package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded A* ground navigation. It only examines already-loaded chunks and
 * never requests chunk generation. Movement is applied through server physics.
 */
final class AgentNavigator {
    private record Node(int x, int y, int z) { }
    private record OpenNode(Node node, double g, double f) { }

    private final Player bot;
    private final AgentSkillBook skills;
    private final List<Node> path = new ArrayList<>();
    // A collision can reveal a narrow obstacle that a simple passability test misses.
    // Temporarily blacklist that next cell so A* chooses another side instead of retrying it.
    private final Map<Node, Integer> blockedNodesUntil = new HashMap<>();
    private final Map<Node, Integer> blockedNodeFailures = new HashMap<>();
    private Node plannedDestination;
    private int pathIndex;
    private int stuckTicks;
    private int retryCooldown;
    private Location previousPosition;
    private Location lastRequestedDestination;
    private Node cachedGoal;
    private int goalLookupCooldown;
    private int gaitTick;
    private int nextSprintDecisionTick;
    private int sprintUntilTick;
    private boolean sprinting;
    private int doorCheckCooldown;
    private Node jumpPrimedNode;
    private int jumpStartedTick = Integer.MIN_VALUE;
    private int lastTurnUpdateTick = Integer.MIN_VALUE;

    AgentNavigator(Player bot) {
        this(bot, null);
    }

    AgentNavigator(Player bot, AgentSkillBook skills) {
        this.bot = bot;
        this.skills = skills;
    }

    private int pathSearchBudget() {
        int level = skills == null ? 0 : skills.level("navigation");
        return 450 + Math.min(10, level) * 150;
    }

    private int pathRadius() {
        int level = skills == null ? 0 : skills.level("navigation");
        return 20 + Math.min(10, level);
    }

    boolean moveTo(Location destination) {
        return moveTo(destination, false);
    }

