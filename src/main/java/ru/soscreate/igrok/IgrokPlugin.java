package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import com.destroystokyo.paper.profile.ProfileProperty;

public final class IgrokPlugin extends JavaPlugin implements Listener {
    private FakePlayerController controller;
    private ChatDialogue dialogue;
    private SocialLife socialLife;
    private HouseBuilder houseBuilder;
    private AgentBrain brain;
    private AgentMemory memory;
    private AgentSkillBook skills;
    private CognitiveNetwork cognition;
    private ChunkAreaManager chunkAreaManager;
    private BukkitTask respawnTask;
    private BukkitTask brainTask;
    private BukkitTask memorySaveTask;
    private BukkitTask stateSaveTask;
    private BukkitTask cognitionSaveTask;
    private BukkitTask inventorySaveTask;
    private BukkitTask skinFallbackTask;
    private File stateFile;
    private YamlConfiguration stateData;
    // Access-ordered and capped so UUIDs from players who left cannot accumulate forever.
    private final Map<UUID, Long> lastExplorationObservation =
            new java.util.LinkedHashMap<>(64, 0.75F, true);
    private final Map<String, Long> runtimeErrorLogTimes = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().addDefault("chunks.enabled", false);
        getConfig().addDefault("chunks.view-radius", 8);
        getConfig().addDefault("chunks.active-radius", 1);
        getConfig().addDefault("chunks.scout-cache", 24);
        getConfig().addDefault("chunks.max-async-loads", 2);
        getConfig().options().copyDefaults(true);
        // Defaults are copied only for missing settings. Existing server-owner
        // values are preserved instead of being overwritten on every restart.
        saveConfig();
        stateFile = new File(getDataFolder(), "agent-state.yml");
        stateData = YamlConfiguration.loadConfiguration(stateFile);
        controller = new FakePlayerController(this);
        chunkAreaManager = new ChunkAreaManager(this, controller);
        houseBuilder = new HouseBuilder(this);
        memory = new AgentMemory(getDataFolder());
        memory.load();
        skills = new AgentSkillBook(new File(getDataFolder(), "skills.yml"));
        skills.load();
        brain = new AgentBrain(this, memory);
        cognition = new CognitiveNetwork(new File(new File(getDataFolder(), "memory"), "cognition.yml"));
        brain.loadState(stateData);
        dialogue = new ChatDialogue(this);
        socialLife = new SocialLife(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(dialogue, this);
        Bukkit.getPluginManager().registerEvents(socialLife, this);
        brainTask = Bukkit.getScheduler().runTaskTimer(this, () -> runSafely("brain", brain::tick), 2L, 2L);
        memorySaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> runSafely("memory-save", memory::save), 6000L, 6000L);
        stateSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> runSafely("state-save", this::savePersistentState), 600L, 600L);
        cognitionSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> runSafely("cognition-save", cognition::save), 6000L, 6000L);
        inventorySaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> runSafely("inventory-save", controller::saveInventory), 6000L, 6000L);

        if (getConfig().getBoolean("auto-spawn", true)) {
            Bukkit.getScheduler().runTask(this, this::spawnWithConfiguredSkin);
        }
        getLogger().info("Igrok " + getDescription().getVersion() + " survival player agent enabled for Paper 1.21.4 (vanilla physics enabled).");
    }

    @Override
    public void onDisable() {
        if (respawnTask != null) respawnTask.cancel();
        if (chunkAreaManager != null) chunkAreaManager.stop();
        if (skinFallbackTask != null) skinFallbackTask.cancel();
        if (dialogue != null) dialogue.shutdown();
        if (brainTask != null) brainTask.cancel();
        if (stateSaveTask != null) stateSaveTask.cancel();
        if (cognitionSaveTask != null) cognitionSaveTask.cancel();
        if (inventorySaveTask != null) inventorySaveTask.cancel();
        if (controller != null) controller.saveInventory();
        savePersistentState();
        if (memorySaveTask != null) memorySaveTask.cancel();
        if (houseBuilder != null) houseBuilder.cancel(false);
        if (memory != null) memory.save();
        if (skills != null) skills.save();
        if (cognition != null) cognition.save();
        if (controller != null) controller.remove();
    }


    /**
     * Keeps an unexpected plugin exception from cancelling a repeating Bukkit task.
     * Identical stack traces are logged at most once per component every 30 seconds.
     */
    private void runSafely(String component, Runnable operation) {
        if (!isEnabled()) return;
        try {
            operation.run();
        } catch (RuntimeException error) {
            reportRuntimeError(component, error);
        }
    }

    void reportRuntimeError(String component, RuntimeException error) {
        if (error == null) return;
        long now = System.currentTimeMillis();
        String key = component + ":" + error.getClass().getName();
        long previous = runtimeErrorLogTimes.getOrDefault(key, 0L);
        if (now - previous >= 30_000L) {
            runtimeErrorLogTimes.put(key, now);
            getLogger().log(Level.SEVERE,
                    "[Igrok] Ошибка в модуле '" + component
                            + "'. Повтор этой ошибки будет ограничен; стек ниже нужен для диагностики.",
                    error);
        }
        if (runtimeErrorLogTimes.size() > 128) {
            runtimeErrorLogTimes.entrySet().removeIf(entry -> now - entry.getValue() > 120_000L);
        }
    }

    private void spawnWithConfiguredSkin() {
        String skinOwner = getConfig().getString("skin-owner", "").trim();
        if (skinOwner.isBlank()) {
            controller.setSkinProperties(List.of());
            finishSpawn();
            return;
        }

        final org.bukkit.profile.PlayerProfile lookupProfile;
        try {
            lookupProfile = Bukkit.createProfile(skinOwner);
        } catch (IllegalArgumentException error) {
            getLogger().warning("Invalid skin-owner '" + skinOwner + "'. Using the standard Minecraft skin: "
                    + error.getMessage());
            controller.setSkinProperties(List.of());
            finishSpawn();
            return;
        }

        AtomicBoolean decided = new AtomicBoolean(false);
        // Never hold up server startup waiting for Mojang profile services.
        skinFallbackTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!decided.compareAndSet(false, true)) return;
            getLogger().warning("Skin lookup for '" + skinOwner
                    + "' timed out after 5 seconds; spawning with the standard Minecraft skin.");
            controller.setSkinProperties(List.of());
            finishSpawn();
        }, 100L);

        try {
            lookupProfile.update().whenComplete((updated, error) -> {
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!isEnabled() || !decided.compareAndSet(false, true)) return;

                    if (skinFallbackTask != null) {
                        skinFallbackTask.cancel();
                        skinFallbackTask = null;
                    }

                    List<ProfileProperty> textures = List.of();
                    if (error == null
                            && updated instanceof com.destroystokyo.paper.profile.PlayerProfile paperProfile) {
                        textures = paperProfile.getProperties().stream()
                                .filter(property -> "textures".equals(property.getName()))
                                .filter(property -> property.getValue() != null && !property.getValue().isBlank())
                                .filter(property -> property.getSignature() != null
                                        && !property.getSignature().isBlank())
                                .toList();
                    } else if (error != null) {
                        getLogger().warning("Could not resolve skin for '" + skinOwner + "': "
                                + error.getMessage());
                    }
                    if (textures.isEmpty()) {
                        getLogger().warning("No signed Minecraft skin texture found for '" + skinOwner
                                + "'. Using the standard Minecraft skin.");
                    } else {
                        getLogger().info("Loaded signed Minecraft skin texture from '" + skinOwner + "'.");
                    }
                    controller.setSkinProperties(textures);
                    finishSpawn();
                });
            });
        } catch (Throwable error) {
            if (decided.compareAndSet(false, true)) {

                if (skinFallbackTask != null) {
                    skinFallbackTask.cancel();
                    skinFallbackTask = null;
                }
                getLogger().warning("Could not start skin lookup for '" + skinOwner + "': "
                        + error.getMessage() + ". Using the standard Minecraft skin.");
                controller.setSkinProperties(List.of());
                finishSpawn();
            }
        }
    }

    private void finishSpawn() {
        if (controller.isSpawned()) return;
        if (!spawnAtSavedLocation()) spawnAtDefaultWorldSpawn();
    }

    /**
     * Loads the target chunk asynchronously. This is a temporary chunk access, not a
     * force-load ticket; no server thread is blocked while terrain is read/generated.
     */
    private void loadSpawnChunk(Location target, boolean fallbackToWorldSpawn) {
        if (target == null || target.getWorld() == null || !isEnabled()) return;
        World world = target.getWorld();
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!isEnabled() || controller.isSpawned()) return;
                if (error != null || chunk == null) {
                    getLogger().warning("Could not asynchronously load Igrok spawn chunk at "
                            + chunkX + ", " + chunkZ + ": " + (error == null ? "chunk unavailable" : error.getMessage()));
                    if (fallbackToWorldSpawn) spawnAtDefaultWorldSpawn();
                    return;
                }
                Location safe = findSafeSpawn(target);
                if (safe == null) {
                    getLogger().warning("Configured Igrok spawn is unsafe after chunk load; searching nearby safe ground.");
                    if (fallbackToWorldSpawn) spawnAtDefaultWorldSpawn();
                    return;
                }
                if (!controller.spawn(safe, getConfig().getString("bot-name", "Igrok"))) {
                    getLogger().severe("Igrok spawn failed after the target chunk was loaded; see the preceding error.");
                    return;
                }
                getLogger().info("Igrok spawned successfully in loaded chunk " + chunkX + ", " + chunkZ + ".");
                // A construction paused for combat must be rebound to the new
                // fake-player entity before the general saved-state restore runs.
                houseBuilder.onBotRespawn(controller.bukkitPlayer());
                houseBuilder.resumeSavedState(stateData);
                if (chunkAreaManager != null && getConfig().getBoolean("chunks.enabled", true)) {
                    chunkAreaManager.start();
                }
            });
        });
    }

    private boolean spawnAtSavedLocation() {
        String worldName = stateData.getString("bot.world", "");
        World world = worldName.isBlank() ? null : Bukkit.getWorld(worldName);
        if (world == null || !stateData.contains("bot.x")) return false;
        Location saved = new Location(world, stateData.getDouble("bot.x"), stateData.getDouble("bot.y"),
                stateData.getDouble("bot.z"), (float) stateData.getDouble("bot.yaw"),
                (float) stateData.getDouble("bot.pitch"));
        loadSpawnChunk(saved, true);
        return true;
    }

    private void savePersistentState() {
        if (stateData == null) return;
        if (brain != null) brain.saveState(stateData);
        if (houseBuilder != null) houseBuilder.saveState(stateData);
        if (skills != null) skills.save();
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
        loadSpawnChunk(spawn, false);
        return true;
    }

    /** Finds a safe standing position near the requested point after its chunk is loaded. */
    private Location findSafeSpawn(Location origin) {
        if (origin == null || origin.getWorld() == null) return null;
        World world = origin.getWorld();
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5,
                            origin.getYaw(), origin.getPitch());
                    if (isSafeSpawn(candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private boolean isSafeSpawn(Location location) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()
                || !world.isChunkLoaded(x >> 4, z >> 4)
                || !world.getWorldBorder().isInside(location)) return false;
        org.bukkit.block.Block feet = world.getBlockAt(x, y, z);
        org.bukkit.block.Block head = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block floor = world.getBlockAt(x, y - 1, z);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid()
                && !head.isLiquid() && !isHazardBlock(feet.getType()) && !isHazardBlock(head.getType())
                && floor.getType().isSolid() && !floor.isLiquid() && !isHazardBlock(floor.getType());
    }

    private boolean isHazardBlock(org.bukkit.Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE,
                    WITHER_ROSE, SWEET_BERRY_BUSH, POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBlockBreak(BlockBreakEvent event) {
        memory.observeAction(event.getPlayer().getUniqueId(), "mine");
        cognition.observeBehavior("mine");
        if (controller != null && controller.isBot(event.getPlayer()) && skills != null) {
            skills.gain("mining", 2);
            skills.gain("awareness", 1);
            Material type = event.getBlock().getType();
            if (isCropMaterial(type)) { skills.gain("farming", 2); skills.gain("harvesting", 1); }
            String blockName = type.name();
            if (blockName.endsWith("_LOG") || blockName.endsWith("_WOOD")
                    || blockName.endsWith("_STEM") || blockName.endsWith("_HYPHAE")
                    || blockName.equals("BAMBOO_BLOCK")) skills.gain("woodcutting", 2);
            if (type == Material.DIRT || type == Material.GRAVEL || type == Material.SAND || type == Material.CLAY) skills.gain("excavation", 1);
            if (blockName.endsWith("_ORE") || blockName.equals("ANCIENT_DEBRIS")) skills.gain("ore_lore", 2);
            if (isEngineeringMaterial(type)) { skills.gain("engineering", 1); skills.gain("redstone", 1); }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBlockPlace(BlockPlaceEvent event) {
        memory.observeAction(event.getPlayer().getUniqueId(), "build");
        cognition.observeBehavior("build");
        if (controller != null && controller.isBot(event.getPlayer()) && skills != null) {
            skills.gain("building", 1);
            Material type = event.getBlockPlaced().getType();
            if (isCropMaterial(type) || type == Material.FARMLAND) { skills.gain("farming", 1); skills.gain("gardening", 1); }
            String blockName = type.name();
            if (blockName.contains("STAIRS") || blockName.contains("SLAB") || blockName.contains("BRICK") || blockName.contains("QUARTZ")) skills.gain("architecture", 1);
            if (blockName.contains("BANNER") || blockName.contains("CARPET") || blockName.contains("FLOWER") || blockName.contains("CANDLE") || blockName.contains("GLAZED")) skills.gain("decoration", 1);
            if (blockName.contains("WALL") || blockName.contains("FENCE") || blockName.contains("BARS") || blockName.contains("GATE")) skills.gain("fortification", 1);
            if (isEngineeringMaterial(type)) { skills.gain("engineering", 1); skills.gain("redstone", 1); }
        }
    }

    @EventHandler
    public void onMobKilled(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            memory.observeAction(killer.getUniqueId(), "combat");
            cognition.observeBehavior("combat");
            if (controller != null && controller.isBot(killer) && skills != null) {
                skills.gain("combat", 3);
                skills.gain("survival", 1);
                if (event.getEntity() instanceof Monster) skills.gain("melee_combat", 2);
                else skills.gain("hunting", 2);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
            if (controller != null && controller.isBot(event.getPlayer()) && skills != null) {
                skills.gain("exploration", 1);
                skills.gain("navigation_memory", 1);
                skills.gain("exploration_lore", 1);
            }
            lastExplorationObservation.put(id, now);
            if (lastExplorationObservation.size() > 512) {
                java.util.Iterator<UUID> oldest = lastExplorationObservation.keySet().iterator();
                while (lastExplorationObservation.size() > 384 && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotFishing(PlayerFishEvent event) {
        if (controller == null || skills == null || !controller.isBot(event.getPlayer())) return;
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            skills.gain("fishing", 3);
            skills.gain("fishing_lore", 2);
            skills.gain("survival", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player breeder)
                || controller == null || skills == null || !controller.isBot(breeder)) return;
        skills.gain("animal_care", 3);
        skills.gain("herding", 2);
        skills.gain("survival", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotCook(FurnaceExtractEvent event) {
        if (controller == null || skills == null || !controller.isBot(event.getPlayer())) return;
        String material = event.getItemType().name();
        if (material.startsWith("COOKED_") || material.equals("BAKED_POTATO")) {
            int amount = Math.max(1, Math.min(3, event.getItemAmount()));
            skills.gain("cooking", amount);
            skills.gain("cooking_lore", amount);
        }
    }

    private boolean isCropMaterial(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, MELON_STEM, PUMPKIN_STEM,
                    ATTACHED_MELON_STEM, ATTACHED_PUMPKIN_STEM, NETHER_WART, COCOA,
                    SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    private boolean isEngineeringMaterial(Material material) {
        String name = material.name();
        return name.contains("REDSTONE") || name.contains("REPEATER")
                || name.contains("COMPARATOR") || name.contains("OBSERVER")
                || name.contains("PISTON") || name.equals("LEVER")
                || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player clicked) || !controller.isBot(clicked)) return;
        if (event.getPlayer().getUniqueId().equals(clicked.getUniqueId())) return;
        brain.onPlayerInteraction(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target) || !controller.isBot(target)) return;
        try {
            LivingEntity attacker = null;
            if (event.getDamager() instanceof LivingEntity living) {
                attacker = living;
            } else if (event.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof LivingEntity shooter) {
                attacker = shooter;
            }
            if (attacker instanceof Player player) {
                brain.onPlayerAttack(player);
            } else if (attacker instanceof Monster monster) {
                brain.onMonsterAttack(monster);
            }
        } catch (RuntimeException error) {
            reportRuntimeError("combat-event", error);
        }
    }


    @EventHandler
    public void onBotDeath(PlayerDeathEvent event) {
        if (!controller.isBot(event.getEntity())) return;
        // Vanilla death rules: inventory and experience are dropped/lost normally.
        // The delayed respawn saves the post-death inventory state before creating a new player.
        EntityDamageEvent lastDamage = event.getEntity().getLastDamageCause();
        String cause = lastDamage == null ? "UNKNOWN" : lastDamage.getCause().name();
        String killerType = lastDamage != null && lastDamage instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager().getType().name() : "";
        brain.learnFromDeath(event.getEntity().getLocation(), cause, killerType);
        brain.stop();

        // Persist the death location, learned lesson and current builder cursor
        // before replacing the fake player. Do not cancel the builder's own task:
        // it temporarily waits for the replacement player and rebinds its navigator.
        savePersistentState();
        if (!getConfig().getBoolean("auto-respawn", true)) return;
        if (respawnTask != null) respawnTask.cancel();
        respawnTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            controller.remove();
            // Respawn near the last safe saved position first. If that chunk or
            // the location is unusable, loadSpawnChunk falls back to world spawn.
            if (!spawnAtSavedLocation()) spawnAtDefaultWorldSpawn();
            respawnTask = null;
        }, 40L);
    }

    FakePlayerController controller() { return controller; }
    ChatDialogue dialogue() { return dialogue; }
    SocialLife socialLife() { return socialLife; }
    HouseBuilder houseBuilder() { return houseBuilder; }
    AgentBrain brain() { return brain; }
    CognitiveNetwork cognition() { return cognition; }
    AgentSkillBook skills() { return skills; }
}
