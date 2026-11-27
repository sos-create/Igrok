package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Resource-aware shelter builder.
 *
 * Every placed block is paid from Igrok's own inventory. The plan is checked
 * before site search so the bot does not walk away and discover half-way
 * through that it cannot afford the cabin.
 */
final class HouseBuilder {
    private record BlockStep(int x, int y, int z, Material material,
                             BlockData data, boolean consumeItem) { }

    private final IgrokPlugin plugin;
    private final List<BlockStep> plan = new ArrayList<>();
    private BukkitTask task;
    private Location base;
    private Player actor;
    private int cursor;
    private int placed;
    private boolean completed;
    private String projectType = "shelter";
    private final Set<String> completedProjects = new HashSet<>();
    private Material wallMaterial = Material.OAK_PLANKS;
    private Material glassMaterial;
    private Material doorMaterial;
    private AgentNavigator navigator;
    private Location workStandPosition;
    private int navigationFailures;

    HouseBuilder(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    void saveState(YamlConfiguration data) {
        data.set("builder.active", isBuilding());
        data.set("builder.completed", completed);
        data.set("builder.project-type", projectType);
        data.set("builder.completed-projects", new ArrayList<>(completedProjects));
        if (!isBuilding() || base == null || base.getWorld() == null) return;
        data.set("builder.world", base.getWorld().getName());
        data.set("builder.x", base.getX());
        data.set("builder.y", base.getY());
        data.set("builder.z", base.getZ());
        data.set("builder.cursor", cursor);
        data.set("builder.placed", placed);
        data.set("builder.wall-material", wallMaterial.name());
        data.set("builder.glass-material", glassMaterial == null ? "" : glassMaterial.name());
        data.set("builder.door-material", doorMaterial == null ? "" : doorMaterial.name());
    }

    void resumeSavedState(YamlConfiguration data) {
        completed = data.getBoolean("builder.completed", false);
        // Existing servers may already have a completed house but no skills.yml
        // because the progression system did not exist in older versions.
        if (completed && plugin.skills().level("building") < 2) {
            plugin.skills().gain("building", 22);
        }
        completedProjects.clear();
        completedProjects.addAll(data.getStringList("builder.completed-projects"));
        projectType = data.getString("builder.project-type", "shelter");
        if (!Set.of("shelter", "watchtower", "workshop").contains(projectType)) projectType = "shelter";
        if (!data.getBoolean("builder.active", false) || isBuilding()) return;
        World world = Bukkit.getWorld(data.getString("builder.world", ""));
        Player bot = plugin.controller().bukkitPlayer();
        if (world == null || bot == null) return;
        base = new Location(world, data.getDouble("builder.x"),
                data.getDouble("builder.y"), data.getDouble("builder.z"));
        if (!world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            plugin.getLogger().warning("Igrok cannot resume building: saved chunk is not loaded.");
            return;
        }
        actor = bot;
        navigator = new AgentNavigator(actor, plugin.skills());
        workStandPosition = null;
        navigationFailures = 0;
        wallMaterial = materialOr(data.getString("builder.wall-material", ""), Material.OAK_PLANKS);
        glassMaterial = optionalMaterial(data.getString("builder.glass-material", ""));
        doorMaterial = optionalMaterial(data.getString("builder.door-material", ""));
        plan.clear();
        createProjectPlan(projectType);
        cursor = Math.max(0, Math.min(plan.size(), data.getInt("builder.cursor", 0)));
        placed = Math.max(0, data.getInt("builder.placed", cursor));
        int interval = Math.max(1, plugin.getConfig().getInt("building.block-interval-ticks", 2));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::runPlacementSafely, 20L, interval);
        say("Я продолжил строительство с того места, где остановился.");
    }

    boolean isBuilding() {
        return task != null;
    }

    boolean hasCompleted() {
        return completed;
    }

