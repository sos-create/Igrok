package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!plugin.isEnabled()) return;
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        String safeMessage = raw.length() > MAX_CHAT_CHARS
                ? raw.substring(0, MAX_CHAT_CHARS) : raw;
        UUID senderId = event.getPlayer().getUniqueId();
        try {
            // Config, queues and Bukkit entities are accessed only on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> queueMessage(senderId, safeMessage));
        } catch (IllegalPluginAccessException ignored) {
            // The server is shutting down.
        }
    }

    private void queueMessage(UUID senderId, String rawMessage) {
        if (!plugin.isEnabled() || rawMessage == null || rawMessage.isBlank()) return;
        long now = System.currentTimeMillis();
        String fingerprint = fingerprint(rawMessage);
        String previousFingerprint = lastIncomingFingerprint.get(senderId);
        long previousAt = lastIncomingAt.getOrDefault(senderId, 0L);
        if (fingerprint.equals(previousFingerprint) && now - previousAt < DUPLICATE_WINDOW_MILLIS) return;

        lastIncomingFingerprint.put(senderId, fingerprint);
        lastIncomingAt.put(senderId, now);
        if (lastIncomingFingerprint.size() > 512) {
            lastIncomingFingerprint.entrySet().removeIf(entry ->
                    now - lastIncomingAt.getOrDefault(entry.getKey(), 0L) > 300_000L);
            lastIncomingAt.keySet().retainAll(lastIncomingFingerprint.keySet());
        }

        PendingMessage pending = pendingMessages.get(senderId);
        if (pending != null) {
            // Several rapid messages become one thought; process only the latest text.
            pending.message = rawMessage;
            return;
        }

        int minDelay = Math.max(1, plugin.getConfig().getInt("chat.reply-delay-min-seconds", 20));
        int maxDelay = Math.max(minDelay, plugin.getConfig().getInt("chat.reply-delay-max-seconds", 30));
        int delaySeconds = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
        PendingMessage created = new PendingMessage(rawMessage);
        pendingMessages.put(senderId, created);
        try {
            created.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PendingMessage queued = pendingMessages.remove(senderId);
                if (queued != created || !plugin.isEnabled()) return;
                processMessage(senderId, queued.message);
            }, delaySeconds * 20L);
        } catch (IllegalPluginAccessException ignored) {
            pendingMessages.remove(senderId);
        }
    }

    void shutdown() {
        for (PendingMessage pending : pendingMessages.values()) {
            if (pending.task != null) pending.task.cancel();
        }
        pendingMessages.clear();
        pendingThoughts.clear();
        lastBotQuestionAt.clear();
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
                sender.sendMessage("§6Igrok§7: §fНе смог безопасно обработать это сообщение. Я остановил действие.");
            }
        }
    }

    private void processMessageInternal(UUID senderId, String rawMessage) {
        if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("chat.enabled", true)
                || !plugin.controller().isSpawned() || rawMessage == null || rawMessage.isBlank()) return;
        Player sender = Bukkit.getPlayer(senderId);
        Player bot = plugin.controller().bukkitPlayer();
        if (sender == null || bot == null || sender.getWorld() != bot.getWorld()) return;

        double radius = Math.max(3.0, plugin.getConfig().getDouble("chat.listen-radius", 12.0));
        if (sender.getLocation().distanceSquared(bot.getLocation()) > radius * radius) return;

        String message = rawMessage.toLowerCase(Locale.ROOT);
        boolean addressed = mentionsBot(message, plugin.getConfig().getString("bot-name", "Igrok"));
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
        long cooldown = Math.max(1L, plugin.getConfig().getLong("chat.cooldown-seconds", 20)) * 1000L;
        if (now - lastReply.getOrDefault(senderId, 0L) < cooldown) return;
        lastReply.put(senderId, now);

        CognitiveNetwork.Analysis thought = plugin.cognition().think(
                rawMessage, plugin.brain().memory().lastTopic(senderId),
                plugin.brain().memory().trust(senderId),
                plugin.brain().memory().behaviorSignals(senderId));
        CognitiveNetwork.Analysis previous = pendingThoughts.put(senderId, thought);
        if (previous != null) {
            double feedback = Math.max(-1.0, Math.min(1.0, thought.sentiment() / 2.0));
            plugin.cognition().learn(previous, feedback);
        }
        plugin.cognition().learn(thought, 0.0);
        plugin.brain().onPlayerMessage(sender, rawMessage);

        String action = thought.action();
        if ("tnt".equals(action)) {
            sendReply(senderId, "Нет. Я не буду ставить, поджигать или взрывать TNT. Это моё постоянное правило.");
            return;
        }
        if (action != null) {
            handleAction(sender, action);
            return;
        }
        sendReply(senderId, composer.reply(sender, rawMessage, thought));
    }

    private void handleAction(Player sender, String action) {
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
        if (!plugin.brain().considerRequest(id, action)) {
            sendReply(id, composer.actionReply(action, false, false, sender));
            return;
        }

        boolean started;
        switch (action) {
            case "build" -> started = plugin.houseBuilder().start(sender.getLocation(), sender);
            case "follow" -> { plugin.brain().follow(sender); started = true; }
            case "stop" -> { plugin.brain().stop(); started = true; }
            case "wood" -> started = plugin.brain().requestMining("wood");
            case "stone" -> started = plugin.brain().requestMining("stone");
            case "attack" -> started = plugin.brain().attackNearest();
            case "explore" -> { plugin.brain().exploreNow(); started = true; }
            default -> started = false;
        }
        sendReply(id, composer.actionReply(action, true, started, sender));
    }

    private boolean mentionsBot(String message, String name) {
        String normalized = name == null ? "igrok" : name.toLowerCase(Locale.ROOT).trim();
        return hasWord(message, normalized) || hasWord(message, "игрок") || message.contains("@игрок");
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

    private void sendReply(UUID playerId, String response) {
        plugin.brain().rememberBotMessage(playerId, response);
        if (response.contains("?")) lastBotQuestionAt.put(playerId, System.currentTimeMillis());
        else lastBotQuestionAt.remove(playerId);
        Bukkit.broadcast(Component.text("Igrok: " + response));
    }
}
