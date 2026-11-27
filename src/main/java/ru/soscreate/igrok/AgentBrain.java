package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
    private final Map<UUID, Integer> lastGreetingTick = new HashMap<>();
    private final Map<UUID, Long> lastInteractionReplyAt = new HashMap<>();
    private final Map<UUID, Long> lastAttackReplyAt = new HashMap<>();
    private final Map<UUID, Long> lastAttackPenaltyAt = new HashMap<>();
    private AgentNavigator navigator;
    private UUID conversationTarget;
    private int conversationUntilTick;
    private final Map<UUID, Long> lastAddressAckAt = new HashMap<>();

    private GoalPlanner.Goal goal = GoalPlanner.Goal.IDLE;
    private UUID followTarget;
    private Monster threat;
    private UUID defensivePlayerId;
    private int defensiveUntilTick;
    private int nextDefensiveMoveTick;
    private Location defensiveDestination;
    private int nextCombatMessageTick;
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
    private String decisionReason = "Пока нет достаточных наблюдений.";
    private double decisionConfidence;
    private final SurvivalController survival;
    private boolean manualOrderActive;
    /** Explicit player pause: keep position while the owner transfers items. */
    private boolean pausedByPlayer;
    private org.bukkit.entity.Animals foodTarget;
    private int nextHuntAttackTick;
    private int nextBuildAttemptTick;
    private int nextIdleLookTick;
    private int nextIdleWanderTick;
    private int idlePauseUntilTick;
    private Location idleWanderTarget;
    private final List<String> learnedDangerZones = new ArrayList<>();
    private boolean learnedCombatCaution;
    private boolean learnedFoodCaution;
    private int combatDeaths;
    private int starvationDeaths;
    private int combatRetreatUntilTick;
    private int pauseAfterFailureUntilTick;
    private boolean builderWasActive;
    private String lastLesson = "Пока не было смертельных ошибок.";

    AgentBrain(IgrokPlugin plugin, AgentMemory memory) {
        this.plugin = plugin;
        this.memory = memory;
        this.survival = new SurvivalController(plugin);
        String name = plugin.getConfig().getString("bot-name", "Igrok");
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.personality = new AgentPersonality(id);
    }

    AgentPersonality personality() { return personality; }
    AgentMemory memory() { return memory; }
    String state() { return state; }
    String decisionReason() { return decisionReason; }
    double decisionConfidence() { return decisionConfidence; }
    String personalityDescription() { return personality.describe(); }


    void saveState(YamlConfiguration data) {
        data.set("brain.goal", goal.name());
        data.set("brain.follow-target", followTarget == null ? null : followTarget.toString());
        data.set("brain.mining-requested", miningRequested);
        data.set("brain.mining-mode", miningMode);
        data.set("brain.mined-this-task", minedThisTask);
        data.set("brain.attack-requested", attackRequested);
        data.set("brain.exploration-requested", explorationRequested);
        if (exploreTarget != null && exploreTarget.getWorld() != null) {
            data.set("brain.explore-world", exploreTarget.getWorld().getName());
            data.set("brain.explore-x", exploreTarget.getX());
            data.set("brain.explore-y", exploreTarget.getY());
            data.set("brain.explore-z", exploreTarget.getZ());
        }
        data.set("learning.danger-zones", new ArrayList<>(learnedDangerZones));
        data.set("learning.combat-caution", learnedCombatCaution);
        data.set("learning.food-caution", learnedFoodCaution);
        data.set("learning.combat-deaths", combatDeaths);
        data.set("learning.starvation-deaths", starvationDeaths);
        data.set("learning.last-lesson", lastLesson);
    }

    void loadState(YamlConfiguration data) {
        try { goal = GoalPlanner.Goal.valueOf(data.getString("brain.goal", "IDLE")); }
        catch (IllegalArgumentException ignored) { goal = GoalPlanner.Goal.IDLE; }
        String id = data.getString("brain.follow-target", "");
        try {
            UUID savedTarget = id.isBlank() ? null : UUID.fromString(id);
            Player onlineTarget = savedTarget == null ? null : Bukkit.getPlayer(savedTarget);
            // Following is a temporary order. Never let an offline player UUID
            // pin an autonomous survival bot in FOLLOW after a server restart.
            followTarget = onlineTarget != null && onlineTarget.isOnline() ? savedTarget : null;
        } catch (IllegalArgumentException ignored) {
            followTarget = null;
        }
        miningRequested = data.getBoolean("brain.mining-requested", false);
        miningMode = data.getString("brain.mining-mode", "wood");
        if (!java.util.Set.of("wood", "stone", "coal", "iron").contains(miningMode)) miningMode = "wood";
        minedThisTask = Math.max(0, data.getInt("brain.mined-this-task", 0));
        attackRequested = data.getBoolean("brain.attack-requested", false);
        explorationRequested = data.getBoolean("brain.exploration-requested", false);
        String worldName = data.getString("brain.explore-world", "");
        World world = worldName.isBlank() ? null : Bukkit.getWorld(worldName);
        if (world != null && data.contains("brain.explore-x")) {
            exploreTarget = new Location(world, data.getDouble("brain.explore-x"),
                    data.getDouble("brain.explore-y"), data.getDouble("brain.explore-z"));
        }
        learnedDangerZones.clear();
        long now = System.currentTimeMillis();
        for (String entry : data.getStringList("learning.danger-zones")) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4) continue;
            try {
                long learnedAt = Long.parseLong(parts[3]);
                if (now - learnedAt <= 7L * 24L * 60L * 60L * 1000L) learnedDangerZones.add(entry);
            } catch (NumberFormatException ignored) { }
        }
        while (learnedDangerZones.size() > 12) learnedDangerZones.remove(0);
        learnedCombatCaution = data.getBoolean("learning.combat-caution", false);
        learnedFoodCaution = data.getBoolean("learning.food-caution", false);
        combatDeaths = Math.max(0, data.getInt("learning.combat-deaths", 0));
        starvationDeaths = Math.max(0, data.getInt("learning.starvation-deaths", 0));
        lastLesson = data.getString("learning.last-lesson", "Пока не было смертельных ошибок.");
        survival.setLearnedFoodCaution(learnedFoodCaution);
        if (goal == GoalPlanner.Goal.MINE) miningRequested = true;
        if (goal == GoalPlanner.Goal.DEFEND) { attackRequested = true; threat = null; }
        if (goal == GoalPlanner.Goal.FOLLOW && followTarget == null) goal = GoalPlanner.Goal.IDLE;
        manualOrderActive = followTarget != null || miningRequested || attackRequested || explorationRequested;
        state = "Восстанавливаю прежний план: " + goal.name();
    }

    void tick() {
        tickCounter += 2;
        if (tickCounter < pauseAfterFailureUntilTick) return;
        try {
            tickInternal();
        } catch (RuntimeException error) {
            pauseAfterFailureUntilTick = tickCounter + 100;
            if (navigator != null) {
                try {
                    navigator.stop();
                } catch (RuntimeException stopError) {
                    plugin.reportRuntimeError("navigator-stop", stopError);
                }
            }
            state = "Временно приостановил действие после внутренней ошибки";
            decisionReason = "Остановил текущее действие для защиты мира и повторю попытку через несколько секунд.";
            plugin.reportRuntimeError("brain", error);
        }
    }

    private void tickInternal() {
        if (!plugin.getConfig().getBoolean("brain.enabled", true) || !plugin.controller().isSpawned()) return;
        Player bot = plugin.controller().bukkitPlayer();
        if (bot == null || !bot.isOnline() || bot.isDead()) return;
        if (navigator == null) navigator = new AgentNavigator(bot);
        survival.tick(bot, tickCounter);

        // A recent player attack temporarily overrides every voluntary activity.
        // This keeps an attack from behind from being overwritten by IDLE/MINE/FOLLOW.
        if (handlePlayerDefense(bot)) return;
        // An explicit stop is a persistent hold, not a one-tick navigator stop.
        // It also outranks the short approach-to-speaker behavior triggered by chat.
        if (pausedByPlayer) {
            if (navigator != null) navigator.stop();
            state = "Жду на месте";
            return;
        }
        if (handleConversationAttention(bot)) return;

        if (plugin.houseBuilder().isBuilding()) {
            // HouseBuilder controls movement with its own navigator. Stopping the
            // normal navigator every tick used to cancel every builder movement.
            if (!builderWasActive) navigator.stop();
            builderWasActive = true;
            state = "Строю дом";
            return;
        }
        if (builderWasActive) {
            builderWasActive = false;
            navigator.stop();
        }

        int thinkInterval = Math.max(10, plugin.getConfig().getInt("brain.think-interval-ticks", 20));
        if (tickCounter % thinkInterval == 0) chooseGoal(bot);

        switch (goal) {
            case FLEE -> flee(bot);
            case DEFEND -> defend(bot);
            case FOLLOW -> followTarget(bot);
            case MINE -> mine(bot);
            case HUNT -> hunt(bot);
            case SLEEP -> sleep(bot);
            case EXPLORE -> explore(bot);
            case IDLE -> idle(bot);
        }

        int socialInterval = Math.max(10, plugin.getConfig().getInt("chat.social.scan-interval-ticks", 20));
        if (tickCounter % socialInterval == 0) socialize(bot);
    }
    private void idle(Player bot) {
        if (idleWanderTarget != null) {
            if (bot.getFoodLevel() <= 10 || bot.getHealth() < bot.getMaxHealth() * 0.60) {
                idleWanderTarget = null;
                navigator.stop();
                nextIdleWanderTick = tickCounter + 100;
                state = "Отдыхаю и берегу силы";
                return;
            }
            if (bot.getLocation().distanceSquared(idleWanderTarget) <= 2.25) {
                idleWanderTarget = null;
                idlePauseUntilTick = tickCounter + ThreadLocalRandom.current().nextInt(30, 91);
                nextIdleWanderTick = tickCounter + ThreadLocalRandom.current().nextInt(240, 481);
                navigator.stop();
                state = "Остановился осмотреться";
                return;
            }
            state = "Неспешно прогуливаюсь";
            if (!moveTowardSafely(bot, idleWanderTarget, 0.12)) {
                idleWanderTarget = null;
                nextIdleWanderTick = tickCounter + 80;
            }
            return;
        }

        navigator.stop();
        if (tickCounter < idlePauseUntilTick) {
            state = "Немного отдыхаю";
            return;
        }

        Player nearby = bot.getNearbyEntities(6.0, 3.0, 6.0).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .filter(player -> player.isOnline() && !player.getUniqueId().equals(bot.getUniqueId()))
                .min(Comparator.comparingDouble(player ->
                        player.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
        if (nearby != null && tickCounter >= nextIdleLookTick) {
            face(bot, nearby.getEyeLocation());
            nextIdleLookTick = tickCounter + ThreadLocalRandom.current().nextInt(35, 81);
            state = "Наблюдаю за " + nearby.getName();
        } else if (tickCounter >= nextIdleLookTick) {
            float yaw = bot.getLocation().getYaw() + ThreadLocalRandom.current().nextInt(-75, 76);
            float pitch = ThreadLocalRandom.current().nextInt(-12, 13);
            bot.setRotation(yaw, pitch);
            nextIdleLookTick = tickCounter + ThreadLocalRandom.current().nextInt(40, 101);
        }

        // Do not rely on a 0.25% random roll every AI pulse: that could leave
        // Igrok stationary for several minutes. Take a short, safe walk every
        // 12-24 seconds while otherwise idle.
        if ((bot.getFoodLevel() <= 10 || bot.getHealth() < bot.getMaxHealth() * 0.60)
                && tickCounter >= nextIdleWanderTick) {
            nextIdleWanderTick = tickCounter + 100;
            state = "Не трачу силы на прогулку, пока не восстановлюсь";
        }
        if (plugin.getConfig().getBoolean("brain.idle-wander.enabled", true)
                && bot.getFoodLevel() > 10 && bot.getHealth() >= bot.getMaxHealth() * 0.60
                && tickCounter >= nextIdleWanderTick) {
            World world = bot.getWorld();
            for (int attempt = 0; attempt < 8 && idleWanderTarget == null; attempt++) {
                int dx = ThreadLocalRandom.current().nextInt(-8, 9);
                int dz = ThreadLocalRandom.current().nextInt(-8, 9);
                if (dx * dx + dz * dz < 9) continue;

                int x = bot.getLocation().getBlockX() + dx;
                int z = bot.getLocation().getBlockZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                int y = world.getHighestBlockYAt(x, z) + 1;
                Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                if (navigator.canStandAt(candidate) && !isLearnedDanger(candidate)) {
                    idleWanderTarget = candidate;
                    state = "Решил немного прогуляться";
                }
            }
            if (idleWanderTarget == null) {
                nextIdleWanderTick = tickCounter + 80;
            }
        }
        if (idleWanderTarget == null) state = "Отдыхаю и наблюдаю";
    }


    private void socialize(Player bot) {
        if (!plugin.getConfig().getBoolean("chat.social.enabled", true)) return;
        if (goal != GoalPlanner.Goal.IDLE) return;
        double radius = Math.max(2.0, plugin.getConfig().getDouble("chat.social.radius", 7.0));
        Player nearest = bot.getNearbyEntities(radius, 3.0, radius).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .filter(player -> !player.getUniqueId().equals(bot.getUniqueId()))
                .filter(Player::isOnline)
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
        if (nearest == null) return;

        face(bot, nearest.getEyeLocation());
        memory.rememberPlayerName(nearest.getUniqueId(), nearest.getName());
        long greetCooldown = Math.max(30L, plugin.getConfig().getLong("chat.social.greeting-cooldown-seconds", 300)) * 1000L;
        if (memory.shouldGreet(nearest.getUniqueId(), greetCooldown)
                && tickCounter - lastGreetingTick.getOrDefault(nearest.getUniqueId(), Integer.MIN_VALUE / 2) > 100) {
            String greeting = "Привет, " + nearest.getName() + "! Рад тебя видеть. Как у тебя дела?";
            bot.swingMainHand();
            memory.markGreeted(nearest.getUniqueId(), greeting);
            memory.observeAction(nearest.getUniqueId(), "social");
            plugin.cognition().observeBehavior("social");
            if (plugin.dialogue() != null) plugin.dialogue().recordBotReply(nearest.getUniqueId(), greeting);
            lastGreetingTick.put(nearest.getUniqueId(), tickCounter);
            state = "Общаюсь с " + nearest.getName();
            say(greeting);
        } else {
            state = "Рядом с игроком " + nearest.getName();
        }
    }

    void onPlayerAddressed(Player player) {
        if (player == null || !player.isOnline()) return;
        Player bot = plugin.controller().bukkitPlayer();
        if (bot == null || !bot.isOnline() || player.getWorld() != bot.getWorld()) return;

        UUID playerId = player.getUniqueId();
        memory.rememberPlayerName(playerId, player.getName());
        double distanceSquared = player.getLocation().distanceSquared(bot.getLocation());
        face(bot, player.getEyeLocation());

        if (distanceSquared > 20.25 && distanceSquared <= 24.0 * 24.0) {
            if (pausedByPlayer) return;
            if (navigator == null) navigator = new AgentNavigator(bot);
            if (plugin.houseBuilder().isBuilding()) plugin.houseBuilder().cancel(false);
            conversationTarget = playerId;
            conversationUntilTick = tickCounter + 200; // 10 seconds of approach
            navigator.stop();
        }

        long now = System.currentTimeMillis();
        if (now - lastAddressAckAt.getOrDefault(playerId, 0L) >= 5_000L) {
            lastAddressAckAt.put(playerId, now);
            if (lastAddressAckAt.size() > 512) {
                lastAddressAckAt.entrySet().removeIf(entry -> now - entry.getValue() > 300_000L);
            }
            String acknowledgement = distanceSquared > 20.25
                    ? "Слышу тебя, " + player.getName() + ". Сейчас подойду."
                    : "Слушаю тебя, " + player.getName() + ".";
            memory.recordConversation(playerId, acknowledgement, true);
            say(acknowledgement);
        }
    }

    private boolean handleConversationAttention(Player bot) {
        if (conversationTarget == null) return false;
        Player speaker = Bukkit.getPlayer(conversationTarget);
        if (speaker == null || !speaker.isOnline() || speaker.getWorld() != bot.getWorld()
                || tickCounter > conversationUntilTick) {
            conversationTarget = null;
            conversationUntilTick = 0;
            if (navigator != null) navigator.stop();
            return false;
        }

        double distanceSquared = speaker.getLocation().distanceSquared(bot.getLocation());
        if (distanceSquared <= 20.25) {
            navigator.stop();
            face(bot, speaker.getEyeLocation());
            conversationTarget = null;
            conversationUntilTick = 0;
            state = "Остановился поговорить с " + speaker.getName();
            return true;
        }

        if (plugin.houseBuilder().isBuilding()) plugin.houseBuilder().cancel(false);
        if (navigator == null) navigator = new AgentNavigator(bot);
        boolean moving = navigator.moveTo(speaker.getLocation());
        Vector horizontalVelocity = bot.getVelocity().clone().setY(0.0);
        if (!moving || horizontalVelocity.lengthSquared() < 0.0004) {
            Vector toward = speaker.getLocation().toVector().subtract(bot.getLocation().toVector());
            toward.setY(0.0);
            tryCombatMove(toward, 0.12);
        }
        // Movement sets yaw toward the path. Restore eye contact after movement.
        face(bot, speaker.getEyeLocation());
        state = "Подхожу поговорить с " + speaker.getName();
        decisionReason = "Игрок обратился ко мне — прекращаю бездействие и подхожу на расстояние разговора.";
        decisionConfidence = 1.0;
        return true;
    }

    private boolean moveTowardSafely(Player bot, Location destination, double speed) {
        return moveTowardSafely(bot, destination, speed, false);
    }

    private boolean moveTowardSafely(Player bot, Location destination, double speed, boolean allowSprint) {
        if (destination == null || destination.getWorld() != bot.getWorld()) return false;
        boolean moving = navigator.moveTo(destination, allowSprint);
        Vector velocity = bot.getVelocity().clone();
        velocity.setY(0.0);
        if (!moving || velocity.lengthSquared() < 0.0004) {
            Vector direction = destination.toVector().subtract(bot.getLocation().toVector());
            direction.setY(0.0);
            if (tryCombatMove(direction, speed)) return true;
        }
        return moving;
    }

    private boolean performVanillaMeleeAttack(Player bot, LivingEntity target) {
        if (bot == null || target == null || target.isDead() || !target.isValid()
                || target.getWorld() != bot.getWorld()
                || bot.getLocation().distanceSquared(target.getLocation()) > 9.0
                || !bot.hasLineOfSight(target)) return false;
        if (bot.getAttackCooldown() < 0.95F) return false;

        bot.swingMainHand();
        bot.attack(target);
        return true;
    }

    void onPlayerMessage(Player player, String message) {
        if (player == null || message == null) return;
        memory.rememberPlayerName(player.getUniqueId(), player.getName());
        memory.rememberMessage(player.getUniqueId(), message);
        memory.recordConversation(player.getUniqueId(), message, false);
        Player bot = plugin.controller().bukkitPlayer();
        if (bot != null && player.getWorld() == bot.getWorld()
                && player.getLocation().distanceSquared(bot.getLocation()) <= 24.0 * 24.0) {
            face(bot, player.getEyeLocation());
        }
    }

    void rememberBotMessage(UUID player, String message) {
        memory.recordConversation(player, message, true);
        if (plugin.dialogue() != null) plugin.dialogue().recordBotReply(player, message);
    }

    void onPlayerInteraction(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - lastInteractionReplyAt.getOrDefault(playerId, 0L) < 2_500L) return;
        lastInteractionReplyAt.put(playerId, now);
        trimInteractionCooldowns(now);

        memory.rememberPlayerName(playerId, player.getName());
        memory.recordConversation(playerId, "[подошёл и взаимодействовал со мной]", false);
        memory.observeAction(playerId, "social");
        plugin.cognition().observeBehavior("social");

        Player bot = plugin.controller().bukkitPlayer();
        if (bot != null) {
            face(bot, player.getEyeLocation());
            bot.swingMainHand();
        }
        String reply = memory.trust(playerId) >= 3
                ? "Привет, " + player.getName() + "! Рад, что заглянул."
                : "Привет, " + player.getName() + ". Что хотел?";
        rememberBotMessage(playerId, reply);
        say(reply);
    }
    void onPlayerAttack(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        Player bot = plugin.controller().bukkitPlayer();
        if (bot != null && bot.isOnline() && player.getWorld() == bot.getWorld()) {
            face(bot, player.getEyeLocation());
            if (player.getLocation().distanceSquared(bot.getLocation()) <= 256.0) {
                if (navigator == null) navigator = new AgentNavigator(bot);
                defensivePlayerId = playerId;
                defensiveUntilTick = tickCounter + 40;
                defensiveDestination = findRetreatDestination(bot, player);
                nextDefensiveMoveTick = tickCounter + 1;
            }
        }

        // Turning and movement happen on every hit; chat and reputation are throttled.
        if (now - lastAttackPenaltyAt.getOrDefault(playerId, 0L) >= 5_000L) {
            memory.adjustReputation(playerId, -1);
            lastAttackPenaltyAt.put(playerId, now);
        }
        if (now - lastAttackReplyAt.getOrDefault(playerId, 0L) < 2_500L) return;
        lastAttackReplyAt.put(playerId, now);
        trimInteractionCooldowns(now);

        memory.rememberPlayerName(playerId, player.getName());
        memory.recordConversation(playerId, "[ударил меня]", false);
        memory.observeAction(playerId, "combat");
        plugin.cognition().observeBehavior("combat");

        String reply = memory.trust(playerId) <= -3
                ? "Не надо меня бить, " + player.getName() + ". Я отхожу и больше тебе не доверяю."
                : "Эй, " + player.getName() + "! Не бей меня. Я пытаюсь отойти!";
        rememberBotMessage(playerId, reply);
        say(reply);
    }

    void onMonsterAttack(Monster attacker) {
        if (attacker == null || attacker.isDead() || !attacker.isValid()) return;
        Player bot = plugin.controller().bukkitPlayer();
        if (bot == null || !bot.isOnline() || attacker.getWorld() != bot.getWorld()) return;
        if (navigator == null) navigator = new AgentNavigator(bot);

        threat = attacker;
        defensivePlayerId = null;
        defensiveDestination = null;
        attackRequested = false;
        manualOrderActive = false;
        nextAttackTick = tickCounter;
        goal = "CREEPER".equals(attacker.getType().name())
                ? GoalPlanner.Goal.FLEE : GoalPlanner.Goal.DEFEND;
        face(bot, attacker.getEyeLocation());
        state = "Отвечаю на атаку " + attacker.getName();

        if (tickCounter >= nextCombatMessageTick) {
            say("На меня напал " + attacker.getName() + "! Я защищаюсь.");
            nextCombatMessageTick = tickCounter + 100;
        }
    }

    private boolean handlePlayerDefense(Player bot) {
        if (defensivePlayerId == null) return false;
        Player attacker = Bukkit.getPlayer(defensivePlayerId);
        if (attacker == null || !attacker.isOnline() || attacker.getWorld() != bot.getWorld()
                || tickCounter > defensiveUntilTick) {
            defensivePlayerId = null;
            defensiveDestination = null;
            defensiveUntilTick = 0;
            if (navigator != null) navigator.stop();
            return false;
        }

        if (plugin.houseBuilder().isBuilding()) plugin.houseBuilder().cancel(false);
        if (navigator == null) navigator = new AgentNavigator(bot);

        if (defensiveDestination == null && tickCounter >= nextDefensiveMoveTick) {
            defensiveDestination = findRetreatDestination(bot, attacker);
            nextDefensiveMoveTick = tickCounter + 8;
        }

        if (defensiveDestination != null) {
            boolean canSprint = bot.getFoodLevel() >= 9 && bot.getHealth() >= 7.0;
            boolean travelling = navigator.moveTo(defensiveDestination, canSprint);
            if (!travelling || bot.getLocation().distanceSquared(defensiveDestination) <= 1.0) {
                defensiveDestination = null;
            }
        } else if (tickCounter >= nextDefensiveMoveTick) {
            Vector away = bot.getLocation().toVector().subtract(attacker.getLocation().toVector());
            away.setY(0.0);
            if (away.lengthSquared() < 0.0001) {
                away = bot.getLocation().getDirection().multiply(-1.0);
                away.setY(0.0);
            }
            if (!tryCombatMove(away, 0.17)) {
                defensiveDestination = findRetreatDestination(bot, attacker);
            }
            nextDefensiveMoveTick = tickCounter + 8;
        }

        face(bot, attacker.getEyeLocation());
        state = "Отступаю от атакующего игрока " + attacker.getName();
        decisionReason = "Получил удар от игрока — повернулся к нему и ищу безопасное направление для отхода.";
        decisionConfidence = 1.0;
        return true;
    }

    private Location findRetreatDestination(Player bot, LivingEntity attacker) {
        if (bot == null || attacker == null || bot.getWorld() != attacker.getWorld()) return null;
        if (navigator == null) navigator = new AgentNavigator(bot);
        Vector away = bot.getLocation().toVector().subtract(attacker.getLocation().toVector());
        away.setY(0.0);
        if (away.lengthSquared() < 0.0001) away = new Vector(1.0, 0.0, 0.0);
        away.normalize();

        Location best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double currentThreatDistance = bot.getLocation().distanceSquared(attacker.getLocation());
        for (int direction = 0; direction < 8; direction++) {
            Vector offset = away.clone().rotateAroundY(direction * Math.PI / 4.0);
            for (double distance : new double[]{4.0, 6.0, 8.0, 10.0}) {
                double x = Math.floor(bot.getLocation().getX() + offset.getX() * distance) + 0.5;
                double z = Math.floor(bot.getLocation().getZ() + offset.getZ() * distance) + 0.5;
                Location candidate = new Location(bot.getWorld(), x, bot.getLocation().getY(), z);
                if (!navigator.canStandAt(candidate) || isLearnedDanger(candidate)) continue;
                double distanceGain = candidate.distanceSquared(attacker.getLocation()) - currentThreatDistance;
                if (distanceGain < 4.0) continue;
                double travelCost = candidate.distanceSquared(bot.getLocation());
                double score = distanceGain - travelCost * 0.07;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean tryCombatMove(Vector preferred, double speed) {
        if (preferred == null) return false;
        Vector direction = preferred.clone();
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.0001) return false;
        if (navigator.moveVector(direction, speed)) return true;
        Vector side = new Vector(-direction.getZ(), 0.0, direction.getX());
        if (navigator.moveVector(side, speed)) return true;
        return navigator.moveVector(side.multiply(-1.0), speed);
    }


    private void trimInteractionCooldowns(long now) {
        if (lastInteractionReplyAt.size() > 512) {
            lastInteractionReplyAt.entrySet().removeIf(entry -> now - entry.getValue() > 300_000L);
        }
        if (lastAttackReplyAt.size() > 512) {
            lastAttackReplyAt.entrySet().removeIf(entry -> now - entry.getValue() > 300_000L);
        }
        if (lastAttackPenaltyAt.size() > 512) {
            lastAttackPenaltyAt.entrySet().removeIf(entry -> now - entry.getValue() > 300_000L);
        }
    }

    /** The player may suggest an action, but the bot makes the final decision. */
    boolean considerRequest(UUID playerId, String action, boolean directOrder) {
        if (playerId == null || action == null) return false;
        String normalized = action.toLowerCase(Locale.ROOT);
        // Permanent safety rules cannot be overridden by trust, permissions or personality.
        if (normalized.contains("tnt") || normalized.contains("динамит")
                || normalized.contains("подж") || normalized.contains("взорв")) return false;
        if ("stop".equals(normalized)) return true;
        // A player must always be able to release an explicit pause by asking Igrok
        // to come over; random personality refusal must not make the pause permanent.
        if (pausedByPlayer && "follow".equals(normalized)) return true;
        if (plugin.getConfig().getBoolean("autonomy.refuse-all-player-orders", false)) return false;

        int trust = memory.trust(playerId);
        double willingness = 0.42 + personality.sociability() * 0.18
                + personality.patience() * 0.12 + Math.max(-5, Math.min(5, trust)) * 0.055;
        // A request is a proposal; a bare imperative is more intrusive and receives
        // a lower acceptance chance. Igrok still evaluates risk, trust and its own goal.
        if (directOrder) willingness -= 0.22;
        if (goal != GoalPlanner.Goal.IDLE && !normalized.contains("останов")) willingness -= 0.20;
        if (normalized.contains("атак") || normalized.contains("напад")) willingness -= 0.16;
        if (normalized.contains("след") || normalized.contains("иди")) willingness -= 0.04;
        willingness = Math.max(0.05, Math.min(0.90, willingness));
        return ThreadLocalRandom.current().nextDouble() < willingness;
    }

    String refusalReason(UUID playerId, boolean directOrder) {
        if (directOrder) return "Я понял, что ты мне приказываешь, но я не обязан подчиняться автоматически. У меня есть свои цели и причины отказаться.";
        int trust = memory.trust(playerId);
        if (goal != GoalPlanner.Goal.IDLE) return "Нет, я уже занят своим делом и не хочу бросать его.";
        if (trust < 0) return "Нет. Пока я тебе недостаточно доверяю.";
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> "Нет, сейчас я не хочу этим заниматься.";
            case 1 -> "Я подумал и решил отказаться. У меня есть свои планы.";
            case 2 -> "Не сегодня. Я сам решаю, чем мне заниматься.";
            default -> "Я понимаю просьбу, но не обязан соглашаться. В этот раз — нет.";
        };
    }

    void memoryStats(Player viewer) {
        if (viewer == null) return;
        viewer.sendMessage("§6Память: §f" + memory.rememberedPlayers() + " игроков, "
                + memory.rememberedMessages() + " сохранённых реплик, " + memory.totalMined()
                + " добытых блоков, файл " + memory.storedBytes() + " байт.");
    }

    void learnFromDeath(Location deathLocation, String damageCause, String killerType) {
        String cause = damageCause == null ? "UNKNOWN" : damageCause.toUpperCase(Locale.ROOT);
        String killer = killerType == null ? "" : killerType.toUpperCase(Locale.ROOT);
        boolean locationWasDangerous = cause.equals("FALL") || cause.equals("LAVA")
                || cause.equals("FIRE") || cause.equals("FIRE_TICK") || cause.equals("HOT_FLOOR")
                || cause.equals("VOID") || cause.equals("DROWNING") || cause.equals("SUFFOCATION")
                || cause.startsWith("ENTITY_") || cause.equals("PROJECTILE")
                || cause.equals("MAGIC") || cause.equals("POISON") || cause.equals("WITHER");
        if (deathLocation != null && deathLocation.getWorld() != null && locationWasDangerous) {
            String zone = deathLocation.getWorld().getName() + "|" + deathLocation.getBlockX()
                    + "|" + deathLocation.getBlockZ() + "|" + System.currentTimeMillis();
            learnedDangerZones.add(zone);
            while (learnedDangerZones.size() > 12) learnedDangerZones.remove(0);
            lastLesson = switch (cause) {
                case "FALL" -> "Погиб от падения: избегать опасных высот и маршрутов возле места гибели.";
                case "LAVA", "FIRE", "FIRE_TICK", "HOT_FLOOR" -> "Погиб от огня или лавы: обходить опасный участок и не идти напрямик.";
                case "VOID" -> "Погиб в пустоте: не приближаться к краю и опасным обрывам.";
                case "DROWNING" -> "Погиб под водой: избегать долгого пребывания под водой.";
                default -> "Погиб из-за опасного окружения: обходить место гибели.";
            };
        }
        if (cause.startsWith("ENTITY_") || cause.equals("PROJECTILE") || cause.equals("MAGIC")
                || cause.equals("POISON") || cause.equals("WITHER") || !killer.isBlank()) {
            learnedCombatCaution = true;
            learnedFoodCaution = true;
            combatDeaths++;
            survival.setLearnedFoodCaution(true);
            lastLesson = "Погиб в бою" + (killer.isBlank() ? "" : " от " + killer.toLowerCase(Locale.ROOT))
                    + ": раньше восстанавливать здоровье, держать дистанцию и отступать при опасности.";
        } else if (cause.equals("STARVATION")) {
            learnedFoodCaution = true;
            starvationDeaths++;
            survival.setLearnedFoodCaution(true);
            lastLesson = "Погиб от голода: еда и её запас теперь имеют более высокий приоритет.";
        }
        plugin.getLogger().info("Igrok запомнил ошибку: " + lastLesson);
    }

    private boolean isLearnedDanger(Location candidate) {
        if (candidate == null || candidate.getWorld() == null) return false;
        long now = System.currentTimeMillis();
        for (String entry : learnedDangerZones) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4 || !parts[0].equals(candidate.getWorld().getName())) continue;
            try {
                long learnedAt = Long.parseLong(parts[3]);
                if (now - learnedAt > 7L * 24L * 60L * 60L * 1000L) continue;
                double dx = candidate.getBlockX() - Integer.parseInt(parts[1]);
                double dz = candidate.getBlockZ() - Integer.parseInt(parts[2]);
                if (dx * dx + dz * dz <= 28.0 * 28.0) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private void chooseGoal(Player bot) {
        double health = bot.getHealth();
        double maxHealth = bot.getMaxHealth();
        double threatDistance = Double.POSITIVE_INFINITY;
        threat = findThreat(bot);
        if (threat != null) threatDistance = threat.getLocation().distance(bot.getLocation());

        Player followed = followTarget == null ? null : Bukkit.getPlayer(followTarget);
        if (followTarget != null && (followed == null || !followed.isOnline()
                || followed.getWorld() != bot.getWorld())) {
            followTarget = null;
            followed = null;
            if (!miningRequested && !attackRequested && !explorationRequested) {
                manualOrderActive = false;
            }
        }

        // Keep an exploration route active until its destination is reached.
        // Previously the request flag was cleared as soon as a destination was
        // selected, so the next planning pulse cancelled the trip halfway.
        boolean explore = explorationRequested || exploreTarget != null;
        if (!explore && plugin.getConfig().getBoolean("brain.exploration.enabled", true)
                && tickCounter >= nextExploreTick && personality.curiosity() > 0.48) {
            explore = true;
        }

        GoalPlanner.Decision decision = planner.evaluate(new GoalPlanner.Situation(
                health, maxHealth, threatDistance, threat != null,
                followed != null, miningRequested, explore, attackRequested,
                personality.bravery(), personality.curiosity(), goal));
        GoalPlanner.Goal chosen = decision.goal();
        decisionReason = decision.reason();
        decisionConfidence = decision.confidence();

        // Creepers are handled as a spacing threat: do not close into melee range.
        if (threat != null && threat.getType().name().equals("CREEPER")
                && threat.getLocation().distanceSquared(bot.getLocation()) <= 64.0) {
            chosen = GoalPlanner.Goal.FLEE;
            decisionReason = "Обнаружен крипер на опасном расстоянии — отойти, не вступая в ближний бой.";
            decisionConfidence = 0.98;
        }
        if (threat != null && learnedCombatCaution
                && bot.getHealth() <= bot.getMaxHealth() * 0.68) {
            combatRetreatUntilTick = Math.max(combatRetreatUntilTick, tickCounter + 100);
        }
        if (threat != null && learnedCombatCaution && tickCounter < combatRetreatUntilTick) {
            chosen = GoalPlanner.Goal.FLEE;
            decisionReason = "Учитываю прошлую гибель в бою: отступаю, пока не восстановлюсь.";
            decisionConfidence = 0.97;
        }

        // Autonomous survival goals are considered only when no player directive is active.
        // Immediate combat and fleeing from a critical threat always retain priority.
        if (!manualOrderActive && chosen != GoalPlanner.Goal.FLEE
                && chosen != GoalPlanner.Goal.DEFEND) {
            SurvivalController.AutonomyDecision autonomous =
                    survival.chooseAutonomousGoal(bot, personality, goal, tickCounter);
            if (autonomous != null) {
                chosen = autonomous.goal();
                decisionReason = autonomous.reason();
                decisionConfidence = autonomous.confidence();
                if (chosen == GoalPlanner.Goal.MINE && !miningRequested) {
                    miningMode = autonomous.miningMode();
                    miningRequested = true;
                    minedThisTask = 0;
                }
            }
        }

        // A critical survival rule is stronger than follow/attack requests.
        if (chosen != GoalPlanner.Goal.FLEE && followed != null) {
            chosen = GoalPlanner.Goal.FOLLOW;
            decisionReason = "Есть активная цель сопровождения; угрозы сейчас не требуют отступления.";
        }
        if (chosen != GoalPlanner.Goal.FLEE && attackRequested && threat != null) {
            chosen = GoalPlanner.Goal.DEFEND;
            decisionReason = "Запрошена защита, а противник обнаружен; риск проверен правилом безопасности.";
        }

        // Once resources are sufficient, Igrok spends his own inventory on one permanent shelter.
        if (!manualOrderActive && chosen != GoalPlanner.Goal.FLEE
                && chosen != GoalPlanner.Goal.DEFEND && !plugin.houseBuilder().isBuilding()
                && !plugin.houseBuilder().hasCompleted() && tickCounter >= nextBuildAttemptTick) {
            nextBuildAttemptTick = tickCounter + 200;
            if (plugin.houseBuilder().canBuild(bot)
                    && plugin.houseBuilder().start(bot.getLocation(), bot)) {
                navigator.stop();
                goal = GoalPlanner.Goal.IDLE;
                state = "Строю собственное убежище";
                decisionReason = "Собрал достаточно досок и камня; начал строить постоянное убежище за собственные ресурсы.";
                decisionConfidence = 0.96;
                return;
            }
        }

        if (chosen != goal) {
            navigator.stop();
            goal = chosen;
        }
        if (chosen == GoalPlanner.Goal.EXPLORE && exploreTarget == null && !explorationRequested) {
            explorationRequested = true;
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
                        || (entity.getType().name().equals("CREEPER")
                            ? entity.getLocation().distanceSquared(bot.getLocation()) <= 64.0
                            : entity.getLocation().distanceSquared(bot.getLocation()) <= 20.25))
                .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    private void flee(Player bot) {
        if (threat == null || threat.isDead() || !threat.isValid()
                || threat.getWorld() != bot.getWorld()) {
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        Location destination = findRetreatDestination(bot, threat);
        if (destination == null) {
            Vector away = bot.getLocation().toVector().subtract(threat.getLocation().toVector());
            away.setY(0.0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            destination = bot.getLocation().clone().add(away.normalize().multiply(8.0));
        }
        state = "Быстро отступаю и ищу безопасное место";
        decisionReason = "Опасность слишком высока: выбираю свободный участок подальше от противника и экономлю здоровье.";
        decisionConfidence = 1.0;
        navigator.moveTo(destination, true);
    }

    private int nextCombatMoveTick;
    private int combatMoveUntilTick;
    private int combatStrafeDirection = 1;
    private void defend(Player bot) {
        if (threat == null || threat.isDead() || !threat.isValid()
                || threat.getWorld() != bot.getWorld()) {
            attackRequested = false;
            manualOrderActive = false;
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        Location target = threat.getLocation();
        double distance = bot.getLocation().distance(target);
        survival.equipBestWeapon(bot);

        if (distance > 4.0) {
            state = "Сближаюсь с противником";
            boolean canSprint = distance > 8.0 && bot.getFoodLevel() >= 12 && bot.getHealth() > 8.0;
            if (!moveTowardSafely(bot, target, 0.15, canSprint)) {
                Vector toward = target.toVector().subtract(bot.getLocation().toVector());
                toward.setY(0.0);
                tryCombatMove(toward, 0.15);
            }
        } else {
            if (tickCounter >= nextCombatMoveTick) {
                combatStrafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                combatMoveUntilTick = tickCounter + ThreadLocalRandom.current().nextInt(10, 19);
                nextCombatMoveTick = tickCounter + ThreadLocalRandom.current().nextInt(18, 31);
            }

            if (distance < 2.0) {
                Vector away = bot.getLocation().toVector().subtract(target.toVector());
                away.setY(0.0);
                if (!tryCombatMove(away, 0.17)) navigator.stop();
            } else if (tickCounter < combatMoveUntilTick) {
                Vector toward = target.toVector().subtract(bot.getLocation().toVector());
                toward.setY(0.0);
                if (toward.lengthSquared() > 0.001) {
                    toward.normalize();
                    Vector side = new Vector(-toward.getZ(), 0.0, toward.getX())
                            .multiply(combatStrafeDirection);
                    if (!tryCombatMove(side, 0.14)) navigator.stop();
                }
            } else if (distance > 2.7) {
                Vector toward = target.toVector().subtract(bot.getLocation().toVector());
                toward.setY(0.0);
                if (!tryCombatMove(toward, 0.09)) navigator.stop();
            } else {
                navigator.stop();
            }
            state = "Маневрирую в бою с " + threat.getName();
        }

        face(bot, threat.getEyeLocation());
        // Paper's LivingEntity.attack uses the server's normal attack attributes,
        // held item and cooldown. An empty main hand still uses vanilla fist damage.
        if (distance <= 3.0 && tickCounter >= nextAttackTick
                && performVanillaMeleeAttack(bot, threat)) {
            nextAttackTick = tickCounter + 2;
        }
    }

    private void hunt(Player bot) {
        if (bot.getFoodLevel() >= 14 && survival.hasFood(bot)) {
            foodTarget = null;
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        if (foodTarget == null || foodTarget.isDead() || !foodTarget.isValid()) {
            foodTarget = survival.findFoodAnimal(bot, 24.0);
        }
        if (foodTarget == null) {
            survival.deferHunt(tickCounter);
            state = "Ищу животных и пищу";
            goal = GoalPlanner.Goal.EXPLORE;
            explorationRequested = true;
            exploreTarget = null;
            return;
        }
        Location target = foodTarget.getLocation();
        double distanceSquared = bot.getLocation().distanceSquared(target);
        if (distanceSquared > 9.0) {
            state = "Иду на охоту за пищей";
            moveTowardSafely(bot, target, 0.12);
            return;
        }
        navigator.stop();
        survival.equipBestWeapon(bot);
        face(bot, foodTarget.getEyeLocation());
        state = "Добываю пищу";
        if (tickCounter >= nextHuntAttackTick && performVanillaMeleeAttack(bot, foodTarget)) {
            nextHuntAttackTick = tickCounter + 2;
        }
    }


    private void sleep(Player bot) {
        if (!survival.isNight(bot)) {
            if (bot.isSleeping()) {
                try { bot.wakeup(false); } catch (IllegalStateException ignored) { }
            }
            navigator.stop();
            state = "Проснулся; продолжаю свои дела";
            goal = GoalPlanner.Goal.IDLE;
            return;
        }
        if (survival.sleepTick(bot, navigator, tickCounter)) {
            state = bot.isSleeping() ? "Сплю и восстанавливаю силы" : "Ищу место для сна";
            return;
        }
        goal = GoalPlanner.Goal.EXPLORE;
        explorationRequested = true;
        exploreTarget = null;
    }

    private void followTarget(Player bot) {
        Player target = followTarget == null ? null : Bukkit.getPlayer(followTarget);
        if (target == null || !target.isOnline() || target.getWorld() != bot.getWorld()) {
            navigator.stop();
            followTarget = null;
            if (!miningRequested && !attackRequested && !explorationRequested) {
                manualOrderActive = false;
            }
            goal = GoalPlanner.Goal.IDLE;
            state = "Игрока нет рядом — возвращаюсь к своим делам";
            return;
        }
        Location behind = target.getLocation().clone();
        Vector facing = target.getLocation().getDirection();
        facing.setY(0);
        if (facing.lengthSquared() > 0.001) behind.subtract(facing.normalize().multiply(2.4));
        double distance = bot.getLocation().distance(target.getLocation());
        if (distance <= 2.8) {
            navigator.stop();
            if (tickCounter % 50 == 0) face(bot, target.getEyeLocation());
            state = "Держусь рядом с " + target.getName();
        } else {
            state = "Иду рядом с " + target.getName();
            boolean canSprint = distance > 12.0 && bot.getFoodLevel() >= 14
                    && bot.getHealth() >= bot.getMaxHealth() * 0.65;
            navigator.moveTo(behind, canSprint);
        }
    }

    private void mine(Player bot) {
        if (!miningRequested) {
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        // A cached Bukkit Block may outlive its chunk. Discard it before any
        // block-state read so the bot never touches an unloaded chunk.
        if (miningTarget != null
                && !miningTarget.getWorld().isChunkLoaded(miningTarget.getX() >> 4, miningTarget.getZ() >> 4)) {
            miningTarget = null;
            miningTicks = 0;
        }
        if (miningTarget == null || miningTarget.getType().isAir()) {
            if (tickCounter < nextSearchTick) {
                state = "Ищу ресурсы";
                return;
            }
            miningTarget = findMiningTarget(bot);
            nextSearchTick = tickCounter + (miningTarget == null ? 100 : 0);
            miningTicks = 0;
        }
        if (miningTarget == null) {
            if (tickCounter % 100 == 0) {
                say("Поблизости не нашёл подходящего блока. Перейду в другое место.");
                survival.deferMining(miningMode, tickCounter);
                miningRequested = false;
                manualOrderActive = false;
                minedThisTask = 0;
                goal = GoalPlanner.Goal.EXPLORE;
                explorationRequested = true;
                exploreTarget = null;
            }
            state = "Ищу ресурсы";
            return;
        }

        Location blockLocation = miningTarget.getLocation().add(0.5, 0.5, 0.5);
        // Same 4.5-block eye reach and line-of-sight rule as a survival player.
        boolean canReach = bot.getEyeLocation().distanceSquared(blockLocation) <= 4.5 * 4.5
                && bot.hasLineOfSight(blockLocation);
        if (!canReach) {
            state = "Подхожу к блоку " + miningTarget.getType().name().toLowerCase(Locale.ROOT);
            if (!navigator.moveTo(miningTarget.getLocation())) {
                miningTarget = null;
                miningTicks = 0;
                nextSearchTick = tickCounter + 40;
            }
            return;
        }

        navigator.stop();
        survival.equipBestTool(bot, miningMode.equals("wood") ? "AXE" : "PICKAXE");
        face(bot, blockLocation);
        int baseTicks = Math.max(12, plugin.getConfig().getInt("brain.mining.break-ticks", 28));
        int requiredTicks = survival.breakTicks(bot, miningTarget.getType(),
                miningMode.equals("wood") ? baseTicks : baseTicks * 2);
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
                nextSearchTick = tickCounter + 40;
                int limit = Math.max(1, plugin.getConfig().getInt("brain.mining.blocks-per-order", 4));
                if (minedThisTask >= limit) {
                    miningRequested = false;
                    manualOrderActive = false;
                    minedThisTask = 0;
                    goal = GoalPlanner.Goal.IDLE;
                    say("Закончил добычу. Теперь у меня в памяти " + memory.totalMined() + " добытых блоков.");
                }
            } else {
                miningScanReset();
                miningRequested = false;
                manualOrderActive = false;
                goal = GoalPlanner.Goal.IDLE;
                nextSearchTick = tickCounter + 100;
                say("Не получилось сломать этот блок. Сервер или регион запретил добычу, поэтому я не обхожу защиту.");
            }
        }
    }

    private Block findMiningTarget(Player bot) {
        World world = bot.getWorld();
        Location origin = bot.getLocation();
        int radius = Math.max(6, Math.min(12, plugin.getConfig().getInt("brain.mining.search-radius", 10)));
        Block best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        boolean woodMode = miningMode.equals("wood");
        int minimumToolTier = miningMode.equals("iron") ? 3 : 1;
        if (!woodMode && survivalToolTier(bot, "PICKAXE") < minimumToolTier) return null;
        int minY = Math.max(world.getMinHeight() + 1, origin.getBlockY() - (woodMode ? 4 : 10));
        int maxY = Math.min(world.getMaxHeight() - 2, origin.getBlockY() + (woodMode ? 8 : 4));
        Location eye = bot.getEyeLocation();
        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (!isWantedResource(type)) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    double distance = center.distanceSquared(eye);
                    if (distance >= bestDistance || distance > (radius + 2.0) * (radius + 2.0)) continue;
                    // Prefer resources the bot can actually see and reach. Selecting
                    // a log hidden behind another trunk made it repeatedly walk to a
                    // blocked side, discard the target, and select the same log again.
                    if (!bot.hasLineOfSight(center)) continue;
                    if (woodMode && !hasNearbyLeaves(block)) continue;
                    best = block;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void miningScanReset() { miningTarget = null; miningTicks = 0; }

    private boolean isWantedResource(Material type) {
        String name = type.name();
        return switch (miningMode) {
            case "wood" -> (name.endsWith("_LOG") || name.endsWith("_WOOD"))
                    && !name.startsWith("STRIPPED_");
            case "coal" -> name.endsWith("_COAL_ORE");
            case "iron" -> name.endsWith("_IRON_ORE");
            case "stone" -> name.equals("STONE") || name.equals("COBBLESTONE");
            default -> false;
        };
    }

    private int survivalToolTier(Player bot, String suffix) {
        int best = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item == null || !item.getType().name().endsWith("_" + suffix)) continue;
            if (item.getType().getMaxDurability() <= 0) continue;
            if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable
                    && item.getType().getMaxDurability() - damageable.getDamage() <= 1) continue;
            String name = item.getType().name();
            int tier = name.startsWith("NETHERITE_") ? 6 : name.startsWith("DIAMOND_") ? 5
                    : name.startsWith("IRON_") ? 4 : name.startsWith("STONE_") ? 3
                    : name.startsWith("GOLDEN_") ? 2 : 1;
            best = Math.max(best, tier);
        }
        return best;
    }

    private boolean hasNearbyLeaves(Block log) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
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
            if (!explorationRequested) {
                exploreTarget = null;
                manualOrderActive = false;
                goal = GoalPlanner.Goal.IDLE;
                nextExploreTick = tickCounter + ThreadLocalRandom.current().nextInt(100, 181);
                navigator.stop();
                state = "Осмотрел окрестности; выбираю следующую цель";
                return;
            }
            int radius = 10 + (int) (personality.curiosity() * 12);
            for (int attempt = 0; attempt < 24; attempt++) {
                int x = bot.getLocation().getBlockX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
                int z = bot.getLocation().getBlockZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
                if (!bot.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                int groundY = bot.getWorld().getHighestBlockYAt(x, z);
                while (groundY > bot.getWorld().getMinHeight() + 2) {
                    String groundName = bot.getWorld().getBlockAt(x, groundY, z).getType().name();
                    if (!groundName.endsWith("_LEAVES") && !groundName.endsWith("_LOG")
                            && !groundName.endsWith("_WOOD")) break;
                    groundY--;
                }
                int y = groundY + 1;
                Location candidate = new Location(bot.getWorld(), x + 0.5, y, z + 0.5);
                Block floor = bot.getWorld().getBlockAt(x, y - 1, z);
                Block feet = bot.getWorld().getBlockAt(x, y, z);
                Block head = bot.getWorld().getBlockAt(x, y + 1, z);
                if (navigator.canStandAt(candidate) && !isLearnedDanger(candidate)) {
                    exploreTarget = candidate;
                    break;
                }
            }
            if (exploreTarget == null) {
                goal = GoalPlanner.Goal.IDLE;
                explorationRequested = false;
                nextExploreTick = tickCounter + 100;
                return;
            }
            explorationRequested = false;
        }
        state = "Ищу ресурсы и исследую окрестности";
        double travelDistance = bot.getLocation().distance(exploreTarget);
        boolean canSprint = travelDistance > 9.0 && bot.getFoodLevel() >= 14
                && bot.getHealth() >= bot.getMaxHealth() * 0.65;
        if (!navigator.moveTo(exploreTarget, canSprint)) {
            exploreTarget = null;
            explorationRequested = false;
            nextExploreTick = tickCounter + 100;
        }
    }

    void follow(Player player) {
        if (player == null) return;
        pausedByPlayer = false;
        followTarget = player.getUniqueId();
        manualOrderActive = true;
        miningRequested = false;
        attackRequested = false;
        goal = GoalPlanner.Goal.FOLLOW;
        if (navigator != null) navigator.stop();
        // Confirmation is sent by the command/chat layer.
    }

    boolean requestMining(String mode) {
        String value = mode == null ? "wood" : mode.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("wood", "stone", "coal", "iron").contains(value)) return false;
        pausedByPlayer = false;
        miningMode = value;
        manualOrderActive = true;
        miningRequested = true;
        minedThisTask = 0;
        followTarget = null;
        attackRequested = false;
        goal = GoalPlanner.Goal.MINE;
        nextSearchTick = 0;
        if (navigator != null) navigator.stop();
        // Confirmation is sent by the command/chat layer.
        return true;
    }

    boolean attackNearest() {
        pausedByPlayer = false;
        attackRequested = true;
        manualOrderActive = true;
        followTarget = null;
        miningRequested = false;
        threat = plugin.controller().bukkitPlayer() == null ? null : findNearestMonster(plugin.controller().bukkitPlayer(), 20);
        if (threat == null) {
            attackRequested = false;
            manualOrderActive = false;
            // Caller reports the missing target.
            return false;
        }
        goal = GoalPlanner.Goal.DEFEND;
        if (navigator != null) navigator.stop();
        // Caller reports the selected target.
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
        pausedByPlayer = false;
        manualOrderActive = true;
        explorationRequested = true;
        followTarget = null;
        miningRequested = false;
        attackRequested = false;
        exploreTarget = null;
        goal = GoalPlanner.Goal.EXPLORE;
        if (navigator != null) navigator.stop();
    }
    void resumeForCommand() {
        pausedByPlayer = false;
    }

    void pauseByPlayer() {
        stop();
        pausedByPlayer = true;
        state = "Жду на месте";
    }

    void stop() {
        pausedByPlayer = false;
        conversationTarget = null;
        conversationUntilTick = 0;
        manualOrderActive = false;
        foodTarget = null;
        followTarget = null;
        miningRequested = false;
        attackRequested = false;
        explorationRequested = false;
        idleWanderTarget = null;
        idlePauseUntilTick = 0;
        miningTarget = null;
        exploreTarget = null;
        defensivePlayerId = null;
        defensiveDestination = null;
        defensiveUntilTick = 0;
        if (navigator != null) navigator.stop();
        goal = GoalPlanner.Goal.IDLE;
        state = "Остановился";
        // Confirmation is sent by the command/chat layer.
    }


    String opinion(String message, UUID player) {
        return personality.opinion(message, memory.trust(player));
    }
    private void face(Player bot, Location target) {
        if (bot == null || target == null || target.getWorld() != bot.getWorld()) return;
        double dx = target.getX() - bot.getLocation().getX();
        double dy = target.getY() - (bot.getLocation().getY() + 1.6);
        double dz = target.getZ() - bot.getLocation().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        pitch = Math.max(-75.0F, Math.min(75.0F, pitch));
        bot.setRotation(yaw, pitch);
        org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer =
                (org.bukkit.craftbukkit.entity.CraftPlayer) bot;
        craftPlayer.getHandle().setYHeadRot(yaw);
        craftPlayer.getHandle().setYBodyRot(yaw);
    }


    private void say(String message) {
        String botName = plugin.getConfig().getString("bot-name", "Igrok");
        if (botName == null || botName.isBlank()) botName = "Igrok";
        Bukkit.broadcastMessage("§6" + botName + "§7: §f" + message);
    }
}
