package ru.soscreate.igrok;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded A* ground navigation. It only examines already-loaded chunks and
 * never requests chunk generation. Movement is applied through server physics.
 */
final class AgentNavigator {
    private record Node(int x, int y, int z) { }
    private record OpenNode(Node node, double g, double f) { }

    private final Player bot;
    private final List<Node> path = new ArrayList<>();
    private Node plannedDestination;
    private int pathIndex;
    private int stuckTicks;
    private int retryCooldown;
    private Location previousPosition;
    private Location lastRequestedDestination;
    private Node cachedGoal;
    private int goalLookupCooldown;

    AgentNavigator(Player bot) {
        this.bot = bot;
    }

    boolean moveTo(Location destination) {
        if (retryCooldown > 0) {
            retryCooldown--;
            return true;
        }
        if (destination == null || destination.getWorld() == null
                || destination.getWorld() != bot.getWorld()) {
            failPath();
            return false;
        }

        boolean destinationChanged = lastRequestedDestination == null
                || lastRequestedDestination.getWorld() != destination.getWorld()
                || lastRequestedDestination.getBlockX() != destination.getBlockX()
                || lastRequestedDestination.getBlockY() != destination.getBlockY()
                || lastRequestedDestination.getBlockZ() != destination.getBlockZ();
        if (destinationChanged || cachedGoal == null || goalLookupCooldown <= 0) {
            cachedGoal = findGoal(destination);
            lastRequestedDestination = destination.clone();
            goalLookupCooldown = 5;
        } else {
            goalLookupCooldown--;
        }
        Node goal = cachedGoal;
        if (goal == null) {
            failPath();
            return false;
        }
        if (!bot.getWorld().isChunkLoaded(goal.x() >> 4, goal.z() >> 4)) {
            path.clear();
            plannedDestination = null;
            cachedGoal = null;
            retryCooldown = 5;
            return true;
        }

        if (plannedDestination == null || manhattan(plannedDestination, goal) > 1
                || path.isEmpty() || stuckTicks > 8) {
            if (!buildPath(goal)) {
                // A* is deliberately bounded. If a complex forest or uneven terrain
                // exhausts its search budget, keep making safe local progress instead
                // of cancelling the goal and leaving the bot motionless.
                if (moveDirectlyToward(destination)) return true;
                failPath();
                return false;
            }
        }

        while (pathIndex < path.size() && reached(path.get(pathIndex))) pathIndex++;
        if (pathIndex >= path.size()) {
            stop();
            plannedDestination = goal;
            return false;
        }

        Node next = path.get(pathIndex);
        World currentWorld = bot.getWorld();
        if (!currentWorld.isChunkLoaded(next.x() >> 4, next.z() >> 4) || !standable(currentWorld, next)) {
            // A path can outlive a chunk ticket. Never move toward stale chunk data.
            path.clear();
            plannedDestination = null;
            if (moveDirectlyToward(destination)) return true;
            retryCooldown = 5;
            return true;
        }
        Location current = bot.getLocation();
        double dx = next.x() + 0.5 - current.getX();
        double dz = next.z() + 0.5 - current.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal < 0.04) {
            pathIndex++;
            return pathIndex < path.size();
        }

        dx /= horizontal;
        dz /= horizontal;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setRotation(yaw, 0.0F);
        bot.setSprinting(false);

        double vertical = bot.isOnGround() ? 0.0 : bot.getVelocity().getY();
        if (bot.isOnGround() && next.y() > current.getBlockY()) {
            Block feet = currentWorld.getBlockAt(current.getBlockX(), current.getBlockY(), current.getBlockZ());
            if (feet.getRelative(0, 1, 0).isPassable()
                    && feet.getRelative(0, 2, 0).isPassable()) vertical = 0.42;
        }
        bot.setVelocity(new Vector(dx * 0.12, vertical, dz * 0.12));