    boolean canBuild(Player bot) {
        if (bot == null || !bot.isOnline() || isBuilding() || completed
                || !plugin.getConfig().getBoolean("building.enabled", true)) return false;
        actor = bot;
        projectType = "shelter";
        choosePalette();
        plan.clear();
        createProjectPlan(projectType);
        return hasAllMaterials();
    }

    boolean hasCompletedProject(String type) {
        return completedProjects.contains(type);
    }

    String nextProjectType() {
        int skill = plugin.skills().level("building");
        boolean workshopReady = skill >= 4 && !completedProjects.contains("workshop")
                && (completedProjects.contains("watchtower") || plugin.skills().level("crafting") >= 2);
        if (workshopReady) return "workshop";
        if (skill >= 2 && !completedProjects.contains("watchtower")) return "watchtower";
        if (skill >= 4 && !completedProjects.contains("workshop")) return "workshop";
        return "";
    }

    boolean canBuildProject(Player bot) {
        if (bot == null || !bot.isOnline() || isBuilding() || !completed
                || !plugin.getConfig().getBoolean("building.enabled", true)) return false;
        String next = nextProjectType();
        if (next.isBlank()) return false;
        actor = bot;
        projectType = next;
        choosePalette();
        plan.clear();
        createProjectPlan(projectType);
        if (hasAllMaterials()) return true;
        plan.clear();
        return false;
    }

    boolean startProject(Location anchor) {
        if (anchor == null || anchor.getWorld() == null || !canBuildProject(
                plugin.controller().bukkitPlayer())) return false;
        return startPreparedProject(anchor);
    }

