package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
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
    private int nextPickupTick;
    private int nextCraftTick;
    private int nextBedScanTick;
    private int nextHuntDecisionTick;
    private int nextWoodGoalTick;
    private int nextStoneGoalTick;
    private Location knownBed;

    SurvivalController(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    void tick(Player bot, int tick) {
        if (tick >= nextPickupTick) {
            pickupNearby(bot);
            nextPickupTick = tick + 10;
        }
        if (bot.getFoodLevel() <= 17 && tick % 10 == 0) eatFromInventory(bot);
        if (tick >= nextCraftTick) {
            craftUsefulItems(bot, tick);
            equipBestArmor(bot);
            nextCraftTick = tick + 100;
        }
        if (tick % 100 == 0) {
            if (!hasNearbyCraftingTable(bot)) placeCraftingTable(bot);
            if (!hasNearbyBed(bot, tick) && hasBedItem(bot)) placeBed(bot);
        }
        if (bot.isSleeping() && !isNight(bot)) {
            try { bot.wakeup(false); } catch (IllegalStateException ignored) { }
        }
    }

    AutonomyDecision chooseAutonomousGoal(Player bot, AgentPersonality personality,
                                           GoalPlanner.Goal current, int tick) {
        if (bot.getFoodLevel() <= 12 && !hasFood(bot) && tick >= nextHuntDecisionTick) {
            return new AutonomyDecision(GoalPlanner.Goal.HUNT, 0.92,
                    "Потребность в пище высокая, а запас еды закончился — найти животное и добыть пищу.", "");
        }
        if (isNight(bot) && (hasNearbyBed(bot, tick) || hasBedItem(bot))) {
            return new AutonomyDecision(GoalPlanner.Goal.SLEEP, 0.96,
                    "Наступила ночь и поблизости есть или можно поставить кровать — лечь спать.", "");
        }

        int logs = countBySuffix(bot, "_LOG") + countBySuffix(bot, "_WOOD");
        int planks = countBySuffix(bot, "_PLANKS");
        if (logs + planks < 8 && tick >= nextWoodGoalTick) {
            return new AutonomyDecision(GoalPlanner.Goal.MINE, 0.82,
                    "Запас древесины мал — добыть брёвна для досок, инструментов и убежища.", "wood");
        }

        int stone = count(bot, Material.COBBLESTONE) + count(bot, Material.STONE);
        if (stone < 8 && hasTool(bot, "PICKAXE") && tick >= nextStoneGoalTick) {
            return new AutonomyDecision(GoalPlanner.Goal.MINE, 0.74,
                    "Каменных материалов мало — пополнить запас для печи и улучшенных инструментов.", "stone");
        }
        if (current == GoalPlanner.Goal.EXPLORE) return null;
        if (personality.curiosity() > 0.38) {
            return new AutonomyDecision(GoalPlanner.Goal.EXPLORE,
                    0.55 + personality.curiosity() * 0.25,
                    "Основные запасы достаточны — исследовать неизвестную часть окрестностей.", "");
        }
        return null;
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
        if (knownBed == null || !knownBed.getBlock().getType().name().endsWith("_BED")) {
            if (hasBedItem(bot)) {
                placeBed(bot);
                knownBed = findNearbyBed(bot, 5);
            }
            if (knownBed == null) {
                if (tick < nextBedScanTick) return false;
                knownBed = findNearbyBed(bot, 24);
                nextBedScanTick = tick + 100;
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
            nextBedScanTick = tick + 100;
            return false;
        }
        navigator.moveTo(knownBed);
        return true;
    }

    void deferHunt(int tick) {
        nextHuntDecisionTick = tick + 200;
    }

    void deferMining(String mode, int tick) {
        if ("wood".equals(mode)) nextWoodGoalTick = tick + 400;
        else nextStoneGoalTick = tick + 400;
    }

    Animals findFoodAnimal(Player bot, double radius) {
        return bot.getNearbyEntities(radius, 8.0, radius).stream()
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
        int bestScore = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            String name = item.getType().name();
            boolean accepted = false;
            for (String kind : kinds) if (name.endsWith("_" + kind)) accepted = true;
            if (!accepted) continue;
            int score = tier(name) * 100;
            if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable
                    && item.getType().getMaxDurability() > 0) {
                score -= damageable.getDamage() / 5;
                if (damageable.getDamage() >= item.getType().getMaxDurability() - 1) score -= 1000;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 9) {
            int hotbarSlot = -1;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack candidate = inventory.getItem(slot);
                if (candidate == null || candidate.getType().isAir()) {
                    hotbarSlot = slot;
                    break;
                }
            }
            if (hotbarSlot < 0) return;
            ItemStack chosen = inventory.getItem(bestSlot);
            inventory.setItem(bestSlot, inventory.getItem(hotbarSlot));
            inventory.setItem(hotbarSlot, chosen);
            bestSlot = hotbarSlot;
        }
        if (bestSlot >= 0) inventory.setHeldItemSlot(bestSlot);
    }

    private int tier(String materialName) {
        if (materialName.startsWith("NETHERITE_")) return 6;
        if (materialName.startsWith("DIAMOND_")) return 5;
        if (materialName.startsWith("IRON_")) return 4;
        if (materialName.startsWith("STONE_")) return 3;
        if (materialName.startsWith("GOLDEN_")) return 2;
        if (materialName.startsWith("WOODEN_")) return 1;
        return 0;
    }

    private boolean hasTool(Player bot, String suffix) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item != null && item.getType().name().endsWith("_" + suffix)
                    && item.getType().getMaxDurability() > 0) return true;
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
        int currentScore = current == null || current.getType().isAir() ? -1 : tier(current.getType().name()) * 100;
        int bestSlot = -1;
        int bestScore = currentScore;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || !item.getType().name().endsWith(suffix)) continue;
            int score = tier(item.getType().name()) * 100;
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

    private void pickupNearby(Player bot) {
        for (Entity entity : bot.getNearbyEntities(4.0, 3.0, 4.0)) {
            if (entity instanceof Item dropped) {
                if (dropped.getPickupDelay() > 0 || dropped.isDead()) continue;
                ItemStack stack = dropped.getItemStack().clone();
                Map<Integer, ItemStack> left = bot.getInventory().addItem(stack);
                if (left.isEmpty()) {
                    dropped.remove();
                } else {
                    dropped.setItemStack(left.values().iterator().next());
                }
            } else if (entity instanceof ExperienceOrb orb && !orb.isDead()) {
                bot.giveExp(orb.getExperience());
                orb.remove();
            }
        }
    }

    private void eatFromInventory(Player bot) {
        if (bot.getFoodLevel() >= 20) return;
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            int nutrition = foodValue(item.getType());
            if (nutrition <= 0) continue;
            item.setAmount(item.getAmount() - 1);
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
            else inventory.setItem(slot, item);
            bot.setFoodLevel(Math.min(20, bot.getFoodLevel() + nutrition));
            bot.setSaturation(Math.min(20.0F, bot.getSaturation() + nutrition * 0.6F));
            return;
        }
    }

    private int foodValue(Material material) {
        String name = material.name();
        if (name.startsWith("COOKED_BEEF") || name.startsWith("COOKED_PORKCHOP")) return 8;
        if (name.equals("PUMPKIN_PIE") || name.equals("RABBIT_STEW")) return 8;
        if (name.equals("COOKED_MUTTON") || name.equals("COOKED_CHICKEN")
                || name.equals("COOKED_SALMON") || name.equals("MUSHROOM_STEW")
                || name.equals("BEETROOT_SOUP")) return 6;
        if (name.equals("BREAD") || name.equals("BAKED_POTATO") || name.equals("COOKED_COD")
                || name.equals("COOKED_RABBIT") || name.equals("GOLDEN_CARROT")) return 5;
        if (name.equals("APPLE") || name.equals("CARROT") || name.equals("POTATO")
                || name.equals("MELON_SLICE") || name.equals("SWEET_BERRIES")
                || name.equals("GLOW_BERRIES") || name.equals("COOKIE") || name.equals("BEETROOT")
                || name.equals("RAW_CHICKEN") || name.equals("RAW_MUTTON")) return 2;
        if (name.equals("RAW_BEEF") || name.equals("RAW_PORKCHOP")
                || name.equals("RAW_RABBIT")) return 3;
        if (name.equals("DRIED_KELP")) return 1;
        return 0;
    }

    private void craftUsefulItems(Player bot, int tick) {
        if (bot.getNearbyEntities(10, 6, 10).stream().anyMatch(entity -> entity instanceof Monster)) return;
        int planks = countBySuffix(bot, "_PLANKS");
        if (planks < 8 && countBySuffix(bot, "_LOG") + countBySuffix(bot, "_WOOD") > 0) {
            if (craftMatching(bot, type -> type.name().endsWith("_PLANKS"))) return;
        }
        if (count(bot, Material.STICK) < 8 && planks >= 2) {
            if (craftResult(bot, Material.STICK)) return;
        }
        if (count(bot, Material.CRAFTING_TABLE) == 0 && planks >= 4) {
            if (craftResult(bot, Material.CRAFTING_TABLE)) return;
        }
        if (!hasTool(bot, "PICKAXE") && hasNearbyCraftingTable(bot)) {
            if (craftResult(bot, Material.IRON_PICKAXE)) return;
            if (craftResult(bot, Material.STONE_PICKAXE)) return;
            if (craftResult(bot, Material.WOODEN_PICKAXE)) return;
        }
        if (!hasTool(bot, "SWORD") && hasNearbyCraftingTable(bot)) {
            if (craftResult(bot, Material.IRON_SWORD)) return;
            if (craftResult(bot, Material.STONE_SWORD)) return;
            if (craftResult(bot, Material.WOODEN_SWORD)) return;
        }
        if (bot.getFoodLevel() < 16 && count(bot, Material.BREAD) < 8
                && count(bot, Material.WHEAT) >= 3 && hasNearbyCraftingTable(bot)) {
            if (craftResult(bot, Material.BREAD)) return;
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
                && knownBed.getBlock().getType().name().endsWith("_BED")) return true;
        if (tick < nextBedScanTick) return false;
        knownBed = findNearbyBed(bot, 24);
        nextBedScanTick = tick + 100;
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
        if (event.isCancelled()) return false;
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
            if (footEvent.isCancelled()) continue;
            BlockPlaceEvent headEvent = new BlockPlaceEvent(head, head.getState(),
                    head.getRelative(BlockFace.DOWN), hand.clone(), bot, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(headEvent);
            if (headEvent.isCancelled()) continue;

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
                    double distance = block.getLocation().distanceSquared(origin);
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
