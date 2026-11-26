package ru.soscreate.igrok;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
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
            return false;
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

        if (plannedDestination == null || manhattan(plannedDestination, goal) > 1
                || path.isEmpty() || stuckTicks > 8) {
            if (!buildPath(goal)) {
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

        double vertical = bot.getVelocity().getY();
        if (bot.isOnGround()) {
            vertical = next.y() > current.getBlockY() ? 0.42 : 0.0;
            if (bot.isCollidable() && bot.getLocation().getBlock().getRelative(0, 1, 0).isPassable()
                    && bot.getLocation().getBlock().getRelative(0, 2, 0).isPassable()) {
                // Keep ordinary walking unless the next waypoint is a step up.
                if (next.y() <= current.getBlockY()) vertical = 0.0;
            }
        }
        bot.setVelocity(new Vector(dx * 0.20, vertical, dz * 0.20));

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
                Vector currentVelocity = bot.getVelocity();
                bot.setVelocity(new Vector(currentVelocity.getX(), 0.42, currentVelocity.getZ()));
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

    private void failPath() {
        stop();
        retryCooldown = 20;
    }

    boolean arrived() {
        return !path.isEmpty() && pathIndex >= path.size();
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

        while (!open.isEmpty() && expanded < 900) {
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
                    if (Math.abs(candidate.x() - start.x()) > 24
                            || Math.abs(candidate.z() - start.z()) > 24
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
                || !world.isChunkLoaded(node.x() >> 4, node.z() >> 4)) return false;
        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        Block floor = world.getBlockAt(node.x(), node.y() - 1, node.z());
        return feet.isPassable() && head.isPassable() && !feet.isLiquid()
                && floor.getType().isSolid() && !floor.isLiquid();
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

    private static double square(double x) { return x * x; }
    private static int manhattan(Node a, Node b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
}
