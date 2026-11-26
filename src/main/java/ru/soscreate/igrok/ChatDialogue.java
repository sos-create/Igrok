package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ChatDialogue implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastReply = new ConcurrentHashMap<>();

    ChatDialogue(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).toLowerCase(Locale.ROOT);
        String botName = plugin.getConfig().getString("bot-name", "Igrok").toLowerCase(Locale.ROOT);
        if (!text.contains(botName) && !text.contains("игрок")) return;

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("chat.cooldown-seconds", 3) * 1000L;
        long previous = lastReply.getOrDefault(event.getPlayer().getUniqueId(), 0L);
        if (now - previous < cooldown) return;
        lastReply.put(event.getPlayer().getUniqueId(), now);

        String reply = response(text);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled() && plugin.getServer().getOnlinePlayers().size() >= 0) {
                Bukkit.broadcast(Component.text("Igrok: " + reply));
            }
        });
    }

    private String response(String message) {
        if (contains(message, "привет", "здравствуй", "добрый день", "хай")) return "Привет! Рад тебя видеть.";
        if (contains(message, "как дела", "как ты")) return "У меня всё хорошо. А у тебя как дела?";
        if (contains(message, "что делаешь", "чем занят")) return "Пока осматриваюсь и общаюсь с игроками.";
        if (contains(message, "кто ты", "ты кто")) return "Я Igrok — искусственный игрок этого мира.";
        if (contains(message, "где ты", "ты где")) return "Я появился на стандартной точке возрождения этого мира.";
        if (contains(message, "помоги", "помощь")) return "Пока я умею общаться и отвечать на простые вопросы. Игровые задачи добавим следующим этапом.";
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
