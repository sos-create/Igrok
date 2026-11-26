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

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("chat.enabled", true)
                    || !plugin.controller().isSpawned()) return;

            Player sender = Bukkit.getPlayer(senderId);
            plugin.brain().memory().rememberMessage(senderId, message);

            String botName = plugin.getConfig().getString("bot-name", "Igrok").toLowerCase(Locale.ROOT);
            if (!message.contains(botName) && !message.contains("игрок")) return;

            long now = System.currentTimeMillis();
            long cooldown = plugin.getConfig().getLong("chat.cooldown-seconds", 3) * 1000L;
            long previous = lastReply.getOrDefault(senderId, 0L);
            if (now - previous < cooldown) return;
            lastReply.put(senderId, now);

            if (sender != null && sender.hasPermission("igrok.build")
                    && plugin.getConfig().getBoolean("building.chat-trigger", true)
                    && contains(message, "построй дом", "построй мне дом", "построй домик", "строй дом")) {
                Bukkit.broadcast(Component.text("Igrok: Сейчас найду свободную ровную площадку и построю дом."));
                if (!plugin.houseBuilder().start(sender.getLocation(), sender)) {
                    Bukkit.broadcast(Component.text("Igrok: Не удалось начать строительство. Возможно, нет подходящей площадки или я уже занят."));
                }
                return;
            }

            if (sender != null && sender.hasPermission("igrok.control")) {
                if (contains(message, "следуй за мной", "иди за мной", "держись рядом")) {
                    plugin.brain().follow(sender);
                    return;
                }
                if (contains(message, "остановись", "стой", "прекрати")) {
                    plugin.brain().stop();
                    return;
                }
                if (contains(message, "сруби дерево", "добудь дерево", "добудь брёвна", "наруби дерева")) {
                    plugin.brain().requestMining("wood");
                    return;
                }
                if (contains(message, "добудь камень", "копай камень", "копай руду", "начни копать")) {
                    plugin.brain().requestMining("stone");
                    return;
                }
                if (contains(message, "атакуй", "защищайся", "напади на моба")) {
                    plugin.brain().attackNearest();
                    return;
                }
                if (contains(message, "исследуй", "разведай", "прогуляйся")) {
                    plugin.brain().exploreNow();
                    return;
                }
            }

            if (contains(message, "мнение", "что думаешь", "как считаешь", "твоя точка зрения")) {
                Bukkit.broadcast(Component.text("Igrok: " + plugin.brain().opinion(message, senderId)));
                return;
            }

            Bukkit.broadcast(Component.text("Igrok: " + response(message)));
        });
    }

    private String response(String message) {
        if (contains(message, "привет", "здравствуй", "добрый день", "хай")) return "Привет! Рад тебя видеть.";
        if (contains(message, "как дела", "как ты")) return "Всё нормально. Сейчас я: " + plugin.brain().state() + ".";
        if (contains(message, "что делаешь", "чем занят")) return "Сейчас я: " + plugin.brain().state() + ".";
        if (contains(message, "кто ты", "ты кто")) return "Я Igrok. У меня есть характер, память о наших разговорах и собственные игровые цели.";
        if (contains(message, "помоги", "помощь")) return "Могу следовать за тобой, исследовать местность, добывать дерево и камень, защищаться от враждебных мобов и строить.";
        if (contains(message, "спасибо", "благодарю")) return "Всегда пожалуйста!";
        if (contains(message, "пока", "до встречи")) return "До встречи! Я продолжу заниматься своими делами.";
        if (message.contains("?")) return "Я обдумаю это с учётом того, что уже знаю. Можешь спросить, что я думаю о строительстве, шахтах, боях или других игроках.";
        return "Я тебя услышал. Сейчас я: " + plugin.brain().state() + ".";
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
