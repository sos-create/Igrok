package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class IgrokPlugin extends JavaPlugin implements Listener {
    private FakePlayerController controller;
    private ChatDialogue dialogue;
    private HouseBuilder houseBuilder;
    private AgentBrain brain;
    private AgentMemory memory;
    private CognitiveNetwork cognition;
    private BukkitTask respawnTask;
    private BukkitTask brainTask;
    private BukkitTask memorySaveTask;
    private BukkitTask stateSaveTask;
    private BukkitTask cognitionSaveTask;
    private BukkitTask inventorySaveTask;
    private File stateFile;
    private YamlConfiguration stateData;
    private final Map<UUID, Long> lastExplorationObservation = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "agent-state.yml");
        stateData = YamlConfiguration.loadConfiguration(stateFile);
        controller = new FakePlayerController(this);
        houseBuilder = new HouseBuilder(this);
        memory = new AgentMemory(getDataFolder());
        memory.load();
        brain = new AgentBrain(this, memory);
        cognition = new CognitiveNetwork(new File(new File(getDataFolder(), "memory"), "cognition.yml"));
        brain.loadState(stateData);
        dialogue = new ChatDialogue(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(dialogue, this);
        brainTask = Bukkit.getScheduler().runTaskTimer(this, brain::tick, 1L, 1L);
        memorySaveTask = Bukkit.getScheduler().runTaskTimer(this, memory::save, 6000L, 6000L);
        stateSaveTask = Bukkit.getScheduler().runTaskTimer(this, this::savePersistentState, 600L, 600L);
        cognitionSaveTask = Bukkit.getScheduler().runTaskTimer(this, cognition::save, 6000L, 6000L);
        inventorySaveTask = Bukkit.getScheduler().runTaskTimer(this, controller::saveInventory, 6000L, 6000L);

        if (getConfig().getBoolean("auto-spawn", true)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!controller.isSpawned() && !spawnAtSavedLocation()) spawnAtDefaultWorldSpawn();
                houseBuilder.resumeSavedState(stateData);
            });
        }
        getLogger().info("Igrok 0.9.6 autonomous survival agent enabled for Paper 1.21.4.");
    }

    @Override
    public void onDisable() {
        if (respawnTask != null) respawnTask.cancel();
        if (brainTask != null) brainTask.cancel();
        if (stateSaveTask != null) stateSaveTask.cancel();
        if (cognitionSaveTask != null) cognitionSaveTask.cancel();
        if (inventorySaveTask != null) inventorySaveTask.cancel();
        if (controller != null) controller.saveInventory();
        savePersistentState();
        if (memorySaveTask != null) memorySaveTask.cancel();
        if (houseBuilder != null) houseBuilder.cancel(false);
        if (memory != null) memory.save();
        if (cognition != null) cognition.save();
        if (controller != null) controller.remove();
    }


    private boolean spawnAtSavedLocation() {
        String worldName = stateData.getString("bot.world", "");
        World world = worldName.isBlank() ? null : Bukkit.getWorld(worldName);
        if (world == null || !stateData.contains("bot.x")) return false;
        Location saved = new Location(world, stateData.getDouble("bot.x"), stateData.getDouble("bot.y"),
                stateData.getDouble("bot.z"), (float) stateData.getDouble("bot.yaw"),
                (float) stateData.getDouble("bot.pitch"));
        if (!world.isChunkLoaded(saved.getBlockX() >> 4, saved.getBlockZ() >> 4)) {
            world.getChunkAt(saved.getBlockX() >> 4, saved.getBlockZ() >> 4);
        }
        return controller.spawn(saved, getConfig().getString("bot-name", "Igrok"));
    }

    private void savePersistentState() {
        if (stateData == null) return;
        if (brain != null) brain.saveState(stateData);
        if (houseBuilder != null) houseBuilder.saveState(stateData);
        Player bot = controller == null ? null : controller.bukkitPlayer();
        if (bot != null) {
            Location loc = bot.getLocation();
            stateData.set("bot.world", loc.getWorld().getName());
            stateData.set("bot.x", loc.getX());
            stateData.set("bot.y", loc.getY());
            stateData.set("bot.z", loc.getZ());
            stateData.set("bot.yaw", loc.getYaw());
            stateData.set("bot.pitch", loc.getPitch());
        }
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return;
            stateData.save(stateFile);
        } catch (IOException error) {
            getLogger().severe("Could not save Igrok state: " + error.getMessage());
        }
    }

    private boolean spawnAtDefaultWorldSpawn() {
        String configuredWorld = getConfig().getString("spawn-world", "");
        World world = configuredWorld == null || configuredWorld.isBlank()
                ? Bukkit.getWorlds().stream().findFirst().orElse(null)
                : Bukkit.getWorld(configuredWorld);
        if (world == null) {
            getLogger().severe("Igrok spawn world was not found. Check spawn-world in config.yml.");
            return false;
        }
        Location spawn = world.getSpawnLocation().clone();
        spawn.setX(spawn.getBlockX() + 0.5);
        spawn.setZ(spawn.getBlockZ() + 0.5);
        return controller.spawn(spawn, getConfig().getString("bot-name", "Igrok"));
    }

    @EventHandler
    public void onPlayerBlockBreak(BlockBreakEvent event) {
        memory.observeAction(event.getPlayer().getUniqueId(), "mine");
        cognition.observeBehavior("mine");
    }

    @EventHandler
    public void onPlayerBlockPlace(BlockPlaceEvent event) {
        memory.observeAction(event.getPlayer().getUniqueId(), "build");
        cognition.observeBehavior("build");
    }

    @EventHandler
    public void onMobKilled(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            memory.observeAction(killer.getUniqueId(), "combat");
            cognition.observeBehavior("combat");
        }
    }

    @EventHandler
    public void onPlayerExplore(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        if ((event.getFrom().getBlockX() >> 4) == (event.getTo().getBlockX() >> 4)
                && (event.getFrom().getBlockZ() >> 4) == (event.getTo().getBlockZ() >> 4)) return;
        long now = System.currentTimeMillis();
        UUID id = event.getPlayer().getUniqueId();
        long previous = lastExplorationObservation.getOrDefault(id, 0L);
        if (now - previous >= 15_000L) {
            memory.observeAction(id, "explore");
            cognition.observeBehavior("explore");
            lastExplorationObservation.put(id, now);
        }
    }

    @EventHandler
    public void onBotInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player clicked) || !controller.isBot(clicked)) return;
        if (event.getPlayer().getUniqueId().equals(clicked.getUniqueId())) return;
        memory.observeAction(event.getPlayer().getUniqueId(), "social");
        cognition.observeBehavior("social");
        brain.onPlayerInteraction(event.getPlayer());
    }

    @EventHandler
    public void onBotHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target) || !controller.isBot(target)) return;
        if (event.getDamager() instanceof Player attacker) {
            cognition.observeBehavior("combat");
            brain.onPlayerAttack(attacker);
        }
    }

    @EventHandler
    public void onBotDeath(PlayerDeathEvent event) {
        if (!controller.isBot(event.getEntity())) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        controller.saveInventory();
        EntityDamageEvent lastDamage = event.getEntity().getLastDamageCause();
        String cause = lastDamage == null ? "UNKNOWN" : lastDamage.getCause().name();
        String killerType = lastDamage != null && lastDamage instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager().getType().name() : "";
        brain.learnFromDeath(event.getEntity().getLocation(), cause, killerType);
        brain.stop();
        houseBuilder.cancel(false);
        if (!getConfig().getBoolean("auto-respawn", true)) return;
        if (respawnTask != null) respawnTask.cancel();
        respawnTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            controller.remove();
            spawnAtDefaultWorldSpawn();
        }, 40L);
    }

    FakePlayerController controller() { return controller; }
    HouseBuilder houseBuilder() { return houseBuilder; }
    AgentBrain brain() { return brain; }
    CognitiveNetwork cognition() { return cognition; }
}
