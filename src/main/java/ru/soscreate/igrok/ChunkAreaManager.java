package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a small active area around Igrok and a rotating scouting cache out to
 * eight chunks. Chunk generation is requested asynchronously and concurrency
 * is bounded so exploration cannot flood the server with chunk work.
 */
final class ChunkAreaManager {
    private static final long REFRESH_TICKS = 40L;

    private final IgrokPlugin plugin;
    private final int viewRadius;
    private final int activeRadius;
    private final int scoutCache;
    private final int maxInFlight;
    private final FakePlayerController controller;
    private final Set<ChunkKey> tickets = new HashSet<>();
    private final Set<ChunkKey> pending = new HashSet<>();
    private final Set<ChunkKey> desired = new LinkedHashSet<>();
    private final Queue<ChunkKey> queue = new ArrayDeque<>();
    private BukkitTask task;
    private int inFlight;
    private long ticks;

    ChunkAreaManager(IgrokPlugin plugin, FakePlayerController controller) {
        this.plugin = plugin;
        this.controller = controller;
        this.viewRadius = Math.max(2, Math.min(8, plugin.getConfig().getInt("chunks.view-radius", 8)));
        this.activeRadius = Math.max(1, Math.min(2, plugin.getConfig().getInt("chunks.active-radius", 1)));
        this.scoutCache = Math.max(0, Math.min(48, plugin.getConfig().getInt("chunks.scout-cache", 24)));
        this.maxInFlight = Math.max(1, Math.min(2, plugin.getConfig().getInt("chunks.max-async-loads", 2)));
    }

    void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks += 10L;
            if (ticks % REFRESH_TICKS == 0L) refresh();
            pumpQueue();
        }, 1L, 10L);
        refresh();
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        queue.clear();
        desired.clear();
        pending.clear();
        inFlight = 0;
        for (ChunkKey key : new HashSet<>(tickets)) {
            World world = Bukkit.getWorld(key.worldId());
            if (world != null) world.removePluginChunkTicket(key.x(), key.z(), plugin);
        }
        tickets.clear();
    }

    private void refresh() {
        Player bot = controller.bukkitPlayer();
        if (bot == null || !bot.isOnline() || bot.isDead()) return;

        World world = bot.getWorld();
        int centerX = bot.getLocation().getBlockX() >> 4;
        int centerZ = bot.getLocation().getBlockZ() >> 4;
        Set<ChunkKey> next = new LinkedHashSet<>();

        // The 3x3 active area is always loaded and has highest priority.
        for (int dx = -ACTIVE_RADIUS; dx <= activeRadius; dx++) {
            for (int dz = -ACTIVE_RADIUS; dz <= activeRadius; dz++) {
                next.add(new ChunkKey(world.getUID(), centerX + dx, centerZ + dz));
            }
        }

        // Scout up to eight chunks away. Select a bounded cache in the direction
        // the avatar faces, while still covering all sides over successive refreshes.
        Location facing = bot.getLocation();
        org.bukkit.util.Vector look = facing.getDirection().setY(0);
        if (look.lengthSquared() > 0.0001) look.normalize();

        List<ScoutCandidate> candidates = new ArrayList<>();
        for (int dx = -viewRadius; dx <= viewRadius; dx++) {
            for (int dz = -viewRadius; dz <= viewRadius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) > viewRadius) continue;
                if (Math.abs(dx) <= activeRadius && Math.abs(dz) <= activeRadius) continue;
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > viewRadius + 0.001) continue;
                double dot = look.lengthSquared() < 0.0001 ? 0.0
                        : (dx * look.getX() + dz * look.getZ()) / Math.max(1.0, distance);
                double score = distance - dot * 1.75;
                candidates.add(new ScoutCandidate(dx, dz, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(ScoutCandidate::score));

        // Rotate the preferred direction a little each refresh so the cache does
        // not permanently ignore the sides and rear of the bot.
        int rotation = (int) ((ticks / REFRESH_TICKS) % 4);
        int added = 0;
        for (ScoutCandidate candidate : candidates) {
            int dx = candidate.dx();
            int dz = candidate.dz();
            int rx = switch (rotation) {
                case 1 -> -dz;
                case 2 -> -dx;
                case 3 -> dz;
                default -> dx;
            };
            int rz = switch (rotation) {
                case 1 -> dx;
                case 2 -> -dz;
                case 3 -> -dx;
                default -> dz;
            };
            ChunkKey key = new ChunkKey(world.getUID(), centerX + rx, centerZ + rz);
            if (next.add(key) && ++added >= scoutCache) break;
        }

        desired.clear();
        desired.addAll(next);

        // Release old plugin tickets immediately after the avatar has moved away.
        for (ChunkKey key : new HashSet<>(tickets)) {
            if (!desired.contains(key)) {
                World oldWorld = Bukkit.getWorld(key.worldId());
                if (oldWorld != null) oldWorld.removePluginChunkTicket(key.x(), key.z(), plugin);
                tickets.remove(key);
            }
        }

        queue.clear();
        for (ChunkKey key : desired) {
            if (!tickets.contains(key) && !pending.contains(key)) queue.add(key);
        }
    }

    private void pumpQueue() {
        while (inFlight < maxInFlight && !queue.isEmpty()) {
            ChunkKey key = queue.poll();
            if (key == null || !desired.contains(key) || tickets.contains(key) || !pending.add(key)) continue;
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                pending.remove(key);
                continue;
            }
            inFlight++;
            world.getChunkAtAsync(key.x(), key.z(), true).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pending.remove(key);
                    inFlight = Math.max(0, inFlight - 1);
                    if (error != null) {
                        plugin.getLogger().fine("Igrok scout chunk load failed at "
                                + key.x() + "," + key.z() + ": " + error.getMessage());
                        return;
                    }
                    World currentWorld = Bukkit.getWorld(key.worldId());
                    if (currentWorld == null || !desired.contains(key)
                            || !currentWorld.isChunkLoaded(key.x(), key.z())) return;
                    if (currentWorld.addPluginChunkTicket(key.x(), key.z(), plugin)) {
                        tickets.add(key);
                    }
                });
            });
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) { }
    private record ScoutCandidate(int dx, int dz, double score) { }
}