    /** Walk normally; sprint is used only when the caller marks the journey urgent or long. */
    boolean moveTo(Location destination, boolean allowSprint) {
        updateGait(allowSprint);
        if (retryCooldown > 0) {
            retryCooldown--;
            return true;
        }
        if (destination == null || destination.getWorld() == null
                || destination.getWorld() != bot.getWorld()) {
            failPath();
            return false;
        }
        tryOpenDoorAhead(destination);

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

        while (pathIndex < path.size() && reached(path.get(pathIndex))) {
            Node reachedNode = path.get(pathIndex++);
            blockedNodesUntil.remove(reachedNode);
            blockedNodeFailures.remove(reachedNode);
            if (reachedNode.equals(jumpPrimedNode)) jumpPrimedNode = null;
            stuckTicks = 0;
        }
        if (pathIndex >= path.size()) {
            stop();
            plannedDestination = goal;
            return false;
        }

        Node next = path.get(pathIndex);
        World currentWorld = bot.getWorld();
        pruneBlockedNodes();
        if (!currentWorld.isChunkLoaded(next.x() >> 4, next.z() >> 4) || !standable(currentWorld, next)) {
            // A path can outlive a chunk ticket. Never move toward stale chunk data.
            path.clear();
            plannedDestination = null;
            if (moveDirectlyToward(destination)) return true;
            retryCooldown = 5;
            return true;
        }
        Location current = bot.getLocation();
        if (!hasClearPlayerWidth(currentWorld, current, next)) {
            // Do not keep pushing into a wall when the node looked passable on paper.
            markBlocked(next);
            path.clear();
            pathIndex = 0;
            plannedDestination = null;
            stuckTicks = 0;
            clearMovementIntent();
            return true;
        }
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
        double turnError = turnToward(yaw);

        double vertical = bot.isOnGround() ? 0.0 : bot.getVelocity().getY();
        double alignment = turnError > 110.0 ? 0.48 : turnError > 65.0 ? 0.68 : 1.0;
        double speed = movementSpeed() * alignment;
        if (next.y() > current.getBlockY()) {
            if (bot.isOnGround() && !next.equals(jumpPrimedNode)) {
                Block feet = current.getBlock();
                if (feet.getRelative(0, 1, 0).isPassable()
                        && feet.getRelative(0, 2, 0).isPassable()) {
                    jumpPrimedNode = next;
                    jumpStartedTick = bot.getTicksLived();
                    vertical = 0.42;
                } else {
                    markBlocked(next);
                    path.clear();
                    plannedDestination = null;
                    clearMovementIntent();
                    return true;
                }
            }
            // Give the jump a few ticks to gain height before driving into the step.
            if (next.equals(jumpPrimedNode)
                    && bot.getTicksLived() - jumpStartedTick < 4) {
                speed = Math.min(speed, 0.035);
            } else if (next.equals(jumpPrimedNode)
                    && bot.isOnGround() && bot.getTicksLived() - jumpStartedTick > 5) {
                markBlocked(next);
                jumpPrimedNode = null;
                path.clear();
                plannedDestination = null;
                clearMovementIntent();
                return true;
            }
        }
        applyHorizontalVelocity(dx, dz, speed);
        if (vertical > bot.getVelocity().getY()) {
            Vector velocity = bot.getVelocity();
            bot.setVelocity(new Vector(velocity.getX(), vertical, velocity.getZ()));
        }

        Location now = bot.getLocation();
        if (previousPosition != null && now.distanceSquared(previousPosition) < 0.0025) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        previousPosition = now.clone();
        if (stuckTicks > 8) {
            if (pathIndex < path.size()) markBlocked(path.get(pathIndex));
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
        // Let the fake-player movement controller brake over several game ticks.
        // Repeated stop() calls from the idle brain must not multiply velocity down
        // to zero in a single frame.
        clearMovementIntent();
        bot.setSprinting(false);
        sprinting = false;
        gaitTick = 0;
        nextSprintDecisionTick = 0;
        sprintUntilTick = 0;
        path.clear();
        pathIndex = 0;
        plannedDestination = null;
        jumpPrimedNode = null;
        jumpStartedTick = Integer.MIN_VALUE;
        blockedNodesUntil.clear();
        blockedNodeFailures.clear();
        stuckTicks = 0;
        retryCooldown = 0;
        previousPosition = null;
        lastRequestedDestination = null;
        cachedGoal = null;
        goalLookupCooldown = 0;
        lastTurnUpdateTick = Integer.MIN_VALUE;
    }

    private void clearMovementIntent() {
        if (bot instanceof org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer
                && craftPlayer.getHandle() instanceof IgrokServerPlayer fakePlayer) {
            fakePlayer.clearMovementIntent();
            return;
        }
        Vector velocity = bot.getVelocity();
        bot.setVelocity(new Vector(velocity.getX() * 0.65,
                bot.isOnGround() ? 0.0 : velocity.getY(), velocity.getZ() * 0.65));
    }

    /**
     * Opens a closed wooden/copper door or fence gate when it blocks the requested
     * route. The normal PlayerInteractEvent is fired first so protection plugins
     * can deny it; iron doors are never forced open.
     */
    private void tryOpenDoorAhead(Location destination) {
        if (doorCheckCooldown > 0) {
            doorCheckCooldown--;
            return;
        }
        doorCheckCooldown = 4; // AgentBrain calls the navigator every two ticks.
        Location position = bot.getLocation();
        Vector desired = destination.toVector().subtract(position.toVector());
        desired.setY(0.0);
        if (desired.lengthSquared() < 0.01) return;
        desired.normalize();

        World world = bot.getWorld();
        Location eye = bot.getEyeLocation();
        int ox = position.getBlockX();
        int oy = position.getBlockY();
        int oz = position.getBlockZ();
        Block best = null;
        Openable bestData = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int x = ox - 1; x <= ox + 1; x++) {
            for (int z = oz - 1; z <= oz + 1; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = oy - 1; y <= oy + 2; y++) {
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight()) continue;
                    Block block = world.getBlockAt(x, y, z);
                    String name = block.getType().name();
                    if (name.contains("IRON") || !(name.endsWith("_DOOR")
                            || name.endsWith("_FENCE_GATE") || name.endsWith("_TRAPDOOR"))) continue;
                    if (!(block.getBlockData() instanceof Openable openable) || openable.isOpen()) continue;

                    Vector toward = block.getLocation().add(0.5, 0.5, 0.5).toVector()
                            .subtract(position.toVector());
                    Vector horizontal = toward.clone();
                    horizontal.setY(0.0);
                    double distanceSquared = toward.lengthSquared();
                    if (distanceSquared > 2.6 * 2.6 || Math.abs(toward.getY()) > 1.7
                            || horizontal.lengthSquared() < 0.01) continue;
                    horizontal.normalize();
                    if (desired.dot(horizontal) < 0.35
                            || distanceSquared >= bestDistance
                            || !BlockPerception.canSee(eye, block, 4.5)) continue;
                    best = block;
                    bestData = openable;
                    bestDistance = distanceSquared;
                }
            }
        }
        if (best == null || bestData == null) return;

        PlayerInteractEvent event = new PlayerInteractEvent(bot, Action.RIGHT_CLICK_BLOCK,
                bot.getInventory().getItemInMainHand(), best, BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.useInteractedBlock() == Event.Result.DENY) return;

        bestData.setOpen(true);
        best.setBlockData((org.bukkit.block.data.BlockData) bestData, true);
    }

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
            if (candidate == null || candidate.y() - current.getBlockY() > 1
                    || current.getBlockY() - candidate.y() > 3
                    || isTemporarilyBlocked(candidate)
                    || !hasClearPlayerWidth(world, current, candidate)) continue;
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
            double score = distance + Math.abs(candidate.y() - current.getBlockY()) * 0.8
                    + terrainPenalty(world, candidate);
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
        double turnError = turnToward((float) Math.toDegrees(Math.atan2(-dx, dz)));
        double alignment = turnError > 110.0 ? 0.48 : turnError > 65.0 ? 0.68 : 1.0;
        double speed = movementSpeed() * alignment;
        double vertical = bot.isOnGround() ? 0.0 : bot.getVelocity().getY();
        if (bot.isOnGround() && best.y() > current.getBlockY()) {
            Block feet = current.getBlock();
            if (feet.getRelative(0, 1, 0).isPassable()
                    && feet.getRelative(0, 2, 0).isPassable()) vertical = 0.42;
        }
        applyHorizontalVelocity(dx, dz, speed);
        if (vertical > bot.getVelocity().getY()) {
            Vector velocity = bot.getVelocity();
            bot.setVelocity(new Vector(velocity.getX(), vertical, velocity.getZ()));
        }
        return true;
    }

    /**
     * Turn like a player instead of snapping the body to the next path vector.
     * The fake player's tick keeps these angles after vanilla movement physics.
     */
    /**
     * Rotate the head slightly ahead of the torso. A real player can look toward
     * the destination before the body has fully turned; preserving that lag also
     * prevents the avatar from snapping around corners.
     *
     * @return remaining head-yaw error in degrees
     */
    private double turnToward(float targetYaw) {
        org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer =
                (org.bukkit.craftbukkit.entity.CraftPlayer) bot;
        net.minecraft.server.level.ServerPlayer handle = craftPlayer.getHandle();
        float currentHeadYaw = handle.getYHeadRot();
        float currentBodyYaw = handle.yBodyRot;
        float headDelta = wrapDegrees(targetYaw - currentHeadYaw);
        float bodyDelta = wrapDegrees(targetYaw - currentBodyYaw);
        int currentTick = bot.getTicksLived();
        int elapsedTicks = lastTurnUpdateTick == Integer.MIN_VALUE ? 2
                : Math.max(1, Math.min(6, currentTick - lastTurnUpdateTick));
        lastTurnUpdateTick = currentTick;
        float headStep = 9.0F * elapsedTicks;
        float bodyStep = 5.5F * elapsedTicks;
        float nextHeadYaw = currentHeadYaw + Math.max(-headStep, Math.min(headStep, headDelta));
        float nextBodyYaw = currentBodyYaw + Math.max(-bodyStep, Math.min(bodyStep, bodyDelta));
        float pitch = handle.getXRot();

        // The network-facing player yaw stays aligned with the torso, while the
        // head is allowed to lead it by a limited angle during a turn.
        handle.setYRot(nextBodyYaw);
        handle.setXRot(pitch);
        handle.setYHeadRot(nextHeadYaw);
        handle.setYBodyRot(nextBodyYaw);
        return Math.max(Math.abs(wrapDegrees(targetYaw - nextHeadYaw)),
                Math.abs(wrapDegrees(targetYaw - nextBodyYaw)));
    }

    /** Publish a movement target; the fake player smooths it every server tick. */
    private void applyHorizontalVelocity(double directionX, double directionZ, double targetSpeed) {
        if (!Double.isFinite(directionX) || !Double.isFinite(directionZ)
                || !Double.isFinite(targetSpeed) || targetSpeed <= 0.0) {
            clearMovementIntent();
            return;
        }
        if (bot instanceof org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer
                && craftPlayer.getHandle() instanceof IgrokServerPlayer fakePlayer) {
            fakePlayer.setMovementIntent(directionX, directionZ, targetSpeed);
            return;
        }

        // Fallback for non-Igrok Player implementations used by isolated tests.
        double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (length < 0.0001) return;
        Vector current = bot.getVelocity();
        double targetX = directionX / length * targetSpeed;
        double targetZ = directionZ / length * targetSpeed;
        bot.setVelocity(new Vector(targetX, current.getY(), targetZ));
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped <= -180.0F) wrapped += 360.0F;
        if (wrapped > 180.0F) wrapped -= 360.0F;
        return wrapped;
    }

    private void updateGait(boolean allowSprint) {
        gaitTick++;
        boolean canSprint = allowSprint && bot.getFoodLevel() >= 9 && bot.getHealth() >= 7.0;
        if (!canSprint) {
            sprinting = false;
            bot.setSprinting(false);
            return;
        }
        if (gaitTick >= nextSprintDecisionTick) {
            sprinting = ThreadLocalRandom.current().nextDouble() < 0.78;
            sprintUntilTick = gaitTick + ThreadLocalRandom.current().nextInt(24, 56);
            nextSprintDecisionTick = sprintUntilTick + ThreadLocalRandom.current().nextInt(12, 29);
        }
        if (gaitTick > sprintUntilTick) sprinting = false;
        bot.setSprinting(sprinting);
    }

    private double movementSpeed() {
        return sprinting ? 0.275 : 0.205;
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
        double turnError = turnToward(yaw);
        bot.setSprinting(false);
        double alignment = turnError > 110.0 ? 0.48 : turnError > 65.0 ? 0.68 : 1.0;
        double vertical = bot.isOnGround() ? 0.0 : bot.getVelocity().getY();
        if (bot.isOnGround() && safeTarget.y() > current.getBlockY()) {
            Block feet = current.getBlock();
            if (feet.getRelative(0, 1, 0).isPassable()
                    && feet.getRelative(0, 2, 0).isPassable()) vertical = 0.42;
        }
        applyHorizontalVelocity(horizontal.getX(), horizontal.getZ(), speed * alignment);
        if (vertical > bot.getVelocity().getY()) {
            Vector velocity = bot.getVelocity();
            bot.setVelocity(new Vector(velocity.getX(), vertical, velocity.getZ()));
        }
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
        int searchBudget = pathSearchBudget();
        int expanded = 0;
        int polled = 0;
        // Stale duplicate entries do not count as expansions. Cap queue polling too,
        // otherwise a crowded open set could keep the main thread busy past the A* budget.
        while (!open.isEmpty() && expanded < searchBudget && polled < searchBudget * 4) {
            OpenNode current = open.poll();
            polled++;
            if (current.g() > costs.getOrDefault(current.node(), Double.POSITIVE_INFINITY)) continue;
            if (current.node().equals(requestedGoal)) {
                found = current.node();
                break;
            }
            expanded++;

            for (int[] dir : DIRECTIONS) {
                for (int dy = 1; dy >= -3; dy--) {
                    Node candidate = new Node(current.node().x() + dir[0],
                            current.node().y() + dy, current.node().z() + dir[1]);
                    if (Math.abs(candidate.x() - start.x()) > pathRadius()
                            || Math.abs(candidate.z() - start.z()) > pathRadius()
                            || isTemporarilyBlocked(candidate)
                            || (!costs.containsKey(candidate) && !standable(world, candidate))) continue;
                    if (dir[0] != 0 && dir[1] != 0) {
                        Node sideA = new Node(current.node().x() + dir[0], current.node().y(), current.node().z());
                        Node sideB = new Node(current.node().x(), current.node().y(), current.node().z() + dir[1]);
                        if (!isStandableOrKnown(world, sideA, costs)
                                || !isStandableOrKnown(world, sideB, costs)) continue;
                    }

                    double verticalCost = dy > 0 ? 0.85 : Math.max(0, -dy) * 0.38;
                    double step = (dir[0] == 0 || dir[1] == 0 ? 1.0 : 1.414)
                            + verticalCost;
                    double nextCost = current.g() + step + terrainPenalty(world, candidate);
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

    /**
     * Checks the swept width of a player, not just the center line. Three short
     * collision rays at shoulder/torso/leg height catch walls, protruding blocks,
     * and tight corners before velocity is applied. Only already-loaded chunks
     * are queried.
     */
    private boolean hasClearPlayerWidth(World world, Location current, Node next) {
        if (next.y() > current.getBlockY()) {
            // Once this exact step has been launched, let vanilla physics finish it.
            if (next.equals(jumpPrimedNode) && !bot.isOnGround()) return true;
            return hasJumpClearance(world, current, next);
        }
        if (current == null || current.getWorld() != world
                || !world.isChunkLoaded(next.x() >> 4, next.z() >> 4)) return false;
        double dx = next.x() + 0.5 - current.getX();
        double dz = next.z() + 0.5 - current.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.08) return true;
        double sideX = -dz / horizontal * 0.19;
        double sideZ = dx / horizontal * 0.19;
        Vector ray = new Vector(dx, next.y() - current.getY(), dz);
        double length = ray.length();
        if (length < 0.08) return true;
        ray.normalize();
        // Three lanes at leg, torso and head height catch wall edges and fences.
        double[] heights = {0.32, 0.92, 1.52};
        for (int side = -1; side <= 1; side++) {
            for (double height : heights) {
                Location start = new Location(world,
                        current.getX() + sideX * side, current.getY() + height,
                        current.getZ() + sideZ * side);
                org.bukkit.util.RayTraceResult hit = world.rayTraceBlocks(
                        start, ray, length, FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) return false;
            }
        }
        return true;
    }

    /**
     * A one-block rise is a jump, not a wall. Sample the predicted jump arc at
     * four points and across the bot's width. This permits a normal step-up while
     * still rejecting low ceilings, overhangs, fences and solid collision shapes.
     */
    private boolean hasJumpClearance(World world, Location current, Node next) {
        if (current == null || current.getWorld() != world || !bot.isOnGround()
                || !world.isChunkLoaded(next.x() >> 4, next.z() >> 4)) return false;
        Block currentFeet = current.getBlock();
        if (!currentFeet.isPassable()
                || !currentFeet.getRelative(0, 1, 0).isPassable()
                || !currentFeet.getRelative(0, 2, 0).isPassable()) return false;
        double dx = next.x() + 0.5 - current.getX();
        double dz = next.z() + 0.5 - current.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.08) return true;
        double sideX = -dz / horizontal * 0.19;
        double sideZ = dx / horizontal * 0.19;
        double startY = current.getY();
        double rise = Math.max(0.0, next.y() - startY);
        // The normal jump impulse peaks a little above one block. Keep the
        // estimate conservative; vanilla physics still decides the real motion.
        double jumpHeight = 0.82;
        double[] samples = {0.22, 0.42, 0.62, 0.82, 0.96};
        double[] bodyHeights = {0.10, 0.90, 1.70};
        for (double t : samples) {
            double x = current.getX() + dx * t;
            double z = current.getZ() + dz * t;
            double feetY = startY + rise * t + jumpHeight * 4.0 * t * (1.0 - t);
            for (int side = -1; side <= 1; side++) {
                double sampleX = x + sideX * side;
                double sampleZ = z + sideZ * side;
                int blockX = (int) Math.floor(sampleX);
                int blockZ = (int) Math.floor(sampleZ);
                if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return false;
                for (double bodyHeight : bodyHeights) {
                    Block occupied = world.getBlockAt(blockX,
                            (int) Math.floor(feetY + bodyHeight), blockZ);
                    if (!occupied.isPassable() || occupied.isLiquid()
                            || isHazard(occupied.getType())) return false;
                }
            }
        }
        return true;
    }

    private void markBlocked(Node node) {
        if (node == null) return;
        int failures = Math.min(5, blockedNodeFailures.getOrDefault(node, 0) + 1);
        blockedNodeFailures.put(node, failures);
        // Repeated failures keep the bot from trying the same bad maneuver again.
        blockedNodesUntil.put(node, bot.getTicksLived() + 40 * failures);
    }

    private boolean isTemporarilyBlocked(Node node) {
        Integer until = blockedNodesUntil.get(node);
        return until != null && until > bot.getTicksLived();
    }

    private void pruneBlockedNodes() {
        int now = bot.getTicksLived();
        blockedNodesUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean isStandableOrKnown(World world, Node node, Map<Node, Double> knownCosts) {
        // A node enters the cost map only after a successful standability check.
        // Reusing that fact avoids repeated Bukkit block/world-border reads from
        // several neighboring A* nodes.
        return knownCosts.containsKey(node) || standable(world, node);
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
                        double d = square(candidate.x() + 0.5 - destination.getX())
                                + square(candidate.y() - destination.getY())
                                + square(candidate.z() + 0.5 - destination.getZ())
                                + terrainPenalty(world, candidate);
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
        // Prefer level ground; only climb or descend when the current height is blocked.
        for (int dy : new int[]{0, 1, -1}) {
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

    /** Prefer paths with support underfoot and a little room from fire, lava and ledges. */
    private double terrainPenalty(World world, Node node) {
        int unsupportedSides = 0;
        int nearbyHazards = 0;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            int x = node.x() + side[0];
            int z = node.z() + side[1];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                unsupportedSides++;
                continue;
            }
            Block sideFloor = world.getBlockAt(x, node.y() - 1, z);
            Block sideFeet = world.getBlockAt(x, node.y(), z);
            if (!sideFloor.getType().isSolid() || sideFloor.isLiquid()) unsupportedSides++;
            if (sideFeet.isLiquid() || isHazard(sideFeet.getType()) || isHazard(sideFloor.getType())) {
                nearbyHazards++;
            }
        }
        double edgePenalty = Math.max(0, unsupportedSides - 1) * 0.75;
        if (unsupportedSides >= 3) edgePenalty += 2.5;
        return edgePenalty + nearbyHazards * 1.8;
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
