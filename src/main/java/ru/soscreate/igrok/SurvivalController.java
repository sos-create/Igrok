package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Server-side survival loop for the fake ServerPlayer.
 *
 * It uses the server's own recipe registry and real Bukkit inventory/entity
 * operations. Decisions are made periodically; item pickup and urgent food
 * handling are throttled and bounded to nearby loaded chunks.
 */
final class SurvivalController {
    record AutonomyDecision(GoalPlanner.Goal goal, double confidence,
                            String reason, String miningMode) { }

    private final IgrokPlugin plugin;
    private final AutonomousPlanner autonomousPlanner = new AutonomousPlanner();
    private int nextCraftTick;
    private int nextBedScanTick;
    private int nextHuntDecisionTick;
    private int nextWoodGoalTick;
    private int nextStoneGoalTick;
    private int nextCoalGoalTick;
    private int nextIronGoalTick;
    private int nextFurnaceTick;
    private int nextChestScanTick;
    private int nextFarmTick;
    private Location knownBed;
    private Location knownChest;
    private boolean learnedFoodCaution;

    SurvivalController(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    void setLearnedFoodCaution(boolean enabled) {
        learnedFoodCaution = enabled;
    }

    void tick(Player bot, int tick) {
        int learnedSurvival = plugin.skills().level("survival");
        int foodReserve = Math.min(19, (learnedFoodCaution ? 19 : 17) + learnedSurvival / 3);
        int recoveryReserve = Math.min(20, (learnedFoodCaution ? 20 : 19) + learnedSurvival / 3);
        if (tick % 20 == 0 && (bot.getFoodLevel() <= foodReserve
                || (bot.getHealth() < bot.getMaxHealth() - 2.0 && bot.getFoodLevel() <= recoveryReserve))) {
            eatFromInventory(bot);
        }
        if (tick >= nextCraftTick) {
            craftUsefulItems(bot, tick);
            equipBestArmor(bot);
            // Crafting is a continuous work queue, not a once-per-10-second action.
            // The method below batches a few safe recipes per pass.
            nextCraftTick = tick + 40;
        }
        if (tick >= nextFurnaceTick) {
            maintainFurnace(bot);
            nextFurnaceTick = tick + 200;
        }
        if (tick >= nextFarmTick) {
            harvestNearbyCrops(bot);
            nextFarmTick = tick + 200;
        }
        if (tick % 200 == 0) manageStorage(bot, tick);
        if (tick % 200 == 0) {
            if (!hasNearbyCraftingTable(bot)) placeCraftingTable(bot);
            if (findNearbyBlock(bot, Material.FURNACE, 4) == null && count(bot, Material.FURNACE) > 0) {
                placeFurnace(bot);
            }
            if (isNight(bot) && !hasNearbyBed(bot, tick) && hasBedItem(bot)) placeBed(bot);
        }
        if (bot.isSleeping() && !isNight(bot)) {
            try { bot.wakeup(false); } catch (IllegalStateException ignored) { }
        }
    }

    AutonomyDecision chooseAutonomousGoal(Player bot, AgentPersonality personality,
                                           GoalPlanner.Goal current, int tick) {
        int logs = countBySuffix(bot, "_LOG") + countBySuffix(bot, "_WOOD");
        int planks = countBySuffix(bot, "_PLANKS");
        int coal = count(bot, Material.COAL);
        int charcoal = count(bot, Material.CHARCOAL);
        int torches = count(bot, Material.TORCH);
        boolean hasTable = hasNearbyCraftingTable(bot) || count(bot, Material.CRAFTING_TABLE) > 0;
        boolean hasFurnace = findNearbyBlock(bot, Material.FURNACE, 4) != null
                || count(bot, Material.FURNACE) > 0;
        boolean shelterComplete = plugin.houseBuilder().hasCompleted();
        boolean shelterReady = !shelterComplete && plugin.houseBuilder().canBuild(bot);

        AutonomousPlanner.Snapshot snapshot = new AutonomousPlanner.Snapshot(
                bot.getFoodLevel(),
                hasFood(bot),
                isNight(bot),
                (hasNearbyBed(bot, tick) || hasBedItem(bot)),
                tick >= nextHuntDecisionTick,
                logs,
                planks,
                count(bot, Material.COBBLESTONE),
                coal,
                charcoal,
                torches,
                count(bot, Material.RAW_IRON),
                count(bot, Material.IRON_INGOT),
                bestToolTier(bot, "PICKAXE"),
                bestToolTier(bot, "AXE"),
                hasTable,
                hasFurnace,
                shelterComplete,
                shelterReady,
                plugin.skills().level("building"),
                plugin.houseBuilder().hasCompletedProject("watchtower"),
                plugin.houseBuilder().hasCompletedProject("workshop"),
                tick >= nextWoodGoalTick,
                tick >= nextStoneGoalTick,
                tick >= nextCoalGoalTick,
                tick >= nextIronGoalTick,
                personality.curiosity(),
                personality.creativity(),
                DevelopmentTree.stage(plugin.skills()),
                DevelopmentTree.preferredBranch(plugin.skills()),
                plugin.skills().level("building") >= 4
                        && (plugin.houseBuilder().hasCompletedProject("watchtower")
                            || plugin.skills().level("crafting") >= 2));

        AutonomousPlanner.Decision decision = autonomousPlanner.choose(snapshot);
        if (decision == null) return null;
        return new AutonomyDecision(decision.goal(), decision.utility(),
                "[" + decision.phase() + "] " + decision.reason(), decision.miningMode());
    }

    boolean hasFood(Player bot) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item != null && !item.getType().isAir() && foodValue(item.getType()) > 0) return true;
        }
        return false;
    }

    boolean isNight(Player bot) {
        if (bot.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
        long time = bot.getWorld().getTime();
        return time >= 12500L && time < 23000L;
    }

    boolean sleepTick(Player bot, AgentNavigator navigator, int tick) {
        if (bot.isSleeping()) return true;
        if (!isNight(bot)) {
            knownBed = null;
            return false;
        }
        boolean bedLoaded = knownBed != null && knownBed.getWorld() == bot.getWorld()
                && bot.getWorld().isChunkLoaded(knownBed.getBlockX() >> 4, knownBed.getBlockZ() >> 4);
        if (!bedLoaded || !knownBed.getBlock().getType().name().endsWith("_BED")) {
            knownBed = null;
            if (hasBedItem(bot)) {
                placeBed(bot);
                knownBed = findNearbyBed(bot, 5);
            }
            if (knownBed == null) {
                if (tick < nextBedScanTick) return false;
                knownBed = findNearbyBed(bot, 16);
                nextBedScanTick = tick + 200;
            }
        }
        if (knownBed == null) return false;
        if (knownBed.getWorld() != bot.getWorld()) {
            knownBed = null;
            return false;
        }
        double distance = bot.getLocation().distanceSquared(knownBed.clone().add(0.5, 0.0, 0.5));
        if (distance <= 6.25) {
            navigator.stop();
            if (bot.sleep(knownBed, false)) return true;
            knownBed = null;
            nextBedScanTick = tick + 200;
            return false;
        }
        navigator.moveTo(knownBed);
        return true;
    }

    void deferHunt(int tick) {
        nextHuntDecisionTick = tick + 200;
    }

    void deferMining(String mode, int tick) {
        switch (mode) {
            case "wood" -> nextWoodGoalTick = tick + 400;
            case "coal" -> nextCoalGoalTick = tick + 500;
            case "iron" -> nextIronGoalTick = tick + 600;
            default -> nextStoneGoalTick = tick + 400;
        }
    }

    Animals findFoodAnimal(Player bot, double radius) {
        List<Animals> candidates = bot.getNearbyEntities(radius, 8.0, radius).stream()
                .filter(entity -> entity instanceof Animals)
                .map(entity -> (Animals) entity)
                .filter(animal -> animal.isValid() && !animal.isDead())
                .filter(animal -> animal.getCustomName() == null)
                .filter(animal -> !animal.isLeashed())
                .filter(animal -> {
                    String type = animal.getType().name();
                    return type.equals("COW") || type.equals("MOOSHROOM")
                            || type.equals("PIG") || type.equals("CHICKEN")
                            || type.equals("SHEEP") || type.equals("RABBIT");
                })
                .filter(animal -> animal.getLocation().getWorld() == bot.getWorld())
                .toList();

        // Preserve small herds while food is not yet critical. A lone animal is
        // treated as emergency food only when hunger has become genuinely dangerous.
        Map<String, Integer> herdSizes = new HashMap<>();
        for (Animals animal : candidates) {
            herdSizes.merge(animal.getType().name(), 1, Integer::sum);
        }
        boolean emergencyHunger = bot.getFoodLevel() <= 7;
        return candidates.stream()
                .filter(animal -> emergencyHunger
                        || herdSizes.getOrDefault(animal.getType().name(), 0) >= 3)
                .min((a, b) -> Double.compare(
                        a.getLocation().distanceSquared(bot.getLocation()),
                        b.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    void equipBestWeapon(Player bot) {
        equipBest(bot, "SWORD", "AXE");
    }

    void equipBestTool(Player bot, String toolKind) {
        equipBest(bot, toolKind);
    }

    private void equipBest(Player bot, String... kinds) {
        PlayerInventory inventory = bot.getInventory();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            String name = item.getType().name();
            boolean accepted = false;
            for (String kind : kinds) if (name.endsWith("_" + kind)) accepted = true;
            if (!accepted) continue;
            int score = tier(name) * 1000 + durabilityScore(item);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) return;
        if (bestSlot >= 9) {
            int hotbarSlot = inventory.getHeldItemSlot();
            ItemStack selected = inventory.getItem(hotbarSlot);
            inventory.setItem(hotbarSlot, inventory.getItem(bestSlot));
            inventory.setItem(bestSlot, selected);
            bestSlot = hotbarSlot;
        }
        inventory.setHeldItemSlot(bestSlot);
    }

    private int durabilityScore(ItemStack item) {
        int maximum = item.getType().getMaxDurability();
        if (maximum <= 0 || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) {
            return 0;
        }
        int remaining = Math.max(0, maximum - damageable.getDamage());
        if (remaining <= 1) return -100_000;
        return (int) (remaining * 100.0 / maximum);
    }

    private int tier(String materialName) {
        if (materialName.startsWith("NETHERITE_")) return 6;
        if (materialName.startsWith("DIAMOND_")) return 5;
        if (materialName.startsWith("IRON_")) return 4;
        if (materialName.startsWith("STONE_")) return 3;
        if (materialName.startsWith("GOLDEN_")) return 2;
        if (materialName.startsWith("CHAINMAIL_")) return 2;
        if (materialName.startsWith("LEATHER_")) return 1;
        if (materialName.startsWith("WOODEN_")) return 1;
        return 0;
    }

    private boolean hasTool(Player bot, String suffix) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item == null || !item.getType().name().endsWith("_" + suffix)
                    || item.getType().getMaxDurability() <= 0) continue;
            if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable
                    && item.getType().getMaxDurability() - damageable.getDamage() <= 1) continue;
            return true;
        }
        return false;
    }

    private void equipBestArmor(Player bot) {
        PlayerInventory inventory = bot.getInventory();
        equipArmorPiece(bot, inventory, "_HELMET", 0);
        equipArmorPiece(bot, inventory, "_CHESTPLATE", 1);
        equipArmorPiece(bot, inventory, "_LEGGINGS", 2);
        equipArmorPiece(bot, inventory, "_BOOTS", 3);
    }

    private void equipArmorPiece(Player bot, PlayerInventory inventory, String suffix, int slot) {
        ItemStack current = switch (slot) {
            case 0 -> inventory.getHelmet();
            case 1 -> inventory.getChestplate();
            case 2 -> inventory.getLeggings();
            default -> inventory.getBoots();
        };
        int currentScore = current == null || current.getType().isAir() ? -1
                : tier(current.getType().name()) * 1000 + durabilityScore(current);
        int bestSlot = -1;
        int bestScore = currentScore;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || !item.getType().name().endsWith(suffix)) continue;
            int score = tier(item.getType().name()) * 1000 + durabilityScore(item);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return;
        ItemStack replacement = inventory.getItem(bestSlot);
        switch (slot) {
            case 0 -> inventory.setHelmet(replacement);
            case 1 -> inventory.setChestplate(replacement);
            case 2 -> inventory.setLeggings(replacement);
            default -> inventory.setBoots(replacement);
        }
        inventory.setItem(bestSlot, current == null || current.getType().isAir() ? null : current);
    }

    private void eatFromInventory(Player bot) {
        if (bot.getFoodLevel() >= 20) return;
        PlayerInventory inventory = bot.getInventory();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            int nutrition = foodValue(item.getType());
            if (nutrition <= 0) continue;
            String name = item.getType().name();
            int score = nutrition * 100;
            if (name.startsWith("COOKED_") || name.equals("BREAD")
                    || name.equals("BAKED_POTATO") || name.equals("GOLDEN_CARROT")) score += 30;
            if (isRawFood(name)) score -= bot.getFoodLevel() > 7 ? 60 : 10;
            if (name.equals("ROTTEN_FLESH") || name.equals("POISONOUS_POTATO")
                    || name.equals("PUFFERFISH") || name.equals("SPIDER_EYE")) continue;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) return;
        ItemStack item = inventory.getItem(bestSlot);
        int nutrition = foodValue(item.getType());
        int amount = item.getAmount() - 1;
        if (amount <= 0) inventory.setItem(bestSlot, null);
        else {
            item.setAmount(amount);
            inventory.setItem(bestSlot, item);
        }
        bot.setFoodLevel(Math.min(20, bot.getFoodLevel() + nutrition));
        bot.setSaturation(Math.min(20.0F, bot.getSaturation() + nutrition * 0.6F));
        plugin.skills().gain("survival", 1);
    }

    private boolean isRawFood(String name) {
        return name.equals("BEEF") || name.equals("PORKCHOP") || name.equals("CHICKEN")
                || name.equals("MUTTON") || name.equals("RABBIT")
                || name.equals("COD") || name.equals("SALMON");
    }

    private int foodValue(Material material) {
        String name = material.name();
        if (name.startsWith("COOKED_BEEF") || name.startsWith("COOKED_PORKCHOP")) return 8;
        if (name.equals("PUMPKIN_PIE") || name.equals("RABBIT_STEW")) return 8;
        if (name.equals("COOKED_MUTTON") || name.equals("COOKED_CHICKEN")
                || name.equals("COOKED_SALMON") || name.equals("COOKED_COD")
                || name.equals("MUSHROOM_STEW") || name.equals("BEETROOT_SOUP")) return 6;
        if (name.equals("BREAD") || name.equals("BAKED_POTATO") || name.equals("COOKED_COD")
                || name.equals("COOKED_RABBIT") || name.equals("GOLDEN_CARROT")) return 5;
        if (name.equals("APPLE") || name.equals("CARROT") || name.equals("POTATO")
                || name.equals("MELON_SLICE") || name.equals("SWEET_BERRIES")
                || name.equals("GLOW_BERRIES") || name.equals("COOKIE") || name.equals("BEETROOT")
                || name.equals("CHICKEN") || name.equals("MUTTON")) return 2;
        if (name.equals("BEEF") || name.equals("PORKCHOP")
                || name.equals("RABBIT") || name.equals("COD")
                || name.equals("SALMON")) return 3;
        if (name.equals("DRIED_KELP")) return 1;
        return 0;
    }

    private void craftUsefulItems(Player bot, int tick) {
        if (bot.getNearbyEntities(10, 6, 10).stream().anyMatch(entity -> entity instanceof Monster)) return;
        int planks = countBySuffix(bot, "_PLANKS");
        int logs = countBySuffix(bot, "_LOG") + countBySuffix(bot, "_WOOD");
        int plankReserve = plugin.houseBuilder().hasCompleted() ? 32 : 64;
        if (planks < plankReserve && logs > 0) {
            int batchLimit = 4 + Math.min(4, plugin.skills().level("crafting") / 3);
            for (int batch = 0; batch < batchLimit && planks < plankReserve && logs > 0; batch++) {
                if (!craftMatching(bot, type -> type.name().endsWith("_PLANKS"))) break;
                planks = countBySuffix(bot, "_PLANKS");
                logs = countBySuffix(bot, "_LOG") + countBySuffix(bot, "_WOOD");
            }
        }
        if (count(bot, Material.STICK) < 8 && planks >= 2) {
            if (craftResult(bot, Material.STICK)) return;
        }
        if (count(bot, Material.CRAFTING_TABLE) == 0 && planks >= 4) {
            if (craftResult(bot, Material.CRAFTING_TABLE)) return;
        }
        if (count(bot, Material.CHEST) == 0 && planks >= 8 && hasNearbyCraftingTable(bot)) {
            if (craftResult(bot, Material.CHEST)) return;
        }
        if (hasNearbyCraftingTable(bot)) {
            // A real survival woodcutter needs an axe, not only a pickaxe and sword.
            int axeTier = bestToolTier(bot, "AXE");
            if (axeTier < 1 && planks >= 3 && count(bot, Material.STICK) >= 2) {
                if (craftResult(bot, Material.WOODEN_AXE)) return;
            }
            if (axeTier < 3 && count(bot, Material.COBBLESTONE) >= 3
                    && count(bot, Material.STICK) >= 2) {
                if (craftResult(bot, Material.STONE_AXE)) return;
            }
            if (axeTier < 4 && count(bot, Material.IRON_INGOT) >= 3
                    && count(bot, Material.STICK) >= 2) {
                if (craftResult(bot, Material.IRON_AXE)) return;
            }

            int pickTier = bestToolTier(bot, "PICKAXE");
            if (pickTier < 4 && count(bot, Material.IRON_INGOT) >= 3) {
                if (craftResult(bot, Material.IRON_PICKAXE)) return;
            }
            if (pickTier < 3 && count(bot, Material.COBBLESTONE) >= 3) {
                if (craftResult(bot, Material.STONE_PICKAXE)) return;
            }
            if (pickTier < 1 && planks >= 3) {
                if (craftResult(bot, Material.WOODEN_PICKAXE)) return;
            }

            int swordTier = bestToolTier(bot, "SWORD");
            if (swordTier < 4 && count(bot, Material.IRON_INGOT) >= 2) {
                if (craftResult(bot, Material.IRON_SWORD)) return;
            }
            if (swordTier < 3 && count(bot, Material.COBBLESTONE) >= 2) {
                if (craftResult(bot, Material.STONE_SWORD)) return;
            }
            if (swordTier < 1 && planks >= 2) {
                if (craftResult(bot, Material.WOODEN_SWORD)) return;
            }

            if (count(bot, Material.SHIELD) == 0 && count(bot, Material.IRON_INGOT) >= 1
                    && planks >= 6) {
                if (craftResult(bot, Material.SHIELD)) return;
            }
            if (countBySuffix(bot, "_DOOR") == 0 && planks >= 6) {
                if (craftMatching(bot, type -> type.name().endsWith("_DOOR"))) return;
            }
            if (count(bot, Material.BREAD) < 8 && count(bot, Material.WHEAT) >= 3) {
                if (craftResult(bot, Material.BREAD)) return;
            }
        }
        if (count(bot, Material.TORCH) < 16 && count(bot, Material.STICK) > 0
                && (count(bot, Material.COAL) > 0 || count(bot, Material.CHARCOAL) > 0)) {
            if (craftResult(bot, Material.TORCH)) return;
        }
        if (count(bot, Material.FURNACE) == 0 && count(bot, Material.COBBLESTONE) >= 8
                && hasNearbyCraftingTable(bot)) {
            if (craftResult(bot, Material.FURNACE)) return;
        }
        if (!hasNearbyBed(bot, tick) && !hasBedItem(bot) && countBySuffix(bot, "_WOOL") >= 3
                && planks >= 3 && hasNearbyCraftingTable(bot)) {
            craftMatching(bot, type -> type.name().endsWith("_BED"));
        }
    }

    private int bestToolTier(Player bot, String suffix) {
        int best = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item == null || !item.getType().name().endsWith("_" + suffix)) continue;
            if (durabilityScore(item) <= -100_000) continue;
            best = Math.max(best, tier(item.getType().name()));
        }
        return best;
    }

    int breakTicks(Player bot, Material block, int baseTicks) {
        String tool = block.name().endsWith("_LOG") || block.name().endsWith("_WOOD")
                ? "AXE" : "PICKAXE";
        int level = bestToolTier(bot, tool);
        int learnedMining = plugin.skills().level("mining");
        double speed = 1.0 + Math.max(0, level) * 0.32 + learnedMining * 0.035;
        if (block.name().endsWith("_ORE") && level < 3) speed *= 0.72;
        return Math.max(6, (int) Math.ceil(baseTicks / speed));
    }

    private boolean craftResult(Player bot, Material output) {
        return craftMatching(bot, type -> type == output);
    }

    private boolean craftMatching(Player bot, java.util.function.Predicate<Material> wanted) {
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir() || !wanted.test(result.getType())) continue;
            List<RecipeChoice> ingredients = ingredientChoices(recipe);
            if (ingredients == null || ingredients.isEmpty()) continue;
            if (requiresCraftingTable(recipe, ingredients.size()) && !hasNearbyCraftingTable(bot)) continue;
            if (consumeAndCraft(bot, recipe, ingredients)) return true;
        }
        return false;
    }

    private void manageStorage(Player bot, int tick) {
        if (knownChest == null || knownChest.getWorld() != bot.getWorld()
                || !bot.getWorld().isChunkLoaded(knownChest.getBlockX() >> 4, knownChest.getBlockZ() >> 4)
                || knownChest.getBlock().getType() != Material.CHEST
                || !withinContainerReach(bot, knownChest)) {
            knownChest = null;
            if (tick < nextChestScanTick) return;
            knownChest = findNearbyChest(bot, 4);
            nextChestScanTick = tick + 200;
        }
        if (knownChest == null && count(bot, Material.CHEST) > 0) {
            placeChest(bot);
            knownChest = findNearbyChest(bot, 4);
        }
        if (knownChest == null) return;

        Block block = knownChest.getBlock();
        if (!bot.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
        BlockState state = block.getState();
        if (!(state instanceof Chest chest)) return;
        Inventory storage = chest.getInventory();
        if (!canOpenInventory(bot, storage)) return;
        PlayerInventory inventory = bot.getInventory();

        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            int keep = keepInPersonalInventory(item.getType());
            if (keep < 0 || item.getAmount() <= keep) continue;
            int surplus = item.getAmount() - keep;
            ItemStack deposit = item.clone();
            deposit.setAmount(surplus);
            Map<Integer, ItemStack> leftovers = storage.addItem(deposit);
            int remaining = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            int moved = surplus - remaining;
            if (moved <= 0) continue;
            int leftInSlot = item.getAmount() - moved;
            if (leftInSlot <= 0) inventory.setItem(slot, null);
            else {
                item.setAmount(leftInSlot);
                inventory.setItem(slot, item);
            }
        }
        chest.update(true, false);
    }

    private int keepInPersonalInventory(Material material) {
        String name = material.name();
        if (name.endsWith("_LOG") || name.endsWith("_WOOD")) return 16;
        if (name.endsWith("_PLANKS")) return 32;
        if (material == Material.COBBLESTONE || material == Material.STONE) return 32;
        if (name.endsWith("_ORE") || name.equals("RAW_IRON") || name.equals("RAW_GOLD")
                || name.equals("RAW_COPPER")) return 16;
        if (material == Material.WHEAT || material == Material.WHEAT_SEEDS
                || material == Material.CARROT || material == Material.POTATO
                || material == Material.BEETROOT_SEEDS) return 16;
        return -1;
    }

    private boolean withinContainerReach(Player bot, Location container) {
        if (container == null || container.getWorld() != bot.getWorld()) return false;
        Location center = container.clone().add(0.5, 0.5, 0.5);
        return bot.getEyeLocation().distanceSquared(center) <= 4.5 * 4.5
                && BlockPerception.canSee(bot.getEyeLocation(), bot.getWorld().getBlockAt(container), 4.5);
    }

    private boolean canOpenInventory(Player bot, Inventory inventory) {
        // Use the player's own open-inventory path. Paper fires InventoryOpenEvent
        // and permission/protection plugins can cancel it in the same way as for a human.
        org.bukkit.inventory.InventoryView view = bot.openInventory(inventory);
        if (view == null) return false;
        bot.closeInventory();
        return true;
    }

    private Location findNearbyChest(Player bot, int radius) {
        Location origin = bot.getLocation();
        World world = bot.getWorld();
        Location best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = origin.getBlockY() - 3; y <= origin.getBlockY() + 4; y++) {
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight()) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.CHEST) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    double distance = bot.getEyeLocation().distanceSquared(center);
                    if (distance > 4.5 * 4.5
                            || !BlockPerception.canSee(bot.getEyeLocation(), block, 4.5)) continue;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block.getLocation();
                    }
                }
            }
        }
        return best;
    }

    private boolean placeChest(Player bot) {
        if (count(bot, Material.CHEST) <= 0) return false;
        Block target = findPlacementSpot(bot, 2);
        if (target == null) return false;
        ItemStack item = firstItem(bot, Material.CHEST);
        if (item == null) return false;
        BlockPlaceEvent event = new BlockPlaceEvent(target, target.getState(),
                target.getRelative(BlockFace.DOWN), item.clone(), bot, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) return false;
        target.setType(Material.CHEST);
        consumeOne(bot, Material.CHEST);
        knownChest = target.getLocation();
        return true;
    }

    private void harvestNearbyCrops(Player bot) {
        World world = bot.getWorld();
        Location origin = bot.getLocation();
        int radius = 6;
        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = origin.getBlockY() - 2; y <= origin.getBlockY() + 2; y++) {
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight()) continue;
                    Block crop = world.getBlockAt(x, y, z);
                    if (!(crop.getBlockData() instanceof Ageable age)
                            || age.getAge() < age.getMaximumAge()) continue;
                    Material seed = replantItem(crop.getType());
                    if (seed == null || count(bot, seed) <= 0) continue;
                    Block soil = crop.getRelative(BlockFace.DOWN);
                    if (soil.getType() != Material.FARMLAND && soil.getType() != Material.SOUL_SAND) continue;
                    Material cropType = crop.getType();
                    Location cropCenter = crop.getLocation().add(0.5, 0.5, 0.5);
                    if (bot.getEyeLocation().distanceSquared(cropCenter) > 4.5 * 4.5
                            || !BlockPerception.canSee(bot.getEyeLocation(), crop, 4.5)) continue;
                    if (!bot.breakBlock(crop)) continue;

                    // Replant only with an item the bot already owns; dropped crop yield is picked up
                    // during the next inventory pass, so this never creates free seeds.
                    BlockPlaceEvent event = new BlockPlaceEvent(crop, crop.getState(), soil,
                            new ItemStack(seed), bot, true, EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled() || !event.canBuild()) continue;
                    BlockData newData = cropType.createBlockData();
                    if (newData instanceof Ageable replanted) replanted.setAge(0);
                    crop.setBlockData(newData, false);
                    consumeOne(bot, seed);
                    return;
                }
            }
        }
    }

    private Material replantItem(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            default -> null;
        };
    }

    private boolean placeFurnace(Player bot) {
        if (count(bot, Material.FURNACE) <= 0 || findNearbyBlock(bot, Material.FURNACE, 4) != null) return false;
        Block target = findPlacementSpot(bot, 2);
        if (target == null) return false;
        ItemStack item = firstItem(bot, Material.FURNACE);
        if (item == null) return false;
        BlockPlaceEvent event = new BlockPlaceEvent(target, target.getState(),
                target.getRelative(BlockFace.DOWN), item.clone(), bot, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) return false;
        target.setType(Material.FURNACE);
        consumeOne(bot, Material.FURNACE);
        return true;
    }

    private void maintainFurnace(Player bot) {
        Block block = findNearbyBlock(bot, Material.FURNACE, 4);
        if (block == null) return;
        BlockState state = block.getState();
        if (!(state instanceof Furnace furnace)) return;
        org.bukkit.inventory.FurnaceInventory inventory = furnace.getInventory();
        if (!canOpenInventory(bot, inventory)) return;

        ItemStack result = inventory.getResult();
        if (result != null && !result.getType().isAir()) {
            Map<Integer, ItemStack> leftovers = bot.getInventory().addItem(result.clone());
            if (leftovers.isEmpty()) {
                inventory.setResult(null);
            } else {
                ItemStack left = leftovers.values().iterator().next();
                inventory.setResult(left);
            }
        }

        ItemStack input = inventory.getSmelting();
        if (input == null || input.getType().isAir()) {
            Material smeltable = findSmeltable(bot);
            if (smeltable != null) {
                ItemStack one = new ItemStack(smeltable, 1);
                if (removeOne(bot, smeltable)) inventory.setSmelting(one);
            }
        }

        ItemStack fuel = inventory.getFuel();
        if ((fuel == null || fuel.getType().isAir()) && inventory.getSmelting() != null
                && !inventory.getSmelting().getType().isAir()) {
            Material fuelType = count(bot, Material.COAL) > 0 ? Material.COAL
                    : count(bot, Material.CHARCOAL) > 0 ? Material.CHARCOAL
                    : countBySuffix(bot, "_PLANKS") > 16 ? firstMaterialBySuffix(bot, "_PLANKS") : null;
            if (fuelType != null && removeOne(bot, fuelType)) inventory.setFuel(new ItemStack(fuelType, 1));
        }
        furnace.update(true, false);
    }

    private Material findSmeltable(Player bot) {
        Material[] priority = {
                Material.BEEF, Material.PORKCHOP, Material.MUTTON,
                Material.CHICKEN, Material.RABBIT, Material.COD,
                Material.SALMON, Material.POTATO,
                Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
                Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
                Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE
        };
        for (Material material : priority) if (count(bot, material) > 0) return material;
        if (count(bot, Material.RAW_IRON) > 0) return Material.RAW_IRON;

        // Charcoal is a valid coal substitute. Feed ordinary logs into the
        // furnace when ores and food are not waiting to be smelted.
        for (Material material : Material.values()) {
            String name = material.name();
            if ((name.endsWith("_LOG") || name.endsWith("_WOOD"))
                    && !name.startsWith("STRIPPED_") && count(bot, material) > 0) {
                return material;
            }
        }
        return null;
    }

    private boolean removeOne(Player bot, Material material) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= 1) bot.getInventory().setItem(slot, null);
            else {
                item.setAmount(item.getAmount() - 1);
                bot.getInventory().setItem(slot, item);
            }
            return true;
        }
        return false;
    }

    private List<RecipeChoice> ingredientChoices(Recipe recipe) {
        List<RecipeChoice> ingredients = new ArrayList<>();
        if (recipe instanceof ShapelessRecipe shapeless) {
            ingredients.addAll(shapeless.getChoiceList());
            return ingredients;
        }
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> map = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (int i = 0; i < row.length(); i++) {
                    char key = row.charAt(i);
                    if (key == ' ') continue;
                    RecipeChoice choice = map.get(key);
                    if (choice == null) return null;
                    ingredients.add(choice);
                }
            }
            return ingredients;
        }
        return null;
    }

    private boolean requiresCraftingTable(Recipe recipe, int ingredientCount) {
        if (recipe instanceof ShapelessRecipe) return ingredientCount > 4;
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            int maxWidth = 0;
            for (String row : shape) maxWidth = Math.max(maxWidth, row.length());
            return shape.length > 2 || maxWidth > 2;
        }
        return true;
    }

    private boolean consumeAndCraft(Player bot, Recipe recipe, List<RecipeChoice> ingredients) {
        PlayerInventory inventory = bot.getInventory();
        int[] reserved = new int[36];
        for (RecipeChoice choice : ingredients) {
            boolean found = false;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType().isAir()
                        || reserved[slot] >= stack.getAmount()) continue;
                boolean matches;
                try { matches = choice.test(stack); }
                catch (RuntimeException ignored) { matches = false; }
                if (!matches) continue;
                reserved[slot]++;
                found = true;
                break;
            }
            if (!found) return false;
        }

        for (int slot = 0; slot < 36; slot++) {
            if (reserved[slot] <= 0) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) return false;
            int remaining = stack.getAmount() - reserved[slot];
            if (remaining <= 0) inventory.setItem(slot, null);
            else {
                stack.setAmount(remaining);
                inventory.setItem(slot, stack);
            }
        }

        ItemStack output = recipe.getResult().clone();
        Map<Integer, ItemStack> overflow = inventory.addItem(output);
        for (ItemStack leftover : overflow.values()) {
            bot.getWorld().dropItemNaturally(bot.getLocation(), leftover);
        }
        bot.updateInventory();
        plugin.skills().gain("crafting", 2);
        return true;
    }

    private int count(Player bot, Material material) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    private int countBySuffix(Player bot, String suffix) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack != null && stack.getType().name().endsWith(suffix)) total += stack.getAmount();
        }
        return total;
    }

    private boolean hasNearbyCraftingTable(Player bot) {
        return findNearbyBlock(bot, Material.CRAFTING_TABLE, 4) != null;
    }

    private boolean hasNearbyBed(Player bot, int tick) {
        if (knownBed != null && knownBed.getWorld() == bot.getWorld()
                && bot.getWorld().isChunkLoaded(knownBed.getBlockX() >> 4, knownBed.getBlockZ() >> 4)
                && knownBed.getBlock().getType().name().endsWith("_BED")) return true;
        if (!isNight(bot) || tick < nextBedScanTick) return false;
        knownBed = findNearbyBed(bot, 16);
        nextBedScanTick = tick + 200;
        return knownBed != null;
    }

    private boolean hasBedItem(Player bot) {
        return countBySuffix(bot, "_BED") > 0;
    }

    private boolean placeCraftingTable(Player bot) {
        if (hasNearbyCraftingTable(bot) || count(bot, Material.CRAFTING_TABLE) <= 0) return false;
        Block target = findPlacementSpot(bot, 2);
        if (target == null) return false;
        ItemStack item = firstItem(bot, Material.CRAFTING_TABLE);
        if (item == null) return false;
        BlockPlaceEvent event = new BlockPlaceEvent(target, target.getState(),
                target.getRelative(BlockFace.DOWN), item.clone(), bot, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) return false;
        target.setType(Material.CRAFTING_TABLE);
        consumeOne(bot, Material.CRAFTING_TABLE);
        return true;
    }

    private boolean placeBed(Player bot) {
        Material bedMaterial = firstMaterialBySuffix(bot, "_BED");
        if (bedMaterial == null) return false;
        for (BlockFace facing : new BlockFace[]{BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST}) {
            int fx = bot.getLocation().getBlockX() + facing.getModX();
            int fz = bot.getLocation().getBlockZ() + facing.getModZ();
            int hx = fx + facing.getModX();
            int hz = fz + facing.getModZ();
            int y = bot.getLocation().getBlockY();
            if (!bot.getWorld().isChunkLoaded(fx >> 4, fz >> 4)
                    || !bot.getWorld().isChunkLoaded(hx >> 4, hz >> 4)) continue;
            Block foot = bot.getWorld().getBlockAt(fx, y, fz);
            Block head = bot.getWorld().getBlockAt(hx, y, hz);
            if (!foot.isEmpty() || !head.isEmpty()
                    || !foot.getRelative(BlockFace.UP).isPassable()
                    || !head.getRelative(BlockFace.UP).isPassable()
                    || !foot.getRelative(BlockFace.DOWN).getType().isSolid()
                    || !head.getRelative(BlockFace.DOWN).getType().isSolid()) continue;
            ItemStack hand = firstItem(bot, bedMaterial);
            if (hand == null) return false;
            BlockPlaceEvent footEvent = new BlockPlaceEvent(foot, foot.getState(),
                    foot.getRelative(BlockFace.DOWN), hand.clone(), bot, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(footEvent);
            if (footEvent.isCancelled() || !footEvent.canBuild()) continue;
            BlockPlaceEvent headEvent = new BlockPlaceEvent(head, head.getState(),
                    head.getRelative(BlockFace.DOWN), hand.clone(), bot, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(headEvent);
            if (headEvent.isCancelled() || !headEvent.canBuild()) continue;

            Bed footData = (Bed) bedMaterial.createBlockData();
            footData.setFacing(facing);
            footData.setPart(Bed.Part.FOOT);
            Bed headData = (Bed) footData.clone();
            headData.setPart(Bed.Part.HEAD);
            foot.setBlockData(footData, false);
            head.setBlockData(headData, false);
            consumeOne(bot, bedMaterial);
            knownBed = foot.getLocation();
            return true;
        }
        return false;
    }

    private Block findPlacementSpot(Player bot, int radius) {
        Location origin = bot.getLocation();
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = origin.getBlockX() + dx, z = origin.getBlockZ() + dz, y = origin.getBlockY();
                    if (!bot.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block target = bot.getWorld().getBlockAt(x, y, z);
                    if (target.isEmpty() && target.getRelative(BlockFace.UP).isPassable()
                            && target.getRelative(BlockFace.DOWN).getType().isSolid()) return target;
                }
            }
        }
        return null;
    }

    private Location findNearbyBed(Player bot, int radius) {
        Location origin = bot.getLocation();
        World world = bot.getWorld();
        Location best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int yMin = Math.max(world.getMinHeight(), origin.getBlockY() - 5);
        int yMax = Math.min(world.getMaxHeight() - 2, origin.getBlockY() + 5);
        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = yMin; y <= yMax; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().name().endsWith("_BED")) continue;
                    if (block.getBlockData() instanceof Bed data && data.getPart() == Bed.Part.HEAD) continue;
                    double distance = block.getLocation().distanceSquared(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block.getLocation();
                    }
                }
            }
        }
        return best;
    }

    private Block findNearbyBlock(Player bot, Material material, int radius) {
        Location origin = bot.getLocation();
        World world = bot.getWorld();
        Block best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = origin.getBlockY() - 2; y <= origin.getBlockY() + 3; y++) {
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight()) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != material) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    if (bot.getEyeLocation().distanceSquared(center) > 4.5 * 4.5
                            || !BlockPerception.canSee(bot.getEyeLocation(), block, 4.5)) continue;
                    double distance = center.distanceSquared(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block;
                    }
                }
            }
        }
        return best;
    }

    private ItemStack firstItem(Player bot, Material material) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item != null && item.getType() == material && item.getAmount() > 0) return item;
        }
        return null;
    }

    private Material firstMaterialBySuffix(Player bot, String suffix) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item != null && item.getType().name().endsWith(suffix)) return item.getType();
        }
        return null;
    }

    private void consumeOne(Player bot, Material material) {
        ItemStack item = firstItem(bot, material);
        if (item == null) return;
        if (item.getAmount() <= 1) bot.getInventory().removeItem(item);
        else item.setAmount(item.getAmount() - 1);
    }
}
