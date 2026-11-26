package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ChatDialogue implements Listener {
    private final IgrokPlugin plugin;
    private final Map<UUID, Long> lastReply = new HashMap<>();

    ChatDialogue(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message())
                .toLowerCase(Locale.ROOT);
        UUID senderId = event.getPlayer().getUniqueId();

        // AsyncChatEvent may run off-thread. All Bukkit/config access and dialogue
        // state changes are deferred to the primary server thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("chat.enabled", true)
                    || !plugin.controller().isSpawned()) return;

            String botName = plugin.getConfig().getString("bot-name", "Igrok").toLowerCase(Locale.ROOT);
            if (!message.contains(botName) && !message.contains("игрок")) return;

            long now = System.currentTimeMillis();
            long cooldown = plugin.getConfig().getLong("chat.cooldown-seconds", 3) * 1000L;
            long previous = lastReply.getOrDefault(senderId, 0L);
            if (now - previous < cooldown) return;
            lastReply.put(senderId, now);

            Player sender = Bukkit.getPlayer(senderId);
            if (sender != null && sender.hasPermission("igrok.build")
                    && plugin.getConfig().getBoolean("building.chat-trigger", true)
                    && contains(message, "построй дом", "построй мне дом", "построй домик", "строй дом")) {
                Bukkit.broadcast(Component.text("Igrok: Сейчас найду свободную ровную площадку и построю дом."));
                if (!plugin.houseBuilder().start(sender.getLocation(), sender)) {
                    Bukkit.broadcast(Component.text("Igrok: Не удалось начать строительство. Возможно, нет подходящей площадки или я уже занят."));
                }
                return;
            }

            Bukkit.broadcast(Component.text("Igrok: " + response(message)));
        });
    }

    private String response(String message) {
        if (contains(message, "привет", "здравствуй", "добрый день", "хай")) return "Привет! Рад тебя видеть.";
        if (contains(message, "как дела", "как ты")) return "У меня всё хорошо. А у тебя как дела?";
        if (contains(message, "что делаешь", "чем занят")) return "Пока осматриваюсь и общаюсь с игроками.";
        if (contains(message, "кто ты", "ты кто")) return "Я Igrok — искусственный игрок этого мира.";
        if (contains(message, "где ты", "ты где")) return "Я появился на стандартной точке возрождения этого мира.";
        if (contains(message, "помоги", "помощь")) return "Пока я умею общаться и строить небольшой дом по просьбе игрока.";
        if (contains(message, "спасибо", "благодарю")) return "Всегда пожалуйста!";
        if (contains(message, "пока", "до встречи")) return "До встречи! Я останусь здесь.";
        if (message.contains("?")) return "Интересный вопрос. Расскажи немного подробнее.";
        return "Я тебя услышал. Расскажи подробнее — мне интересно.";
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
