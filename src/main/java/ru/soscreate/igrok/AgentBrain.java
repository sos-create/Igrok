package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
    private String decisionReason = "Пока нет достаточных наблюдений.";
    private double decisionConfidence;
    private final SurvivalController survival;
    private boolean manualOrderActive;
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
        try { followTarget = id.isBlank() ? null : UUID.fromString(id); }
        catch (IllegalArgumentException ignored) { followTarget = null; }
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
        tickCounter++;
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
            if (bot.getLocation().distanceSquared(idleWanderTarget) <= 2.25) {
                idleWanderTarget = null;
                idlePauseUntilTick = tickCounter + ThreadLocalRandom.current().nextInt(30, 91);
                nextIdleWanderTick = tickCounter + ThreadLocalRandom.current().nextInt(240, 601);
                navigator.stop(); state = "Остановился осмотреться"; return;
            }
            state = "Неспешно прогуливаюсь"; navigator.moveTo(idleWanderTarget); return;
        }
        navigator.stop();
        if (tickCounter < idlePauseUntilTick) { state = "Немного отдыхаю"; return; }
        Player nearby = bot.getNearbyEntities(6.0, 3.0, 6.0).stream()
                .filter(entity -> entity instanceof Player).map(entity -> (Player) entity)
                .filter(player -> player.isOnline() && !player.getUniqueId().equals(bot.getUniqueId()))
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
        if (nearby != null && tickCounter >= nextIdleLookTick) {
            face(bot, nearby.getEyeLocation());
            nextIdleLookTick = tickCounter + ThreadLocalRandom.current().nextInt(35, 81);
            state = "Наблюдаю за " + nearby.getName(); return;
        }
        if (tickCounter >= nextIdleLookTick) {
            float yaw = bot.getLocation().getYaw() + ThreadLocalRandom.current().nextInt(-75, 76);
            float pitch = ThreadLocalRandom.current().nextInt(-12, 13);
            bot.setRotation(yaw, pitch);
            nextIdleLookTick = tickCounter + ThreadLocalRandom.current().nextInt(40, 101);
        }
        if (plugin.getConfig().getBoolean("brain.idle-wander.enabled", true)
                && tickCounter >= nextIdleWanderTick && ThreadLocalRandom.current().nextDouble() < 0.0025) {
            int dx = ThreadLocalRandom.current().nextInt(-6, 7), dz = ThreadLocalRandom.current().nextInt(-6, 7);
            if (dx * dx + dz * dz >= 9) {
                Location candidate = bot.getLocation().getBlock().getLocation().add(dx, 0, dz);
                World world = bot.getWorld();
                if (world.isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                    int y = world.getHighestBlockYAt(candidate.getBlockX(), candidate.getBlockZ());
                    Location wander = new Location(world, candidate.getBlockX() + 0.5, y + 1.0,
                            candidate.getBlockZ() + 0.5);
                    if (navigator.canStandAt(wander)) {
                        idleWanderTarget = wander;
                        state = "Решил немного прогуляться"; return;
                    }
                }
            }
            nextIdleWanderTick = tickCounter + ThreadLocalRandom.current().nextInt(120, 241);
        }
        state = "Отдыхаю и наблюдаю";
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
            lastGreetingTick.put(nearest.getUniqueId(), tickCounter);
            state = "Общаюсь с " + nearest.getName();
            say(greeting);
        } else {
            state = "Рядом с игроком " + nearest.getName();
        }
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
    }

    void onPlayerInteraction(Player player) {
        if (player == null) return;
        memory.rememberPlayerName(player.getUniqueId(), player.getName());
        memory.recordConversation(player.getUniqueId(), "[подошёл и взаимодействовал со мной]", false);
        Player bot = plugin.controller().bukkitPlayer();
        if (bot != null) {
            face(bot, player.getEyeLocation());
            bot.swingMainHand();
        }
        String reply = memory.trust(player.getUniqueId()) >= 3
                ? "Привет, " + player.getName() + "! Рад, что заглянул."
                : "Привет, " + player.getName() + ". Что хотел?";
        rememberBotMessage(player.getUniqueId(), reply);
        say(reply);
    }

    void onPlayerAttack(Player player) {
        if (player == null) return;
        memory.rememberPlayerName(player.getUniqueId(), player.getName());
        memory.adjustReputation(player.getUniqueId(), -2);
        memory.recordConversation(player.getUniqueId(), "[ударил меня]", false);
        String reply = memory.trust(player.getUniqueId()) <= -3
                ? "Не надо меня бить, " + player.getName() + ". Я уже начинаю тебе не доверять."
                : "Эй, " + player.getName() + "! Зачем ты меня ударил?";
        rememberBotMessage(player.getUniqueId(), reply);
        say(reply);
    }

    /** The player may suggest an action, but the bot makes the final decision. */
    boolean considerRequest(UUID playerId, String action) {
        if (playerId == null || action == null) return false;
        String normalized = action.toLowerCase(Locale.ROOT);
        // Permanent safety rules cannot be overridden by trust, permissions or personality.
        if (normalized.contains("tnt") || normalized.contains("динамит")
                || normalized.contains("подж") || normalized.contains("взорв")) return false;
        if (plugin.getConfig().getBoolean("autonomy.refuse-all-player-orders", false)) return false;
        if ("stop".equals(normalized)) return true;

        int trust = memory.trust(playerId);
        double willingness = 0.28 + personality.sociability() * 0.24
                + personality.patience() * 0.12 + Math.max(-5, Math.min(5, trust)) * 0.055;
        // Busy, risky or disruptive requests are less likely to be accepted.
        if (goal != GoalPlanner.Goal.IDLE && !normalized.contains("останов")) willingness -= 0.20;
        if (normalized.contains("атак") || normalized.contains("напад")) willingness -= 0.16;
        if (normalized.contains("след") || normalized.contains("иди")) willingness -= 0.04;
        willingness = Math.max(0.05, Math.min(0.90, willingness));
        return ThreadLocalRandom.current().nextDouble() < willingness;
    }

    String refusalReason(UUID playerId) {
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
        if (deathLocation != null && deathLocation.getWorld() != null
                && (cause.equals("FALL") || cause.equals("LAVA") || cause.equals("FIRE")
                || cause.equals("FIRE_TICK") || cause.equals("HOT_FLOOR") || cause.equals("VOID")
                || cause.equals("DROWNING") || cause.equals("SUFFOCATION"))) {
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
        if (followed != null && (!followed.isOnline() || followed.getWorld() != bot.getWorld())) followed = null;

        boolean explore = explorationRequested;
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
        if (chosen != GoalPlanner.Goal.FLEE && followTarget != null) {
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
            nextBuildAttemptTick = tickCounter + 600;
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

    private int nextCombatMoveTick;
    private int combatMoveUntilTick;
    private int combatStrafeDirection = 1;

    private void defend(Player bot) {
        if (threat == null || threat.isDead() || !threat.isValid()) {
            attackRequested = false;
            manualOrderActive = false;
            goal = GoalPlanner.Goal.IDLE;
            navigator.stop();
            return;
        }
        Location target = threat.getLocation();
        double distance = bot.getLocation().distance(target);
        survival.equipBestWeapon(bot);
        face(bot, threat.getEyeLocation());

        // Avoid standing still in front of an enemy: keep a little distance,
        // sidestep unpredictably, and retreat briefly when the enemy gets too close.
        if (distance > 4.0) {
            state = "Сближаюсь с противником";
            navigator.moveTo(target);
        } else {
            navigator.stop();
            if (tickCounter >= nextCombatMoveTick) {
                combatStrafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                combatMoveUntilTick = tickCounter + ThreadLocalRandom.current().nextInt(8, 19);
                nextCombatMoveTick = tickCounter + ThreadLocalRandom.current().nextInt(24, 42);
            }
            if (distance < 2.0) {
                Vector away = bot.getLocation().toVector().subtract(target.toVector());
                away.setY(0);
                if (away.lengthSquared() > 0.001) {
                    away.normalize();
                    navigator.moveVector(away, 0.16);
                }
            } else if (tickCounter < combatMoveUntilTick) {
                Vector toward = target.toVector().subtract(bot.getLocation().toVector());
                toward.setY(0);
                if (toward.lengthSquared() > 0.001) {
                    toward.normalize();
                    Vector side = new Vector(-toward.getZ(), 0, toward.getX()).multiply(combatStrafeDirection);
                    navigator.moveVector(side, 0.14);
                }
            }
            state = "Маневрирую в бою с " + threat.getName();
        }

        if (distance <= 3.4 && tickCounter >= nextAttackTick) {
            bot.swingMainHand();
            bot.attack(threat);
            nextAttackTick = tickCounter + ThreadLocalRandom.current().nextInt(10, 16);
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
        if (bot.getLocation().distanceSquared(target) > 9.0) {
            state = "Иду на охоту за пищей";
            navigator.moveTo(target);
            return;
        }
        navigator.stop();
        survival.equipBestWeapon(bot);
        face(bot, foodTarget.getEyeLocation());
        state = "Добываю пищу";
        if (tickCounter >= nextHuntAttackTick) {
            bot.swingMainHand();
            bot.attack(foodTarget);
            nextHuntAttackTick = tickCounter + 14;
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
            state = "Жду возвращения игрока, за которым следую";
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
            navigator.moveTo(behind);
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
            nextSearchTick = tickCounter + 40;
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
        double distance = bot.getLocation().distance(blockLocation);
        if (distance > 4.4) {
            state = "Иду к блоку " + miningTarget.getType().name().toLowerCase(Locale.ROOT);
            navigator.moveTo(miningTarget.getLocation());
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
                int limit = Math.max(1, plugin.getConfig().getInt("brain.mining.blocks-per-order", 4));
                if (minedThisTask >= limit) {
                    miningRequested = false;
                    manualOrderActive = false;
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
                for (int y = Math.max(world.getMinHeight() + 1, origin.getBlockY() - (miningMode.equals("wood") ? 5 : 16));
                     y <= Math.min(world.getMaxHeight() - 2, origin.getBlockY() + 8); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (!isWantedResource(type)) continue;
                    if (!miningMode.equals("wood")
                            && survivalToolTier(bot, "PICKAXE") < (miningMode.equals("iron") ? 3 : 1)) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    double distance = center.distanceSquared(origin);
                    if (distance >= bestDistance || distance > (radius + 2.0) * (radius + 2.0)) continue;
                    if (miningMode.equals("wood") && !hasNearbyLeaves(block)) continue;
                    if (!miningMode.equals("wood") && !bot.hasLineOfSight(center)) continue;
                    best = block;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

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
            if (!explorationRequested) {
                manualOrderActive = false;
                goal = GoalPlanner.Goal.IDLE;
                navigator.stop();
                return;
            }
            int radius = 6 + (int) (personality.curiosity() * 10);
            for (int attempt = 0; attempt < 8; attempt++) {
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
                return;
            }
            explorationRequested = false;
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
        manualOrderActive = true;
        explorationRequested = true;
        followTarget = null;
        miningRequested = false;
        attackRequested = false;
        exploreTarget = null;
        goal = GoalPlanner.Goal.EXPLORE;
        if (navigator != null) navigator.stop();
    }

    void stop() {
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
        if (navigator != null) navigator.stop();
        goal = GoalPlanner.Goal.IDLE;
        state = "Остановился";
        // Confirmation is sent by the command/chat layer.
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
