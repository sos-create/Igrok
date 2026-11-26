package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Main autonomous decision loop. Perception and planning run at a low frequency;
 * movement and ongoing mining are updated every tick.
 */
final class AgentBrain {
    private final IgrokPlugin plugin;
    private final AgentPersonality personality;
    private final AgentMemory memory;
    private final GoalPlanner planner = new GoalPlanner();
    private AgentNavigator navigator;

    private GoalPlanner.Goal goal = GoalPlanner.Goal.IDLE;
    private UUID followTarget;
    private Monster threat;
    private boolean miningRequested;
    private boolean attackRequested;
    private boolean explorationRequested;
    private String miningMode = "wood";
    private Block miningTarget;
    private int miningTicks;
    private int minedThisTask;
    private int nextAttackTick;
    private int nextSearchTick;
    private int nextExploreTick;
    private int tickCounter;
    private Location exploreTarget;
    private String state = "Наблюдаю за миром";

    AgentBrain(IgrokPlugin plugin, AgentMemory memory) {
        this.plugin = plugin;
        this.memory = memory;
        String name = plugin.getConfig().getString("bot-name", "Igrok");
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.personality = new AgentPersonality(id);
    }

    AgentPersonality personality() { return personality; }
    AgentMemory memory() { return memory; }
    String state() { return state; }
    String personalityDescription() { return personality.describe(); }

    void tick() {
        tickCounter++;
        if (!plugin.getConfig().getBoolean("brain.enabled", true) || !plugin.controller().isSpawned()) return;
        Player bot = plugin.controller().bukkitPlayer();
        if (bot == null || !bot.isOnline() || bot.isDead()) return;
        if (navigator == null) navigator = new AgentNavigator(bot);

        if (plugin.houseBuilder().isBuilding()) {
            navigator.stop();
            state = "Строю дом";
            return;
        }

        int thinkInterval = Math.max(5, plugin.getConfig().getInt("brain.think-interval-ticks", 10));
        if (tickCounter % thinkInterval == 0) chooseGoal(bot);

        switch (goal) {
            case FLEE -> flee(bot);
            case DEFEND -> defend(bot);
            case FOLLOW -> follow(bot);
            case MINE -> mine(bot);
            case EXPLORE -> explore(bot);
            case IDLE -> {
                navigator.stop();
                state = "Отдыхаю и наблюдаю";
            }
        }
    }

    private void chooseGoal(Player bot) {
        double health = bot.getHealth();
        double maxHealth = bot.getMaxHealth();
        double threatDistance = Double.POSITIVE_INFINITY;
        threat = findThreat(bot);
        if (threat != null) threatDistance = threat.getLocation().distance(bot.getLocation());

        Player followed = followTarget == null ? null : Bukkit.getPlayer(followTarget);
        if (followed == null || !followed.isOnline() || followed.getWorld() != bot.getWorld()) {
            followTarget = null;
            followed = null;
        }

        boolean explore = explorationRequested;
        if (!explore && plugin.getConfig().getBoolean("brain.exploration.enabled", true)
                && tickCounter >= nextExploreTick && personality.curiosity() > 0.48) {
            explore = true;
        }

        GoalPlanner.Goal chosen = planner.choose(new GoalPlanner.Situation(
                health, maxHealth, threatDistance, threat != null,
                followed != null, miningRequested, explore,
                personality.bravery(), personality.curiosity()));

        if (attackRequested && threat != null) chosen = GoalPlanner.Goal.DEFEND;
        if (chosen != goal) {
            navigator.stop();
            goal = chosen;
        }
        if (chosen == GoalPlanner.Goal.EXPLORE && exploreTarget == null) {
            explorationRequested = false;
            nextExploreTick = tickCounter + 20 * (18 + (int) (personality.patience() * 25));
        }
    }

