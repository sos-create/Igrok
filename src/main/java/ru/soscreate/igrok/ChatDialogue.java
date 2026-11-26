package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Filters addressed chat, sends it to the cognition layers and publishes replies. */
final class ChatDialogue implements Listener {
    private static final int MAX_CHAT_CHARS = 256;
    private final IgrokPlugin plugin;
    private final Map<UUID, Long> lastReply = new ConcurrentHashMap<>();
    private final Map<UUID, CognitiveNetwork.Analysis> pendingThoughts = new ConcurrentHashMap<>();
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
            Bukkit.getScheduler().runTask(plugin, () -> processMessage(senderId, safeMessage));
        } catch (IllegalPluginAccessException ignored) {
            // The server is shutting down; do not schedule work on a disabled plugin.
        }
    }

    private void processMessage(UUID senderId, String rawMessage) {
        try {
            processMessageInternal(senderId, rawMessage);
        } catch (RuntimeException error) {
            plugin.reportRuntimeError("chat-processing", error);
            Player sender = Bukkit.getPlayer(senderId);
            if (sender != null) {
                sender.sendMessage("§6Igrok§7: §fНе смог безопасно обработать это сообщение. "
                        + "Я остановил действие и попробую снова позже.");
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
        boolean continuation = plugin.brain().memory().isConversationActive(senderId, followUpMillis)
                && isConversationalFollowUp(message);
        if (!addressed && !continuation) return;

        long now = System.currentTimeMillis();
        if (pendingThoughts.size() > 512) pendingThoughts.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        if (pendingThoughts.size() > 1024) pendingThoughts.clear();
        if (lastReply.size() > 512) lastReply.entrySet().removeIf(entry -> now - entry.getValue() > 300_000L);
        long cooldown = Math.max(1L, plugin.getConfig().getLong("chat.cooldown-seconds", 2)) * 1000L;
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
            sendReply(id, "Я услышал просьбу, но у тебя нет разрешения на такие действия. Можем пока просто поговорить.");
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
        Bukkit.broadcast(Component.text("Igrok: " + response));
    }
}
