package ru.soscreate.igrok;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Small, bounded weather routine. It only inspects already-loaded chunks and
 * temporarily borrows the navigator; the current goal is retained and resumes
 * when the storm ends.
 */
final class WeatherShelterRoutine {
    private final IgrokPlugin plugin;
    private Location target;
    private int nextSearchTick;

    WeatherShelterRoutine(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    boolean tick(Player bot, AgentNavigator navigator, int tick) {
        if (!plugin.getConfig().getBoolean("life.rain-shelter.enabled", true)
                || bot.getWorld().getEnvironment() != World.Environment.NORMAL
                || !bot.getWorld().hasStorm()) {
            // The navigator may still be following the shelter route. Release it
            // before returning control to the independent goal planner.
            if (target != null) navigator.stop();
            target = null;
            return false;
        }

        if (isShelter(bot.getLocation())) {
            target = bot.getLocation().clone();
            navigator.stop();
            return true;
        }

        if (target != null && (!target.getWorld().isChunkLoaded(
                target.getBlockX() >> 4, target.getBlockZ() >> 4)
                || !isShelter(target))) {
            target = null;
        }

        if (target == null && tick >= nextSearchTick) {
            target = findShelter(bot, navigator);
            nextSearchTick = tick + Math.max(40,
                    plugin.getConfig().getInt("life.rain-shelter.scan-interval-ticks", 100));
        }
        if (target == null) return false;

        if (bot.getLocation().distanceSquared(target) <= 2.25) {
            navigator.stop();
            return true;
        }
        if (!navigator.moveTo(target)) {
            target = null;
            nextSearchTick = tick + 60;
            return false;
        }
        return true;
    }

    private Location findShelter(Player bot, AgentNavigator navigator) {
        World world = bot.getWorld();
        Location origin = bot.getLocation();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        int radius = Math.max(3, Math.min(10,
                plugin.getConfig().getInt("life.rain-shelter.search-radius", 8)));
        Location best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = ox + dx, z = oz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        int y = oy + dy;
                        if (y <= world.getMinHeight() || y + 6 >= world.getMaxHeight()) continue;
                        Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                        if (!isShelter(candidate) || !navigator.canStandAt(candidate)) continue;
                        double distance = candidate.distanceSquared(origin);
                        if (distance < bestDistance) {
                            best = candidate;
                            bestDistance = distance;
                        }
                    }
                }
            }
            // Do not scan farther rings once a close safe roof has been found.
            if (best != null && bestDistance <= (ring + 1.5) * (ring + 1.5)) break;
        }
        return best;
    }

    private boolean isShelter(Location location) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        if (y <= world.getMinHeight() + 1 || y + 6 >= world.getMaxHeight()
                || !world.isChunkLoaded(x >> 4, z >> 4)
                || !world.getWorldBorder().isInside(location)) return false;

        Material floor = world.getBlockAt(x, y - 1, z).getType();
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        if (!floor.isSolid() || isHazard(floor)
                || !world.getBlockAt(x, y, z).isPassable()
                || !world.getBlockAt(x, y + 1, z).isPassable()
                || world.getBlockAt(x, y, z).isLiquid()
                || world.getBlockAt(x, y + 1, z).isLiquid()) return false;

        // A roof must block rain while leaving at least one full head block clear.
        for (int roofY = y + 2; roofY <= y + 5; roofY++) {
            Material roof = world.getBlockAt(x, roofY, z).getType();
            if (roof.isSolid() && !isHazard(roof)) return true;
            if (!world.getBlockAt(x, roofY, z).isPassable()) return false;
        }
        return false;
    }

    private boolean isHazard(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, LAVA, MAGMA_BLOCK, CACTUS, POWDER_SNOW,
                    SWEET_BERRY_BUSH, WITHER_ROSE, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }
}