        Location now = bot.getLocation();
        if (previousPosition != null && now.distanceSquared(previousPosition) < 0.0025) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        previousPosition = now.clone();
        if (stuckTicks > 8) {
            path.clear();
            plannedDestination = null;
            retryCooldown = 5;
            if (bot.isOnGround()) {
                // Only use the stuck-recovery hop when the current column has clear
                // headroom. Never push the bot into a ceiling or a damaging block.
                Block feet = bot.getLocation().getBlock();
                Block head = feet.getRelative(0, 1, 0);
                Block aboveHead = feet.getRelative(0, 2, 0);
                if (head.isPassable() && aboveHead.isPassable()
                        && !head.isLiquid() && !aboveHead.isLiquid()
                        && !isHazard(head.getType()) && !isHazard(aboveHead.getType())) {
                    Vector currentVelocity = bot.getVelocity();
                    bot.setVelocity(new Vector(currentVelocity.getX(), 0.42, currentVelocity.getZ()));
                }
            }
        }
        return true;
    }

    void stop() {
        Vector velocity = bot.getVelocity();
        bot.setVelocity(new Vector(0.0, bot.isOnGround() ? 0.0 : velocity.getY(), 0.0));
        bot.setSprinting(false);
        path.clear();
        pathIndex = 0;
        plannedDestination = null;
        stuckTicks = 0;
        retryCooldown = 0;
        previousPosition = null;
        lastRequestedDestination = null;
        cachedGoal = null;
        goalLookupCooldown = 0;
    }

    /**
     * Safe local steering fallback used when bounded A* cannot find a route.
     * It advances one walkable block at a time, respects loaded chunks, world
     * borders, hazards and headroom, and lets vanilla entity physics do the walking.
     */
    private boolean moveDirectlyToward(Location destination) {
        if (destination == null || destination.getWorld() != bot.getWorld()) return false;
        World world = bot.getWorld();
        Location current = bot.getLocation();
        double currentDistance = square(destination.getX() - current.getX())
                + square(destination.getZ() - current.getZ());
        Node best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int[] dir : DIRECTIONS) {
            int x = current.getBlockX() + dir[0];
            int z = current.getBlockZ() + dir[1];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Node candidate = findStandable(world, x, current.getBlockY(), z);
            if (candidate == null || Math.abs(candidate.y() - current.getBlockY()) > 1) continue;
            if (dir[0] != 0 && dir[1] != 0) {
                Node sideA = findStandable(world, current.getBlockX() + dir[0],
                        current.getBlockY(), current.getBlockZ());
                Node sideB = findStandable(world, current.getBlockX(),
                        current.getBlockY(), current.getBlockZ() + dir[1]);
                if (sideA == null || sideB == null) continue;
            }
            double dx = candidate.x() + 0.5 - destination.getX();
            double dz = candidate.z() + 0.5 - destination.getZ();
            double distance = dx * dx + dz * dz;
            if (distance >= currentDistance - 0.02) continue;
            double score = distance + Math.abs(candidate.y() - current.getBlockY()) * 0.8;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) return false;
        double dx = best.x() + 0.5 - current.getX();
        double dz = best.z() + 0.5 - current.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) return false;
        dx /= length;
        dz /= length;
        bot.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0.0F);
        bot.setSprinting(false);
        double vertical = bot.isOnGround() ? 0.0 : bot.getVelocity().getY();
        if (bot.isOnGround() && best.y() > current.getBlockY()) {
            Block feet = current.getBlock();
            if (feet.getRelative(0, 1, 0).isPassable()
                    && feet.getRelative(0, 2, 0).isPassable()) vertical = 0.42;
        }
        bot.setVelocity(new Vector(dx * 0.12, vertical, dz * 0.12));
        return true;
    }

    private void failPath() {
        stop();
        retryCooldown = 20;
    }

    boolean arrived() {
        return !path.isEmpty() && pathIndex >= path.size();
    }

    boolean canStandAt(Location location) {
        if (location == null || location.getWorld() == null || location.getWorld() != bot.getWorld()) return false;
        return findStandable(location.getWorld(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ()) != null;
    }

    /** A short sidestep or retreat checked against loaded chunks and safe ground. */
    boolean moveVector(Vector direction, double speed) {
        if (direction == null || !Double.isFinite(speed) || speed <= 0.0) return false;
        Vector horizontal = direction.clone();
        horizontal.setY(0.0);
        if (horizontal.lengthSquared() < 0.0001) return false;
        horizontal.normalize();

        Location current = bot.getLocation();
        int targetX = (int) Math.floor(current.getX() + horizontal.getX() * 1.25);
        int targetZ = (int) Math.floor(current.getZ() + horizontal.getZ() * 1.25);
        Node safeTarget = findStandable(bot.getWorld(), targetX, current.getBlockY(), targetZ);
        if (safeTarget == null || Math.abs(safeTarget.y() - current.getBlockY()) > 1) return false;

        float yaw = (float) Math.toDegrees(Math.atan2(-horizontal.getX(), horizontal.getZ()));
        bot.setRotation(yaw, 0.0F);
        bot.setSprinting(false);
        double vertical = bot.isOnGround() ? 0.0 : bot.getVelocity().getY();
        if (bot.isOnGround() && safeTarget.y() > current.getBlockY()) {
            Block feet = current.getBlock();
            if (feet.getRelative(0, 1, 0).isPassable()
                    && feet.getRelative(0, 2, 0).isPassable()) vertical = 0.42;
        }
        bot.setVelocity(new Vector(horizontal.getX() * speed, vertical, horizontal.getZ() * speed));
        return true;
    }

    private boolean buildPath(Node requestedGoal) {
        World world = bot.getWorld();
        Node start = findStandable(world, bot.getLocation().getBlockX(),
                bot.getLocation().getBlockY(), bot.getLocation().getBlockZ());
        if (start == null) return false;
        if (start.equals(requestedGoal)) {
            path.clear();
            plannedDestination = requestedGoal;
            pathIndex = 0;
            return true;
        }

        PriorityQueue<OpenNode> open = new PriorityQueue<>(Comparator.comparingDouble(OpenNode::f));
        Map<Node, Double> costs = new HashMap<>();
        Map<Node, Node> parents = new HashMap<>();
        open.add(new OpenNode(start, 0.0, heuristic(start, requestedGoal)));
        costs.put(start, 0.0);
        Node found = null;
        int expanded = 0;

        while (!open.isEmpty() && expanded < 450) {
            OpenNode current = open.poll();
            if (current.g() > costs.getOrDefault(current.node(), Double.POSITIVE_INFINITY)) continue;
            if (current.node().equals(requestedGoal)) {
                found = current.node();
                break;
            }
            expanded++;

            for (int[] dir : DIRECTIONS) {
                for (int dy = 1; dy >= -1; dy--) {
                    Node candidate = new Node(current.node().x() + dir[0],
                            current.node().y() + dy, current.node().z() + dir[1]);
                    if (Math.abs(candidate.x() - start.x()) > 20
                            || Math.abs(candidate.z() - start.z()) > 20
                            || !standable(world, candidate)) continue;
                    if (dir[0] != 0 && dir[1] != 0) {
                        Node sideA = new Node(current.node().x() + dir[0], current.node().y(), current.node().z());
                        Node sideB = new Node(current.node().x(), current.node().y(), current.node().z() + dir[1]);
                        if (!standable(world, sideA) || !standable(world, sideB)) continue;
                    }

                    double step = (dir[0] == 0 || dir[1] == 0 ? 1.0 : 1.414)
                            + Math.abs(dy) * 0.65;
                    double nextCost = current.g() + step;
                    if (nextCost >= costs.getOrDefault(candidate, Double.POSITIVE_INFINITY)) continue;
                    costs.put(candidate, nextCost);
                    parents.put(candidate, current.node());
                    open.add(new OpenNode(candidate, nextCost,
                            nextCost + heuristic(candidate, requestedGoal)));
                }
            }
        }

        if (found == null) return false;
        List<Node> reverse = new ArrayList<>();
        Node cursor = found;
        while (!cursor.equals(start)) {
            reverse.add(cursor);
            cursor = parents.get(cursor);
            if (cursor == null) return false;
        }
        path.clear();
        for (int i = reverse.size() - 1; i >= 0; i--) path.add(reverse.get(i));
        pathIndex = 0;
        plannedDestination = requestedGoal;
        stuckTicks = 0;
        return true;
    }

    private Node findGoal(Location destination) {
        World world = destination.getWorld();
        int x = destination.getBlockX();
        int y = destination.getBlockY();
        int z = destination.getBlockZ();
        Node best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = 1; dy >= -1; dy--) {
                        Node candidate = findStandable(world, x + dx, y + dy, z + dz);
                        if (candidate == null) continue;
                        double d = candidate.x() * 0.0 + square(candidate.x() + 0.5 - destination.getX())
                                + square(candidate.y() - destination.getY())
                                + square(candidate.z() + 0.5 - destination.getZ());
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private Node findStandable(World world, int x, int y, int z) {
        for (int dy = 1; dy >= -1; dy--) {
            Node node = new Node(x, y + dy, z);
            if (standable(world, node)) return node;
        }
        return null;
    }

    private boolean standable(World world, Node node) {
        if (node.y() <= world.getMinHeight() || node.y() + 1 >= world.getMaxHeight()
                || !world.isChunkLoaded(node.x() >> 4, node.z() >> 4)
                || !world.getWorldBorder().isInside(
                        new Location(world, node.x() + 0.5, node.y(), node.z() + 0.5))) return false;
        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        Block floor = world.getBlockAt(node.x(), node.y() - 1, node.z());
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                && !isHazard(feet.getType()) && !isHazard(head.getType())
                && floor.getType().isSolid() && !floor.isLiquid() && !isHazard(floor.getType());
    }

    private boolean reached(Node node) {
        Location loc = bot.getLocation();
        double dx = loc.getX() - (node.x() + 0.5);
        double dz = loc.getZ() - (node.z() + 0.5);
        return dx * dx + dz * dz < 0.28 && Math.abs(loc.getY() - node.y()) < 1.1;
    }

    private static double heuristic(Node a, Node b) {
        return Math.sqrt(square(a.x() - b.x()) + square(a.z() - b.z()))
                + Math.abs(a.y() - b.y()) * 1.1;
    }

    private static boolean isHazard(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE,
                    WITHER_ROSE, SWEET_BERRY_BUSH, POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    private static double square(double x) { return x * x; }
    private static int manhattan(Node a, Node b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
}
