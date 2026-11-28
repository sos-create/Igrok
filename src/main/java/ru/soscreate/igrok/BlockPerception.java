package ru.soscreate.igrok;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/**
 * Server-side block perception: material identity, distance and actual ray visibility.
 * This is not a screenshot/texture renderer. It reads the same block types and
 * collision geometry that the server uses, and it never identifies opaque blocks
 * through walls. Ordinary glass and wooden fences are transparent sight exceptions.
 */
final class BlockPerception {
    record Observation(Material material, Location location, double distance) { }

    private BlockPerception() { }

    static boolean canSee(Location eye, Block target, double maxDistance) {
        if (eye == null || eye.getWorld() == null || target == null
                || target.getWorld() != eye.getWorld() || maxDistance <= 0.0) return false;
        World world = eye.getWorld();
        if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return false;

        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        Vector direction = center.toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 0.01) return true;
        if (distance > maxDistance) return false;
        direction.normalize();

        Location rayStart = eye.clone();
        double travelled = 0.0;
        for (int pass = 0; pass < 8 && travelled < distance; pass++) {
            double remaining = distance - travelled;
            RayTraceResult hit = world.rayTraceBlocks(
                    rayStart, direction, remaining, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) return true;

            Block hitBlock = hit.getHitBlock();
            if (sameBlock(hitBlock, target)) return true;
            if (!isTransparentForVision(hitBlock.getType())) return false;

            double advance = rayStart.toVector().distance(hit.getHitPosition()) + 0.06;
            if (!Double.isFinite(advance) || advance < 0.02) advance = 0.06;
            travelled += advance;
            rayStart = eye.clone().add(direction.clone().multiply(travelled));
        }
        return travelled >= distance - 0.05;
    }

    static List<Observation> nearbyVisible(Player player, int radius, int limit) {
        List<Observation> visible = new ArrayList<>();
        if (player == null || !player.isOnline() || radius < 1 || limit < 1) return visible;
        World world = player.getWorld();
        Location origin = player.getLocation();
        Location eye = player.getEyeLocation();
        int minX = origin.getBlockX() - radius;
        int maxX = origin.getBlockX() + radius;
        int minZ = origin.getBlockZ() - radius;
        int maxZ = origin.getBlockZ() + radius;
        int minY = Math.max(world.getMinHeight(), origin.getBlockY() - 4);
        int maxY = Math.min(world.getMaxHeight() - 2, origin.getBlockY() + 5);

        // Limit the number of expensive ray tests per chat observation.
        int inspected = 0;
        for (int x = minX; x <= maxX && inspected < 2200; x++) {
            for (int z = minZ; z <= maxZ && inspected < 2200; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY && inspected < 2200; y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (!isInteresting(type)) continue;
                    inspected++;
                    Block block = world.getBlockAt(x, y, z);
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    double distance = Math.sqrt(eye.distanceSquared(center));
                    if (distance > radius || !canSee(eye, block, radius)) continue;
                    visible.add(new Observation(type, block.getLocation(), distance));
                }
            }
        }
        visible.sort(Comparator.comparingDouble(Observation::distance));
        if (visible.size() > limit) return new ArrayList<>(visible.subList(0, limit));
        return visible;
    }

    /**
     * Compact local block census for the agent's own situational report.
     * The scan is intentionally small and skips unloaded chunks.
     */
    static String nearbyBlockCounts(Player player, int radius, int below, int above, int limit) {
        if (player == null || !player.isOnline()) return "окружающие блоки не определены";
        World world = player.getWorld();
        Location origin = player.getLocation();
        Map<Material, Integer> counts = new HashMap<>();
        int scanned = 0;
        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(world.getMinHeight(), origin.getBlockY() - below);
                     y <= Math.min(world.getMaxHeight() - 1, origin.getBlockY() + above); y++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material.isAir()) continue;
                    counts.merge(material, 1, Integer::sum);
                    scanned++;
                }
            }
        }
        if (counts.isEmpty()) return "рядом нет обнаруженных твёрдых блоков";
        String summary = counts.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .limit(Math.max(1, limit))
                .map(entry -> russianName(entry.getKey()) + " ×" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return "локальный обзор (" + scanned + " блоков): " + summary;
    }

    static boolean isTransparentForVision(Material material) {
        String name = material.name();
        if (name.equals("GLASS") || name.equals("GLASS_PANE")) return true;
        if (name.endsWith("_STAINED_GLASS") || name.endsWith("_STAINED_GLASS_PANE")) return true;
        if (name.endsWith("_FENCE") && !name.equals("NETHER_BRICK_FENCE")) return true;
        return name.endsWith("_FENCE_GATE") && !name.equals("NETHER_BRICK_FENCE_GATE");
    }

    static String russianName(Material material) {
        String name = material.name();
        String wood = woodType(name);
        if (wood != null) {
            String base = name.startsWith("STRIPPED_") ? name.substring("STRIPPED_".length()) : name;
            if (base.endsWith("_LOG")) return "бревно " + wood;
            if (base.endsWith("_STEM")) return "стебель " + wood;
            if (base.endsWith("_WOOD") || base.endsWith("_HYPHAE")) return "древесина " + wood;
            if (base.endsWith("_PLANKS")) return "доски " + wood;
            if (base.endsWith("_LEAVES")) return "листва " + wood;
            if (base.endsWith("_FENCE_GATE")) return "калитка из древесины " + wood;
            if (base.endsWith("_FENCE")) return "забор из древесины " + wood;
            if (base.endsWith("_STAIRS")) return "ступени из древесины " + wood;
            if (base.endsWith("_SLAB")) return "плита из древесины " + wood;
            if (base.endsWith("_DOOR")) return "дверь из древесины " + wood;
            if (base.endsWith("_TRAPDOOR")) return "люк из древесины " + wood;
            if (base.endsWith("_BUTTON")) return "деревянная кнопка (" + wood + ")";
            if (base.endsWith("_PRESSURE_PLATE")) return "деревянная нажимная плита (" + wood + ")";
        }
        String toolName = russianToolName(name);
        if (toolName != null) return toolName;
        if (name.endsWith("_ORE")) {
            if (name.contains("COAL")) return "угольная руда";
            if (name.contains("IRON")) return "железная руда";
            if (name.contains("GOLD")) return "золотая руда";
            if (name.contains("COPPER")) return "медная руда";
            if (name.contains("DIAMOND")) return "алмазная руда";
            if (name.contains("EMERALD")) return "изумрудная руда";
            if (name.contains("LAPIS")) return "лазуритовая руда";
            if (name.contains("REDSTONE")) return "редстоуновая руда";
        }
        return switch (name) {
            case "AIR", "CAVE_AIR", "VOID_AIR" -> "воздух";
            case "STONE" -> "камень";
            case "DEEPSLATE" -> "глубинный сланец";
            case "COBBLESTONE" -> "булыжник";
            case "DIRT" -> "земля";
            case "GRASS_BLOCK" -> "блок травы";
            case "SAND" -> "песок";
            case "GRAVEL" -> "гравий";
            case "WATER" -> "вода";
            case "LAVA" -> "лава";
            case "CHEST" -> "сундук";
            case "TRAPPED_CHEST" -> "сундук-ловушка";
            case "BARREL" -> "бочка";
            case "CRAFTING_TABLE" -> "верстак";
            case "FURNACE" -> "печь";
            case "BLAST_FURNACE" -> "плавильная печь";
            case "SMOKER" -> "коптильня";
            case "SMITHING_TABLE" -> "кузнечный стол";
            case "ENCHANTING_TABLE" -> "стол зачарований";
            case "BREWING_STAND" -> "варочная стойка";
            case "STONECUTTER" -> "камнерез";
            case "GRINDSTONE" -> "точило";
            case "COMPOSTER" -> "компостер";
            case "BEE_NEST" -> "пчелиное гнездо";
            case "BEEHIVE" -> "улей";
            case "FARMLAND" -> "грядка";
            case "WHEAT" -> "пшеница";
            case "CARROTS" -> "морковь";
            case "POTATOES" -> "картофель";
            case "BEETROOTS" -> "свёкла";
            case "NETHER_WART" -> "адский нарост";
            case "COCOA" -> "какао";
            case "PUMPKIN_STEM" -> "стебель тыквы";
            case "MELON_STEM" -> "стебель арбуза";
            case "SWEET_BERRY_BUSH" -> "куст сладких ягод";
            case "PISTON" -> "поршень";
            case "OBSERVER" -> "наблюдатель";
            case "REPEATER" -> "повторитель";
            case "REDSTONE_BLOCK" -> "блок редстоуна";
            case "TORCH" -> "факел";
            case "GLASS" -> "стекло";
            case "GLASS_PANE" -> "стеклянная панель";
            case "COAL_ORE", "DEEPSLATE_COAL_ORE" -> "угольная руда";
            case "IRON_ORE", "DEEPSLATE_IRON_ORE" -> "железная руда";
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE" -> "золотая руда";
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> "алмазная руда";
            case "BAMBOO_BLOCK" -> "блок бамбука";
            case "STICK" -> "палка";
            case "IRON_INGOT" -> "железный слиток";
            case "GOLD_INGOT" -> "золотой слиток";
            case "COPPER_INGOT" -> "медный слиток";
            case "RAW_IRON" -> "необработанное железо";
            case "RAW_GOLD" -> "необработанное золото";
            case "RAW_COPPER" -> "необработанная медь";
            case "REDSTONE" -> "редстоуновая пыль";
            case "COAL" -> "уголь";
            case "CHARCOAL" -> "древесный уголь";
            case "DIAMOND" -> "алмаз";
            case "EMERALD" -> "изумруд";
            case "WHITE_BED", "RED_BED", "BLUE_BED", "BLACK_BED", "BROWN_BED",
                    "CYAN_BED", "GRAY_BED", "GREEN_BED", "LIGHT_BLUE_BED", "LIGHT_GRAY_BED",
                    "LIME_BED", "MAGENTA_BED", "ORANGE_BED", "PINK_BED", "PURPLE_BED",
                    "YELLOW_BED" -> "кровать";
            case "OAK_LOG" -> "дубовое бревно";
            case "SPRUCE_LOG" -> "еловое бревно";
            case "BIRCH_LOG" -> "берёзовое бревно";
            case "JUNGLE_LOG" -> "бревно тропического дерева";
            case "ACACIA_LOG" -> "акациевое бревно";
            case "DARK_OAK_LOG" -> "бревно тёмного дуба";
            case "MANGROVE_LOG" -> "мангровое бревно";
            case "CHERRY_LOG" -> "вишнёвое бревно";
            default -> name.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String russianToolName(String name) {
        String[] suffixes = {"PICKAXE", "AXE", "SWORD", "SHOVEL", "HOE"};
        String[] russianSuffixes = {"кирка", "топор", "меч", "лопата", "мотыга"};
        for (int i = 0; i < suffixes.length; i++) {
            String suffix = "_" + suffixes[i];
            if (!name.endsWith(suffix)) continue;
            String tier = name.substring(0, name.length() - suffix.length());
            String russianTier = switch (tier) {
                case "WOODEN" -> "деревянная";
                case "STONE" -> "каменная";
                case "IRON" -> "железная";
                case "GOLDEN" -> "золотая";
                case "DIAMOND" -> "алмазная";
                case "NETHERITE" -> "незеритовая";
                default -> null;
            };
            if (russianTier == null) return null;
            return russianTier + " " + russianSuffixes[i];
        }
        return null;
    }

    private static String woodType(String name) {
        String normalized = name.startsWith("STRIPPED_")
                ? name.substring("STRIPPED_".length()) : name;
        if (!(normalized.endsWith("_LOG") || normalized.endsWith("_WOOD")
                || normalized.endsWith("_STEM") || normalized.endsWith("_HYPHAE")
                || normalized.endsWith("_PLANKS")
                || normalized.endsWith("_LEAVES") || normalized.endsWith("_FENCE")
                || normalized.endsWith("_FENCE_GATE") || normalized.endsWith("_STAIRS")
                || normalized.endsWith("_SLAB") || normalized.endsWith("_DOOR")
                || normalized.endsWith("_TRAPDOOR") || normalized.endsWith("_BUTTON")
                || normalized.endsWith("_PRESSURE_PLATE"))) return null;
        if (normalized.startsWith("OAK_")) return "дуба";
        if (normalized.startsWith("SPRUCE_")) return "ели";
        if (normalized.startsWith("BIRCH_")) return "берёзы";
        if (normalized.startsWith("JUNGLE_")) return "тропического дерева";
        if (normalized.startsWith("ACACIA_")) return "акации";
        if (normalized.startsWith("DARK_OAK_")) return "тёмного дуба";
        if (normalized.startsWith("MANGROVE_")) return "мангрового дерева";
        if (normalized.startsWith("CHERRY_")) return "вишни";
        if (normalized.startsWith("BAMBOO_")) return "бамбука";
        if (normalized.startsWith("CRIMSON_")) return "багрового дерева";
        if (normalized.startsWith("WARPED_")) return "искажённого дерева";
        return null;
    }

    private static boolean sameBlock(Block first, Block second) {
        return first.getWorld() == second.getWorld()
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private static boolean isInteresting(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE")
                || name.equals("BAMBOO_BLOCK") || name.endsWith("_ORE")
                || name.equals("WATER") || name.equals("LAVA")
                || name.equals("CHEST") || name.equals("TRAPPED_CHEST") || name.equals("BARREL")
                || name.equals("CRAFTING_TABLE") || name.equals("FURNACE")
                || name.equals("BLAST_FURNACE") || name.equals("SMOKER")
                || name.equals("ANVIL") || name.equals("CHIPPED_ANVIL") || name.equals("DAMAGED_ANVIL")
                || name.equals("SMITHING_TABLE") || name.equals("ENCHANTING_TABLE")
                || name.equals("BREWING_STAND") || name.equals("STONECUTTER")
                || name.equals("GRINDSTONE") || name.equals("COMPOSTER")
                || name.equals("BEE_NEST") || name.equals("BEEHIVE")
                || name.equals("FARMLAND") || name.equals("TORCH") || name.endsWith("_BED")
                || name.equals("WHEAT") || name.equals("CARROTS") || name.equals("POTATOES")
                || name.equals("BEETROOTS") || name.equals("NETHER_WART")
                || name.equals("COCOA") || name.equals("PUMPKIN_STEM") || name.equals("MELON_STEM");
    }
}
