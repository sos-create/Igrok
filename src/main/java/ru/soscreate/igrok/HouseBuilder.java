package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A small deterministic cabin builder. All world changes happen on the main
 * server thread and are spread over multiple ticks.
 */
final class HouseBuilder {
    private record BlockStep(int x, int y, int z, Material material, BlockData data) { }

    private final IgrokPlugin plugin;
    private final List<BlockStep> plan = new ArrayList<>();
    private BukkitTask task;
    private Location base;
    private Player actor;
    private int cursor;
    private int placed;

    HouseBuilder(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isBuilding() {
        return task != null;
    }

    boolean start(Location anchor, Player placingPlayer) {
        if (isBuilding()) return false;
        if (!plugin.getConfig().getBoolean("building.enabled", true)) {
            return false;
        }
        if (anchor == null || anchor.getWorld() == null) {
            return false;
        }

        Location site = findSite(anchor);
        if (site == null) {
            return false;
        }

        Player chosenActor = placingPlayer;
        if (chosenActor == null || !chosenActor.isOnline()) {
            chosenActor = plugin.controller().bukkitPlayer();
        }
        if (chosenActor == null) {
            return false;
        }

        this.base = site;
        this.actor = chosenActor;
        this.cursor = 0;
        this.placed = 0;
        this.plan.clear();
        createCabinPlan();

        int interval = Math.max(1, plugin.getConfig().getInt("building.block-interval-ticks", 2));
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::placeNextBlock, 1L, interval);
        return true;
    }

    void cancel(boolean announce) {
        if (task == null) return;
        task.cancel();
        task = null;
        plan.clear();
        if (announce) say("Остановил строительство. Уже поставленные блоки оставлены на месте.");
    }

    private void placeNextBlock() {
        if (task == null) return;
        if (cursor >= plan.size()) {
            finish();
            return;
        }

        if (actor == null || !actor.isOnline()) {
            actor = plugin.controller().bukkitPlayer();
            if (actor == null) {
                stopForSafety("Строительство остановлено: бот больше не находится в мире.");
                return;
            }
        }

        BlockStep step = plan.get(cursor);
        World world = base.getWorld();
        Block target = world.getBlockAt(base.getBlockX() + step.x(),
                base.getBlockY() + step.y(), base.getBlockZ() + step.z());

        // Never replace blocks that appeared after the site was checked.
        if (!target.isEmpty()) {
            stopForSafety("Строительство остановлено: место для следующего блока занято.");
            return;
        }

        Block against = target.getRelative(BlockFace.DOWN);
        BlockPlaceEvent event = new BlockPlaceEvent(target, target.getState(), against,
                new ItemStack(step.material()), actor, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            stopForSafety("Строительство остановлено: сервер или другой плагин запретил ставить блоки здесь.");
            return;
        }

        BlockData data = step.data() == null ? step.material().createBlockData() : step.data().clone();
        target.setBlockData(data, false);
        cursor++;
        placed++;

        if (cursor >= plan.size()) finish();
    }

    private void stopForSafety(String message) {
        cancel(false);
        say(message + " Уже поставленные блоки не удалял.");
    }

    private void finish() {
        if (task != null) task.cancel();
        task = null;
        plan.clear();
        say("Дом готов! Я поставил " + placed + " блоков.");
    }

    private Location findSite(Location anchor) {
        World world = anchor.getWorld();
        int radius = Math.max(10, Math.min(24, plugin.getConfig().getInt("building.search-radius", 16)));
        int originX = anchor.getBlockX();
        int originZ = anchor.getBlockZ();
        List<int[]> offsets = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared >= 64 && distanceSquared <= radius * radius) {
                    offsets.add(new int[]{dx, dz, distanceSquared});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset[2]));

        for (int[] offset : offsets) {
            int centerX = originX + offset[0];
            int centerZ = originZ + offset[1];
            // Do not cause chunk loads/generation while looking for a site.
            if (!isAreaLoaded(world, centerX, centerZ)) continue;
            int groundY = world.getHighestBlockYAt(centerX, centerZ);
            if (isSuitableSite(world, centerX, centerZ, groundY)) {
                return new Location(world, centerX, groundY + 1, centerZ);
            }
        }
        return null;
    }

    private boolean isAreaLoaded(World world, int centerX, int centerZ) {
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            }
        }
        return true;
    }

    private boolean isSuitableSite(World world, int centerX, int centerZ, int groundY) {
        if (groundY <= world.getMinHeight() || groundY + 6 >= world.getMaxHeight()) return false;

        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                Location borderCheck = new Location(world, x + 0.5, groundY, z + 0.5);
                if (!world.getWorldBorder().isInside(borderCheck)) return false;

                int columnTop = world.getHighestBlockYAt(x, z);
                if (columnTop != groundY) return false;

                Block ground = world.getBlockAt(x, groundY, z);
                Material groundType = ground.getType();
                if (!groundType.isSolid() || ground.isLiquid() || groundType.hasGravity()
                        || groundType.name().contains("LEAVES")) return false;

                for (int y = groundY + 1; y <= groundY + 5; y++) {
                    if (!world.getBlockAt(x, y, z).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    private void createCabinPlan() {
        // Personality influences the material palette, so different instances
        // of the same blueprint do not all produce an identical cabin.
        boolean boldPalette = plugin.brain().personality().creativity() > 0.58;
        Material wallMaterial = boldPalette ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS;
        Material roofMaterial = boldPalette ? Material.DARK_OAK_STAIRS : Material.SPRUCE_PLANKS;

        // 7x7 foundation and floor.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                Material floor = Math.abs(x) == 3 || Math.abs(z) == 3
                        ? Material.COBBLESTONE : Material.OAK_PLANKS;
                add(x, 0, z, floor);
            }
        }

        // Three-block-high perimeter walls, with log supports at the corners.
        for (int y = 1; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                    if (!edge) continue;

                    boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
                    if (corner) {
                        add(x, y, z, Material.SPRUCE_LOG);
                        continue;
                    }

                    boolean doorway = z == 3 && x == 0 && y <= 2;
                    boolean window = y == 2 && ((z == -3 && x == 0)
                            || (x == -3 && z == 0) || (x == 3 && z == 0));
                    if (doorway || window) continue;
                    add(x, y, z, wallMaterial);
                }
            }
        }

        // Door block data must have a bottom and a top half.
        Door lowerDoor = (Door) Material.OAK_DOOR.createBlockData();
        lowerDoor.setFacing(BlockFace.SOUTH);
        lowerDoor.setHalf(Bisected.Half.BOTTOM);
        Door upperDoor = (Door) lowerDoor.clone();
        upperDoor.setHalf(Bisected.Half.TOP);
        addData(0, 1, 3, Material.OAK_DOOR, lowerDoor);
        addData(0, 2, 3, Material.OAK_DOOR, upperDoor);

        // Simple glass windows.
        add(-3, 2, 0, Material.GLASS_PANE);
        add(3, 2, 0, Material.GLASS_PANE);
        add(0, 2, -3, Material.GLASS_PANE);

        // Flat overhanging roof, intentionally simple and reliable.
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                add(x, 4, z, boldPalette ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);
            }
        }

        // One step outside the front door.
        add(0, 0, 4, Material.COBBLESTONE);
    }

    private void add(int x, int y, int z, Material material) {
        plan.add(new BlockStep(x, y, z, material, null));
    }

    private void addData(int x, int y, int z, Material material, BlockData data) {
        plan.add(new BlockStep(x, y, z, material, data));
    }

    private void say(String message) {
        Bukkit.broadcastMessage("§6Igrok§7: §f" + message);
    }
}
