package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Filters addressed chat, coalesces bursts, and handles one delayed thought per player. */
final class ChatDialogue implements Listener {
    private static final int MAX_CHAT_CHARS = 256;
    private static final long DUPLICATE_WINDOW_MILLIS = 60_000L;

    private static final class PendingMessage {
        private String message;
        private BukkitTask task;

        private PendingMessage(String message) {
            this.message = message;
        }
    }

    private final IgrokPlugin plugin;
    private final Map<UUID, Long> lastReply = new HashMap<>();
    private final Map<UUID, String> lastIncomingFingerprint = new HashMap<>();
    private final Map<UUID, Long> lastIncomingAt = new HashMap<>();
    private final Map<UUID, PendingMessage> pendingMessages = new HashMap<>();
    private final Map<UUID, Long> lastBotQuestionAt = new HashMap<>();
    private final Map<UUID, CognitiveNetwork.Analysis> pendingThoughts = new HashMap<>();
    private final DialogueComposer composer;

    ChatDialogue(IgrokPlugin plugin) {
        this.plugin = plugin;
        this.composer = new DialogueComposer(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.isEnabled()) return;
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        String safeMessage = raw.length() > MAX_CHAT_CHARS
                ? raw.substring(0, MAX_CHAT_CHARS) : raw;
        UUID senderId = event.getPlayer().getUniqueId();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    queueMessage(senderId, safeMessage);
                } catch (RuntimeException error) {
                    plugin.reportRuntimeError("chat-queue", error);
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // The server is shutting down.
        }
    }
    private void queueMessage(UUID senderId, String rawMessage) {
        if (!plugin.isEnabled() || rawMessage == null || rawMessage.isBlank()) return;
        long now = System.currentTimeMillis();
        String fingerprint = fingerprint(rawMessage);
        String lower = rawMessage.toLowerCase(Locale.ROOT);
        Player sender = Bukkit.getPlayer(senderId);
        boolean addressed = mentionsBot(lower, plugin.getConfig().getString("bot-name", "Igrok"))
                || isNearBot(sender);
        long followUpMillis = Math.max(10L,
                plugin.getConfig().getLong("chat.follow-up-window-seconds", 90)) * 1000L;
        long questionAt = lastBotQuestionAt.getOrDefault(senderId, 0L);
        boolean continuation = questionAt > 0L && now - questionAt <= followUpMillis
                && isConversationalFollowUp(lower);
        if (questionAt > 0L && now - questionAt > followUpMillis) {
            lastBotQuestionAt.remove(senderId);
        }

        String previousFingerprint = lastIncomingFingerprint.get(senderId);
        long previousAt = lastIncomingAt.getOrDefault(senderId, 0L);
        if (!continuation && fingerprint.equals(previousFingerprint)
                && now - previousAt < DUPLICATE_WINDOW_MILLIS) return;

        lastIncomingFingerprint.put(senderId, fingerprint);
        lastIncomingAt.put(senderId, now);
        if (lastIncomingFingerprint.size() > 512) {
            lastIncomingFingerprint.entrySet().removeIf(entry ->
                    now - lastIncomingAt.getOrDefault(entry.getKey(), 0L) > 300_000L);
            lastIncomingAt.keySet().retainAll(lastIncomingFingerprint.keySet());
        }

        if (addressed && sender != null) {
            plugin.brain().onPlayerAddressed(sender);
            if (plugin.controller() != null && plugin.controller().isSpawned()) {
                plugin.skills().gain("social", 1);
                plugin.skills().gain("communication", 1);
                if (lower.contains("спасибо") || lower.contains("привет") || lower.contains("помоги")
                        || lower.contains("друг") || lower.contains("please") || lower.contains("thanks")) {
                    plugin.skills().gain("trust", 1);
                }
            }
        }

        PendingMessage pending = pendingMessages.get(senderId);
        if (pending != null) {
            // Ignore unrelated public chat. Only addressed messages or valid replies
            // may update/reschedule the bot's pending response.
            if (!addressed && !continuation) return;
            pending.message = rawMessage;
            scheduleReply(senderId, pending);
            return;
        }
        if (!addressed && !continuation) return;

        PendingMessage created = new PendingMessage(rawMessage);
        pendingMessages.put(senderId, created);
        scheduleReply(senderId, created);
    }


    private void scheduleReply(UUID senderId, PendingMessage pending) {
        if (pending.task != null) pending.task.cancel();
        int minDelay = Math.max(1, plugin.getConfig().getInt("chat.reply-delay-min-seconds", 5));
        int maxDelay = Math.max(minDelay, plugin.getConfig().getInt("chat.reply-delay-max-seconds", 5));
        int delaySeconds = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
        try {
            pending.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PendingMessage queued = pendingMessages.remove(senderId);
                if (queued != pending || !plugin.isEnabled()) return;
                processMessage(senderId, queued.message);
            }, delaySeconds * 20L);
        } catch (IllegalPluginAccessException ignored) {
            pendingMessages.remove(senderId);
        }
    }

    /** Registers a short reply window for a question asked by the bot itself. */
    void expectReply(UUID playerId) {
        if (playerId != null) lastBotQuestionAt.put(playerId, System.currentTimeMillis());
    }

    void shutdown() {
        for (PendingMessage pending : pendingMessages.values()) {
            if (pending.task != null) pending.task.cancel();
        }
        pendingMessages.clear();
        pendingThoughts.clear();
        lastBotQuestionAt.clear();
        lastReply.clear();
        lastIncomingFingerprint.clear();
        lastIncomingAt.clear();
    }

    private String fingerprint(String message) {
        return message.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    private void processMessage(UUID senderId, String rawMessage) {
        try {
            processMessageInternal(senderId, rawMessage);
        } catch (RuntimeException error) {
            plugin.reportRuntimeError("chat-processing", error);
            Player sender = Bukkit.getPlayer(senderId);
            if (sender != null) {
                String botName = plugin.getConfig().getString("bot-name", "Igrok");
                if (botName == null || botName.isBlank()) botName = "Igrok";
                sender.sendMessage("§6" + botName + "§7: §fНе смог безопасно обработать это сообщение. Я остановил действие.");
            }
        }
    }

    private void processMessageInternal(UUID senderId, String rawMessage) {
        if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("chat.enabled", true)
                || !plugin.controller().isSpawned() || rawMessage == null || rawMessage.isBlank()) return;
        Player sender = Bukkit.getPlayer(senderId);
        Player bot = plugin.controller().bukkitPlayer();
        if (sender == null || bot == null || sender.getWorld() != bot.getWorld()) return;

        double radius = Math.max(4.0, plugin.getConfig().getDouble("chat.listen-radius", 16.0));
        if (sender.getLocation().distanceSquared(bot.getLocation()) > radius * radius) return;

        String message = rawMessage.toLowerCase(Locale.ROOT);
        boolean addressed = mentionsBot(message, plugin.getConfig().getString("bot-name", "Igrok"))
                || isNearBot(sender);
        long followUpMillis = Math.max(10L,
                plugin.getConfig().getLong("chat.follow-up-window-seconds", 90)) * 1000L;
        long questionAt = lastBotQuestionAt.getOrDefault(senderId, 0L);
        boolean continuation = questionAt > 0L && System.currentTimeMillis() - questionAt <= followUpMillis
                && isConversationalFollowUp(message);
        if (!addressed && !continuation) return;
        if (continuation) lastBotQuestionAt.remove(senderId);

        long now = System.currentTimeMillis();
        if (pendingThoughts.size() > 512) pendingThoughts.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        if (lastReply.size() > 512) lastReply.entrySet().removeIf(entry -> now - entry.getValue() > 300_000L);
        long cooldown = Math.max(1L, plugin.getConfig().getLong("chat.cooldown-seconds", 5)) * 1000L;
        if (!continuation && now - lastReply.getOrDefault(senderId, 0L) < cooldown) return;
        lastReply.put(senderId, now);

        CognitiveNetwork.Analysis thought = plugin.cognition().think(
                rawMessage, plugin.brain().memory().lastTopic(senderId),
                plugin.brain().memory().trust(senderId),
                plugin.brain().memory().behaviorSignals(senderId));
        CognitiveNetwork.Analysis previous = pendingThoughts.remove(senderId);
        if (previous != null && continuation) {
            // Only a genuine conversational reply can rate the previous thought.
            // A later unrelated sentence must not reinforce or punish old associations.
            double feedback = switch (thought.intent()) {
                case THANKS, AGREEMENT -> 0.45;
                case DISAGREEMENT -> -0.45;
                default -> thought.sentiment() >= 2 ? 0.25
                        : thought.sentiment() <= -2 ? -0.25 : 0.0;
            };
            if (feedback != 0.0) plugin.cognition().learn(previous, feedback);
        }
        pendingThoughts.put(senderId, thought);
        plugin.cognition().learn(thought, 0.0);
        // A yes/no answer to an active social invitation belongs to that invitation,
        // not to the general command parser. Consume it before brain intent handling.
        if (continuation && plugin.socialLife() != null
                && plugin.socialLife().handleReply(sender, rawMessage)) return;
        plugin.brain().onPlayerMessage(sender, rawMessage);

        String action = thought.action();
        if ("tnt".equals(action)) {
            sendReply(senderId, "Нет. Я не буду ставить, поджигать или взрывать TNT. Это моё постоянное правило.");
            return;
        }
        if (action != null) {
            handleAction(sender, action, thought);
            return;
        }
        sendReply(senderId, composer.reply(sender, rawMessage, thought));
    }

    private void handleAction(Player sender, String action, CognitiveNetwork.Analysis thought) {
        boolean thoughtDirectOrder = thought != null && thought.directOrder();
        UUID id = sender.getUniqueId();
        boolean permitted = switch (action) {
            case "build" -> sender.hasPermission("igrok.build")
                    && plugin.getConfig().getBoolean("building.chat-trigger", true);
            default -> sender.hasPermission("igrok.control");
        };
        if (!permitted) {
            sendReply(id, "Я услышал просьбу, но у тебя нет разрешения на такие действия. Можем пока поговорить.");
            return;
        }
        if (!plugin.brain().considerRequest(id, action, thoughtDirectOrder)) {
            sendReply(id, plugin.brain().refusalReason(id, thoughtDirectOrder));
            return;
        }

        // Any accepted new task releases an explicit stop/hold.
        if (!"stop".equals(action)) plugin.brain().resumeForCommand();
        boolean started;
        switch (action) {
            case "build" -> started = plugin.houseBuilder().start(sender.getLocation(), sender);
            case "follow" -> { plugin.brain().follow(sender); started = true; }
            case "stop" -> { plugin.brain().pauseByPlayer(); started = true; }
            case "wood" -> started = plugin.brain().requestMining("wood");
            case "stone" -> started = plugin.brain().requestMining("stone");
            case "attack" -> started = plugin.brain().attackNearest();
            case "explore" -> { plugin.brain().exploreNow(); started = true; }
            default -> started = false;
        }
        sendReply(id, composer.actionReply(action, true, started, sender));
    }
    private boolean isNearBot(Player sender) {
        if (sender == null || !sender.isOnline()) return false;
        Player bot = plugin.controller().bukkitPlayer();
        if (bot == null || !bot.isOnline() || sender.getWorld() != bot.getWorld()) return false;
        double radius = Math.max(4.0, plugin.getConfig().getDouble("chat.listen-radius", 16.0));
        return sender.getLocation().distanceSquared(bot.getLocation()) <= radius * radius;
    }

    private boolean mentionsBot(String message, String name) {
        String normalized = name == null ? "igrok" : name.toLowerCase(Locale.ROOT).trim();
        if (hasWord(message, normalized)) return true;
        // Accept the Russian bot name anywhere in the sentence, not only at
        // the beginning: "Привет, Игрок" and "Игрок, подойди" both address it.
        return hasWord(message, "игрок");
    }


    private boolean hasWord(String text, String word) {
        if (word == null || word.isBlank()) return false;
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(word)
                + "(?![\\p{L}\\p{N}_])").matcher(text).find();
    }

    private boolean isConversationalFollowUp(String message) {
        if (message.length() > 180) return false;
        return message.contains("?") || message.matches("(?iu)\\s*(да|нет|ага|угу|ладно|понятно|ясно)[!.? ]*")
                || contains(message, "а ты", "почему", "зачем", "и что", "согласен", "не согласен",
                "расскажи", "а если", "что насчет", "что насчёт", "спасибо", "круто", "хорошо",
                "продолжай", "поясни", "не понял", "почему так", "а дальше", "что имеешь в виду");
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    void recordBotReply(UUID playerId, String response) {
        if (playerId == null) return;
        if (response != null && response.contains("?")) {
            lastBotQuestionAt.put(playerId, System.currentTimeMillis());
        } else {
            lastBotQuestionAt.remove(playerId);
        }
    }

    private void sendReply(UUID playerId, String response) {
        if (response == null || response.isBlank()) return;
        plugin.brain().rememberBotMessage(playerId, response);
        recordBotReply(playerId, response);
        String botName = plugin.getConfig().getString("bot-name", "Igrok");
        if (botName == null || botName.isBlank()) botName = "Igrok";
        Bukkit.broadcast(Component.text(botName + ": " + response));
    }
}
