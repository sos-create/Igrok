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
            if (!transparentForVision(hitBlock.getType())) return false;

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

    static boolean isTransparentForVision(Material material) {
        String name = material.name();
        if (name.equals("GLASS") || name.equals("GLASS_PANE")) return true;
        if (name.endsWith("_STAINED_GLASS") || name.endsWith("_STAINED_GLASS_PANE")) return true;
        if (name.endsWith("_FENCE") && !name.equals("NETHER_BRICK_FENCE")) return true;
        return name.endsWith("_FENCE_GATE") && !name.equals("NETHER_BRICK_FENCE_GATE");
    }

    static String russianName(Material material) {
        String name = material.name();
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
            case "CRAFTING_TABLE" -> "верстак";
            case "FURNACE" -> "печь";
            case "TORCH" -> "факел";
            case "GLASS" -> "стекло";
            case "GLASS_PANE" -> "стеклянная панель";
            case "COAL_ORE", "DEEPSLATE_COAL_ORE" -> "угольная руда";
            case "IRON_ORE", "DEEPSLATE_IRON_ORE" -> "железная руда";
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE" -> "золотая руда";
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> "алмазная руда";
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

    private static boolean sameBlock(Block first, Block second) {
        return first.getWorld() == second.getWorld()
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private static boolean isInteresting(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_ORE")
                || name.equals("WATER") || name.equals("LAVA")
                || name.equals("CHEST") || name.equals("BARREL")
                || name.equals("CRAFTING_TABLE") || name.equals("FURNACE")
                || name.equals("BLAST_FURNACE") || name.equals("SMOKER")
                || name.equals("TORCH") || name.endsWith("_BED")
                || name.equals("WHEAT") || name.equals("CARROTS") || name.equals("POTATOES");
    }
}