    private Monster findThreat(Player bot) {
        double radius = Math.max(4.0, plugin.getConfig().getDouble("brain.combat.radius", 12.0));
        boolean aggressive = attackRequested || plugin.getConfig().getBoolean("brain.combat.aggressive", false);
        return bot.getNearbyEntities(radius, 6.0, radius).stream()
                .filter(entity -> entity instanceof Monster)
                .map(entity -> (Monster) entity)
                .filter(entity -> !entity.isDead() && entity.getWorld() == bot.getWorld())
                .filter(entity -> aggressive || entity.getTarget() == bot
                        || entity.getLocation().distanceSquared(bot.getLocation()) <= 20.25)
                .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    private void flee(Player bot) {
        if (threat == null || threat.isDead()) {
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        Vector away = bot.getLocation().toVector().subtract(threat.getLocation().toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
        away.normalize().multiply(9.0);
        Location destination = bot.getLocation().clone().add(away);
        if (destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)) {
            state = "Отступаю от угрозы";
            navigator.moveTo(destination);
        } else {
            navigator.stop();
        }
    }

    private void defend(Player bot) {
        if (threat == null || threat.isDead() || !threat.isValid()) {
            attackRequested = false;
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        Location target = threat.getLocation();
        double distance = bot.getLocation().distance(target);
        if (distance > 3.0) {
            state = "Приближаюсь к " + threat.getName();
            navigator.moveTo(target);
            return;
        }
        navigator.stop();
        face(bot, target);
        state = "Защищаюсь от " + threat.getName();
        if (tickCounter >= nextAttackTick) {
            bot.swingMainHand();
            bot.attack(threat);
            nextAttackTick = tickCounter + 12;
        }
    }

    private void follow(Player bot) {
        Player target = followTarget == null ? null : Bukkit.getPlayer(followTarget);
        if (target == null || !target.isOnline() || target.getWorld() != bot.getWorld()) {
            followTarget = null;
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        double distance = bot.getLocation().distance(target.getLocation());
        if (distance <= 3.0) {
            navigator.stop();
            state = "Рядом с " + target.getName();
        } else {
            state = "Следую за " + target.getName();
            navigator.moveTo(target.getLocation());
        }
    }

    private void mine(Player bot) {
        if (!miningRequested) {
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        if (miningTarget == null || miningTarget.getType().isAir() || tickCounter >= nextSearchTick) {
            miningTarget = findMiningTarget(bot);
            nextSearchTick = tickCounter + 20;
            miningTicks = 0;
        }
        if (miningTarget == null) {
            if (tickCounter % 100 == 0) say("Поблизости не нашёл подходящего блока. Попробую поискать в другом месте.");
            state = "Ищу ресурсы";
            if (miningMode.equals("wood")) goal = GoalPlanner.Goal.EXPLORE;
            return;
        }

        Location blockLocation = miningTarget.getLocation().add(0.5, 0.5, 0.5);
        double distance = bot.getLocation().distance(blockLocation);
        if (distance > 4.4) {
            state = "Иду к блоку " + miningTarget.getType().name().toLowerCase(Locale.ROOT);
            navigator.moveTo(miningTarget.getLocation());
            return;
        }

        navigator.stop();
        face(bot, blockLocation);
        int baseTicks = Math.max(12, plugin.getConfig().getInt("brain.mining.break-ticks", 28));
        int requiredTicks = miningMode.equals("stone") ? baseTicks * 2 : baseTicks;
        miningTicks++;
        float progress = Math.min(0.95F, miningTicks / (float) requiredTicks);
        if (tickCounter % 4 == 0) {
            for (Player viewer : bot.getWorld().getPlayers()) {
                if (viewer != bot && viewer.getLocation().distanceSquared(blockLocation) <= 144.0) {
                    viewer.sendBlockDamage(blockLocation, progress, bot);
                }
            }
            bot.swingMainHand();
        }
        state = "Добываю " + miningTarget.getType().name().toLowerCase(Locale.ROOT);

        if (miningTicks >= requiredTicks) {
            Material brokenType = miningTarget.getType();
            if (bot.breakBlock(miningTarget)) {
                memory.recordMined(brokenType);
                minedThisTask++;
                miningTarget = null;
                miningTicks = 0;
                int limit = Math.max(1, plugin.getConfig().getInt("brain.mining.blocks-per-order", 4));
                if (minedThisTask >= limit) {
                    miningRequested = false;
                    minedThisTask = 0;
                    goal = GoalPlanner.Goal.IDLE;
                    say("Закончил добычу. Теперь у меня в памяти " + memory.totalMined() + " добытых блоков.");
                }
            } else {
                miningTarget = null;
                say("Не получилось сломать этот блок. Возможно, регион защищён.");
            }
        }
    }

    private Block findMiningTarget(Player bot) {
        World world = bot.getWorld();
        Location origin = bot.getLocation();
        int radius = Math.max(5, Math.min(14, plugin.getConfig().getInt("brain.mining.search-radius", 10)));
        Block best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(world.getMinHeight() + 1, origin.getBlockY() - 5);
                     y <= Math.min(world.getMaxHeight() - 2, origin.getBlockY() + 8); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (!isWantedResource(type)) continue;
                    if (miningMode.equals("wood") && !hasNearbyLeaves(block)) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    double distance = center.distanceSquared(origin);
                    if (distance >= bestDistance || distance > (radius + 2.0) * (radius + 2.0)) continue;
                    if (!bot.hasLineOfSight(center)) continue;
                    best = block;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isWantedResource(Material type) {
        String name = type.name();
        if (miningMode.equals("wood")) {
            return (name.endsWith("_LOG") || name.endsWith("_WOOD")) && !name.startsWith("STRIPPED_");
        }
        return name.equals("STONE") || name.equals("COBBLESTONE")
                || name.endsWith("_COAL_ORE") || name.endsWith("_IRON_ORE")
                || name.endsWith("_COPPER_ORE") || name.endsWith("_GOLD_ORE")
                || name.endsWith("_REDSTONE_ORE") || name.endsWith("_LAPIS_ORE")
                || name.endsWith("_DIAMOND_ORE") || name.endsWith("_EMERALD_ORE");
    }

    private boolean hasNearbyLeaves(Block log) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block nearby = log.getRelative(dx, dy, dz);
                    if (!log.getWorld().isChunkLoaded(nearby.getX() >> 4, nearby.getZ() >> 4)) continue;
                    if (nearby.getType().name().endsWith("_LEAVES")) return true;
                }
            }
        }
        return false;
    }

    private void explore(Player bot) {
        if (exploreTarget == null || bot.getLocation().distanceSquared(exploreTarget) < 2.0) {
            if (!explorationRequested && personality.curiosity() < 0.75) {
                goal = GoalPlanner.Goal.IDLE;
                navigator.stop();
                return;
            }
            int radius = 6 + (int) (personality.curiosity() * 10);
            for (int attempt = 0; attempt < 8; attempt++) {
                int x = bot.getLocation().getBlockX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
                int z = bot.getLocation().getBlockZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
                if (!bot.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = bot.getWorld().getHighestBlockYAt(x, z) + 1;
                Location candidate = new Location(bot.getWorld(), x + 0.5, y, z + 0.5);
                if (bot.getWorld().getBlockAt(x, y, z).isPassable()
                        && bot.getWorld().getBlockAt(x, y + 1, z).isPassable()) {
                    exploreTarget = candidate;
                    break;
                }
            }
            if (exploreTarget == null) {
                goal = GoalPlanner.Goal.IDLE;
                explorationRequested = false;
                return;
            }
        }
        state = "Исследую окрестности";
        if (!navigator.moveTo(exploreTarget)) {
            exploreTarget = null;
            explorationRequested = false;
        }
    }

    void follow(Player player) {
        if (player == null) return;
        followTarget = player.getUniqueId();
        miningRequested = false;
        attackRequested = false;
        goal = GoalPlanner.Goal.FOLLOW;
        if (navigator != null) navigator.stop();
        say("Хорошо, буду держаться рядом с " + player.getName() + ".");
    }

    boolean requestMining(String mode) {
        String value = mode == null ? "wood" : mode.toLowerCase(Locale.ROOT);
        if (!value.equals("wood") && !value.equals("stone")) return false;
        miningMode = value;
        miningRequested = true;
        minedThisTask = 0;
        followTarget = null;
        attackRequested = false;
        goal = GoalPlanner.Goal.MINE;
        nextSearchTick = 0;
        if (navigator != null) navigator.stop();
        say(value.equals("wood") ? "Поищу дерево и добуду несколько брёвен." : "Поищу камень или руду для добычи.");
        return true;
    }

    boolean attackNearest() {
        attackRequested = true;
        followTarget = null;
        miningRequested = false;
        threat = plugin.controller().bukkitPlayer() == null ? null : findNearestMonster(plugin.controller().bukkitPlayer(), 20);
        if (threat == null) {
            attackRequested = false;
            say("Поблизости нет подходящих враждебных мобов.");
            return false;
        }
        goal = GoalPlanner.Goal.DEFEND;
        if (navigator != null) navigator.stop();
        say("Вижу угрозу. Иду к " + threat.getName() + ".");
        return true;
    }

    private Monster findNearestMonster(Player bot, double radius) {
        return bot.getNearbyEntities(radius, radius, radius).stream()
                .filter(entity -> entity instanceof Monster)
                .map(entity -> (Monster) entity)
                .filter(entity -> !entity.isDead())
                .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    void exploreNow() {
        explorationRequested = true;
        followTarget = null;
        miningRequested = false;
        attackRequested = false;
        exploreTarget = null;
        goal = GoalPlanner.Goal.EXPLORE;
        if (navigator != null) navigator.stop();
    }

    void stop() {
        followTarget = null;
        miningRequested = false;
        attackRequested = false;
        explorationRequested = false;
        miningTarget = null;
        exploreTarget = null;
        if (navigator != null) navigator.stop();
        goal = GoalPlanner.Goal.IDLE;
        state = "Остановился";
        say("Ладно, пока остановлюсь.");
    }

    String opinion(String message, UUID player) {
        return personality.opinion(message, memory.trust(player));
    }

    private void face(Player bot, Location target) {
        double dx = target.getX() - bot.getLocation().getX();
        double dy = target.getY() - (bot.getLocation().getY() + 1.6);
        double dz = target.getZ() - bot.getLocation().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        bot.setRotation(yaw, Math.max(-75.0F, Math.min(75.0F, pitch)));
    }

    private void say(String message) {
        Bukkit.broadcastMessage("§6Igrok§7: §f" + message);
    }
}