    private boolean startPreparedProject(Location anchor) {
        if (actor == null || !actor.isOnline()) return false;
        navigator = new AgentNavigator(actor, plugin.skills());
        workStandPosition = null;
        navigationFailures = 0;
        Location site = findSite(anchor);
        if (site == null) {
            plan.clear();
            return false;
        }
        base = site;
        cursor = 0;
        placed = 0;
        int interval = Math.max(1, plugin.getConfig().getInt("building.block-interval-ticks", 2));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::runPlacementSafely, 1L, interval);
        return true;
    }

    boolean start(Location anchor, Player ignoredPlacingPlayer) {
        if (completed) return startProject(anchor);
        if (isBuilding() || !plugin.getConfig().getBoolean("building.enabled", true)
                || anchor == null || anchor.getWorld() == null) return false;

        // The fake player is the builder and pays the materials; a command sender
        // cannot accidentally donate or duplicate blocks from their own inventory.
        Player chosenActor = plugin.controller().bukkitPlayer();
        if (chosenActor == null || !chosenActor.isOnline()) return false;
        actor = chosenActor;
        navigator = new AgentNavigator(actor, plugin.skills());
        workStandPosition = null;
        navigationFailures = 0;
        projectType = "shelter";
        choosePalette();
        plan.clear();
        createProjectPlan(projectType);
        if (!hasAllMaterials()) {
            plan.clear();
            return false;
        }

        Location site = findSite(anchor);
        if (site == null) {
            plan.clear();
            return false;
        }

        base = site;
        cursor = 0;
        placed = 0;
        int interval = Math.max(1, plugin.getConfig().getInt("building.block-interval-ticks", 2));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::runPlacementSafely, 1L, interval);
        return true;
    }

    void cancel(boolean announce) {
        if (task == null) return;
        task.cancel();
        task = null;
        plan.clear();
        workStandPosition = null;
        if (navigator != null) navigator.stop();
        if (announce) say("Остановил строительство. Уже поставленные блоки оставлены на месте.");
    }

    private void choosePalette() {
        wallMaterial = mostCommonSuffix("_PLANKS", Material.OAK_PLANKS);
        glassMaterial = count(actor, Material.GLASS_PANE) >= 3 ? Material.GLASS_PANE
                : count(actor, Material.GLASS) >= 3 ? Material.GLASS : null;
        doorMaterial = firstAvailableDoor();
    }

    private Material mostCommonSuffix(String suffix, Material fallback) {
        Material best = fallback;
        int bestCount = 0;
        for (Material material : Material.values()) {
            if (!material.name().endsWith(suffix)) continue;
            int amount = count(actor, material);
            if (amount > bestCount) {
                bestCount = amount;
                best = material;
            }
        }
        return best;
    }

    private Material firstAvailableDoor() {
        for (Material material : Material.values()) {
            if (material.name().endsWith("_DOOR") && count(actor, material) >= 1) return material;
        }
        return null;
    }

    private void runPlacementSafely() {
        try {
            placeNextBlock();
        } catch (RuntimeException error) {
            plugin.reportRuntimeError("builder", error);
            if (task != null) {
                task.cancel();
                task = null;
            }
            if (navigator != null) navigator.stop();
            plan.clear();
            workStandPosition = null;
        }
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
        if (isForbiddenMaterial(step.material())) {
            stopForSafety("Строительство остановлено: этот материал запрещён моими правилами безопасности.");
            return;
        }
        if (step.consumeItem() && count(actor, step.material()) < 1) {
            stopForSafety("Строительство остановлено: в инвентаре закончился материал " + step.material() + ".");
            return;
        }

        World world = base.getWorld();
        int x = base.getBlockX() + step.x();
        int y = base.getBlockY() + step.y();
        int z = base.getBlockZ() + step.z();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            stopForSafety("Строительство остановлено: следующий участок мира не загружен.");
            return;
        }
        Block target = world.getBlockAt(x, y, z);
        if (!target.isEmpty()) {
            stopForSafety("Строительство остановлено: место для следующего блока занято.");
            return;
        }
        if (actor.getGameMode() != GameMode.SURVIVAL) {
            stopForSafety("Строительство остановлено: бот должен находиться в режиме выживания.");
            return;
        }

        // A normal survival player must physically reach the block and have line of sight.
        if (!withinPlacementReach(target)) {
            if (navigator == null) navigator = new AgentNavigator(actor, plugin.skills());
            if (workStandPosition == null || !isSafeStandingSpot(workStandPosition)) {
                workStandPosition = findBuildStandPosition(target);
                navigationFailures = 0;
            }
            if (workStandPosition == null) {
                stopForSafety("Не нашёл безопасное место, откуда можно поставить следующий блок.");
                return;
            }
            if (actor.getLocation().distanceSquared(workStandPosition) > 0.64) {
                boolean moving = navigator.moveTo(workStandPosition);
                if (!moving && actor.getLocation().distanceSquared(workStandPosition) > 0.64) {
                    navigationFailures++;
                    if (navigationFailures > 50) {
                        stopForSafety("Не смог дойти до места строительства; удалённая установка блоков запрещена.");
                    }
                } else {
                    navigationFailures = 0;
                }
                return;
            }
            if (!withinPlacementReach(target)) {
                workStandPosition = null;
                navigationFailures++;
                if (navigationFailures > 12) {
                    stopForSafety("Не удалось подойти к блоку с доступной стороны.");
                }
                return;
            }
        }

        BlockPlaceEvent event = new BlockPlaceEvent(target, target.getState(),
                target.getRelative(BlockFace.DOWN), new ItemStack(step.material()),
                actor, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            stopForSafety("Строительство остановлено: сервер или другой плагин запретил ставить блоки здесь.");
            return;
        }

        BlockData data = step.data() == null ? step.material().createBlockData() : step.data().clone();
        target.setBlockData(data, false);
        if (step.consumeItem()) consumeOne(actor, step.material());
        cursor++;
        placed++;
        workStandPosition = null;
        navigationFailures = 0;
        if (cursor >= plan.size()) finish();
    }

    private boolean hasAllMaterials() {
        Map<Material, Integer> required = new EnumMap<>(Material.class);
        for (BlockStep step : plan) {
            if (step.consumeItem()) required.merge(step.material(), 1, Integer::sum);
        }
        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            if (count(actor, entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    private int count(Player player, Material material) {
        if (player == null || material == null) return 0;
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private void consumeOne(Player player, Material material) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, item);
            }
            return;
        }
    }

    private boolean withinPlacementReach(Block target) {
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        return BlockPerception.canSee(actor.getEyeLocation(), target, 4.5);
    }

    private Location findBuildStandPosition(Block target) {
        if (navigator == null) navigator = new AgentNavigator(actor, plugin.skills());
        World world = target.getWorld();
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        Location current = actor.getLocation();
        Location best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -1; dy <= 3; dy++) {
                        Location candidate = new Location(world, target.getX() + dx + 0.5,
                                target.getY() + dy, target.getZ() + dz + 0.5,
                                current.getYaw(), 0.0F);
                        if (!isSafeStandingSpot(candidate)) continue;
                        Location eye = candidate.clone().add(0.0, 1.62, 0.0);
                        Vector direction = center.toVector().subtract(eye.toVector());
                        double distance = direction.length();
                        if (distance < 0.1 || distance > 4.5) continue;
                        if (!BlockPerception.canSee(eye, target, 4.5)) continue;
                        double score = current.distanceSquared(candidate) + distance * 0.15;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean isSafeStandingSpot(Location location) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()
                || !world.isChunkLoaded(x >> 4, z >> 4)
                || !world.getWorldBorder().isInside(location)) return false;
        if (navigator != null && !navigator.canStandAt(location)) return false;
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                && !isHazard(feet.getType()) && !isHazard(head.getType())
                && floor.getType().isSolid() && !floor.isLiquid() && !isHazard(floor.getType());
    }

    private boolean isHazard(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE,
                    WITHER_ROSE, SWEET_BERRY_BUSH, POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    private void stopForSafety(String message) {
        cancel(false);
        say(message + " Уже поставленные блоки не удалял.");
    }

    private void finish() {
        if (task != null) task.cancel();
        task = null;
        if ("shelter".equals(projectType)) completed = true;
        else completedProjects.add(projectType);
        plan.clear();
        workStandPosition = null;
        if (navigator != null) navigator.stop();
        plugin.skills().gain("building", 4);
        String completedName = switch (projectType) {
            case "watchtower" -> "смотровую башню";
            case "workshop" -> "мастерскую";
            default -> "убежище";
        };
        say("Готово! Я построил " + completedName + " из " + placed
                + " блоков, используя собственные ресурсы. Это новый опыт для моих строительных навыков.");
    }

    private Location findSite(Location anchor) {
        World world = anchor.getWorld();
        int radius = Math.max(12, Math.min(20, plugin.getConfig().getInt("building.search-radius", 16)));
        int originX = anchor.getBlockX();
        int originZ = anchor.getBlockZ();
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared >= 36 && distanceSquared <= radius * radius) {
                    offsets.add(new int[]{dx, dz, distanceSquared});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset[2]));
        for (int[] offset : offsets) {
            int centerX = originX + offset[0];
            int centerZ = originZ + offset[1];
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
                if (world.getHighestBlockYAt(x, z) != groundY) return false;
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

    private boolean isForbiddenMaterial(Material material) {
        String name = material.name();
        return name.equals("TNT") || name.equals("FIRE") || name.equals("SOUL_FIRE")
                || name.equals("CAMPFIRE") || name.equals("SOUL_CAMPFIRE")
                || name.equals("FIRE_CHARGE");
    }

    private void createProjectPlan(String type) {
        plan.clear();
        switch (type) {
            case "watchtower" -> createWatchtowerPlan();
            case "workshop" -> createWorkshopPlan();
            default -> createCabinPlan();
        }
    }

    private void createWatchtowerPlan() {
        // Compact lookout: a stone base, four timber supports and a raised roof deck.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) add(x, 0, z, Material.COBBLESTONE);
        }
        for (int y = 1; y <= 3; y++) {
            add(-1, y, -1, wallMaterial);
            add(1, y, -1, wallMaterial);
            add(-1, y, 1, wallMaterial);
            add(1, y, 1, wallMaterial);
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue; // Leave a hatch onto the lookout deck.
                add(x, 4, z, wallMaterial);
            }
        }
        // Open rails and a short block staircase let the agent climb onto the deck.
        add(0, 3, -1, wallMaterial);
        add(0, 3, 1, wallMaterial);
        add(-1, 3, 0, wallMaterial);
        add(1, 3, 0, wallMaterial);
        add(0, 0, 2, wallMaterial);
        add(0, 1, 1, wallMaterial);
        add(0, 2, 0, wallMaterial);
        add(0, 3, 0, wallMaterial);
    }

    private void createWorkshopPlan() {
        // A compact 3x3 workroom: inexpensive enough to be a second project,
        // but with a real door, light from windows and a roof.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) add(x, 0, z, Material.COBBLESTONE);
        }
        for (int y = 1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (Math.abs(x) != 1 && Math.abs(z) != 1) continue;
                    boolean doorway = z == 1 && x == 0 && y <= 2;
                    boolean window = glassMaterial != null && y == 2
                            && ((z == -1 && x == 0) || (x == -1 && z == 0));
                    if (doorway || window) continue;
                    add(x, y, z, wallMaterial);
                }
            }
        }
        if (doorMaterial != null) {
            Door lower = (Door) doorMaterial.createBlockData();
            lower.setFacing(BlockFace.SOUTH);
            lower.setHalf(Bisected.Half.BOTTOM);
            Door upper = (Door) lower.clone();
            upper.setHalf(Bisected.Half.TOP);
            addData(0, 1, 1, doorMaterial, lower, true);
            addData(0, 2, 1, doorMaterial, upper, false);
        }
        if (glassMaterial != null) {
            add(0, 2, -1, glassMaterial);
            add(-1, 2, 0, glassMaterial);
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) add(x, 3, z, wallMaterial);
        }
    }

    private void createCabinPlan() {
        // Compact 5x5 shelter. Its palette comes from materials Igrok actually owns.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                Material floor = Math.abs(x) == 2 || Math.abs(z) == 2
                        ? Material.COBBLESTONE : wallMaterial;
                add(x, 0, z, floor);
            }
        }

        for (int y = 1; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
                    boolean doorway = z == 2 && x == 0 && y <= 2;
                    boolean window = glassMaterial != null && y == 2
                            && ((z == -2 && x == 0) || (x == -2 && z == 0) || (x == 2 && z == 0));
                    if (doorway || window) continue;
                    add(x, y, z, wallMaterial);
                }
            }
        }

        if (doorMaterial != null) {
            Door lower = (Door) doorMaterial.createBlockData();
            lower.setFacing(BlockFace.SOUTH);
            lower.setHalf(Bisected.Half.BOTTOM);
            Door upper = (Door) lower.clone();
            upper.setHalf(Bisected.Half.TOP);
            addData(0, 1, 2, doorMaterial, lower, true);
            addData(0, 2, 2, doorMaterial, upper, false);
        }
        if (glassMaterial != null) {
            add(-2, 2, 0, glassMaterial);
            add(2, 2, 0, glassMaterial);
            add(0, 2, -2, glassMaterial);
        }

        // Compact roof keeps the shelter affordable so it remains one
        // possible project among several, rather than a long mandatory grind.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) add(x, 3, z, wallMaterial);
        }
        add(0, 0, 3, Material.COBBLESTONE);
    }

    private void add(int x, int y, int z, Material material) {
        plan.add(new BlockStep(x, y, z, material, null, true));
    }

    private void addData(int x, int y, int z, Material material, BlockData data, boolean consumeItem) {
        plan.add(new BlockStep(x, y, z, material, data, consumeItem));
    }

    private Material materialOr(String name, Material fallback) {
        Material value = optionalMaterial(name);
        return value == null ? fallback : value;
    }

    private Material optionalMaterial(String name) {
        if (name == null || name.isBlank()) return null;
        return Material.matchMaterial(name);
    }

    private void say(String message) {
        Bukkit.broadcastMessage("§6Igrok§7: §f" + message);
    }
}
