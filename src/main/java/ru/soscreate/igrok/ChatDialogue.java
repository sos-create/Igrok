package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Locale;
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
        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        UUID senderId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> processMessage(senderId, rawMessage));
    }

    private void processMessage(UUID senderId, String rawMessage) {
        if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("chat.enabled", true)
                || !plugin.controller().isSpawned()) return;
        Player sender = Bukkit.getPlayer(senderId);
        Player bot = plugin.controller().bukkitPlayer();
        if (sender == null || bot == null || rawMessage == null || rawMessage.isBlank()) return;

        String message = rawMessage.toLowerCase(Locale.ROOT);
        String botName = plugin.getConfig().getString("bot-name", "Igrok").toLowerCase(Locale.ROOT);
        // He listens only when directly addressed. Ordinary player-to-player chat is ignored.
        if (!message.contains(botName) && !message.contains("игрок")) return;

        double radius = Math.max(3.0, plugin.getConfig().getDouble("chat.listen-radius", 10.0));
        if (sender.getWorld() != bot.getWorld()
                || sender.getLocation().distanceSquared(bot.getLocation()) > radius * radius) return;

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("chat.cooldown-seconds", 3) * 1000L;
        if (now - lastReply.getOrDefault(senderId, 0L) < cooldown) return;
        lastReply.put(senderId, now);
        plugin.brain().onPlayerMessage(sender, rawMessage);

        String action = requestedAction(message);
        if (action != null) {
            // Permission is a server-side gate; passing it still does not force the bot to obey.
            boolean permitted = switch (action) {
                case "build" -> sender.hasPermission("igrok.build")
                        && plugin.getConfig().getBoolean("building.chat-trigger", true);
                default -> sender.hasPermission("igrok.control");
            };
            if (!permitted) {
                sendReply(senderId, "Я услышал просьбу, но у тебя нет разрешения просить меня об этом.");
                return;
            }
            if (action.equals("tnt")) {
                sendReply(senderId, "Нет. Я никогда не буду ставить, поджигать или взрывать TNT.");
                return;
            }
            if (!plugin.brain().considerRequest(senderId, action)) {
                sendReply(senderId, plugin.brain().refusalReason(senderId));
                return;
            }

            String response;
            switch (action) {
                case "build" -> response = plugin.houseBuilder().start(sender.getLocation(), sender)
                        ? "Ладно, я сам решил согласиться. Попробую построить дом."
                        : "Я согласился, но сейчас не могу начать строительство.";
                case "follow" -> {
                    plugin.brain().follow(sender);
                    response = "Хорошо, в этот раз я решил пойти с тобой.";
                }
                case "stop" -> {
                    plugin.brain().stop();
                    response = "Ладно, остановлюсь.";
                }
                case "wood" -> response = plugin.brain().requestMining("wood")
                        ? "Решил немного добыть дерева." : "Не могу сейчас начать добычу.";
                case "stone" -> response = plugin.brain().requestMining("stone")
                        ? "Решил поискать камень и руду." : "Не могу сейчас начать добычу.";
                case "attack" -> response = plugin.brain().attackNearest()
                        ? "Я решил разобраться с ближайшей угрозой."
                        : "Я согласился, но поблизости нет подходящей цели.";
                case "explore" -> {
                    plugin.brain().exploreNow();
                    response = "Хочу осмотреть окрестности. Пойду исследовать.";
                }
                default -> response = response(sender, message);
            }
            sendReply(senderId, response);
            return;
        }
        sendReply(senderId, response(sender, message));
    }

    private String requestedAction(String message) {
        if (contains(message, "поставь тнт", "поставь динамит", "подожги тнт",
                "взорви", "взорвать", "активируй тнт")) return "tnt";
        if (contains(message, "построй дом", "построй мне дом", "построй домик", "строй дом")) return "build";
        if (contains(message, "следуй за мной", "иди за мной", "держись рядом")) return "follow";
        if (contains(message, "остановись", "стой", "прекрати")) return "stop";
        if (contains(message, "сруби дерево", "добудь дерево", "добудь брёвна", "наруби дерева")) return "wood";
        if (contains(message, "добудь камень", "копай камень", "копай руду", "начни копать")) return "stone";
        if (contains(message, "атакуй", "защищайся", "напади на моба")) return "attack";
        if (contains(message, "исследуй", "разведай", "прогуляйся")) return "explore";
        return null;
    }

    private String response(Player sender, String message) {
        AgentMemory memory = plugin.brain().memory();
        UUID id = sender.getUniqueId();
        if (contains(message, "что мы обсуждали", "что ты помнишь", "о чём говорили", "что ты помнишь обо мне")) {
            if (contains(message, "обо мне")) return memory.playerSummary(id);
            return "Вот что я помню из нашей беседы: " + memory.conversationSummary(id, 5);
        }
        if (contains(message, "а почему", "почему так", "и что дальше", "а дальше", "что потом")) {
            String topic = memory.lastTopic(id);
            return switch (topic) {
                case "строительство" -> "Я считаю, что сначала нужно выбрать безопасное место и продумать основу дома.";
                case "добыча" -> "Сначала нужно подготовить инструмент и не копать вслепую.";
                case "бой" -> "Сначала стоит оценить угрозу и своё здоровье.";
                case "исследование" -> "Мне интересно находить новые места, но я стараюсь не терять путь назад.";
                default -> "Я помню общий ход беседы, но уточни, какую мысль продолжить.";
            };
        }
        if (contains(message, "что ты обо мне думаешь", "как ты ко мне относишься")) {
            int trust = memory.trust(id);
            if (trust >= 3) return "Ты производишь хорошее впечатление. Я начинаю тебе доверять.";
            if (trust <= -3) return "Пока я отношусь к тебе настороженно.";
            return "Я пока присматриваюсь к тебе. Доверие нужно заслужить.";
        }
        if (contains(message, "мнение", "что думаешь", "как считаешь", "твоя точка зрения"))
            return plugin.brain().opinion(message, id);
        if (contains(message, "привет", "здравствуй", "добрый день", "хай"))
            return "Привет, " + sender.getName() + "! Рад тебя видеть.";
        if (contains(message, "как дела", "как ты"))
            return "Всё нормально. Сейчас я: " + plugin.brain().state() + ". А у тебя как дела?";
        if (contains(message, "что делаешь", "чем занят"))
            return "Сейчас я: " + plugin.brain().state() + ".";
        if (contains(message, "кто ты", "ты кто"))
            return "Я Igrok. У меня есть характер, память и собственные игровые цели. Я сам решаю, соглашаться ли с просьбами.";
        if (contains(message, "помоги", "помощь"))
            return "Я могу добывать ресурсы, исследовать мир, строить и защищаться, но сам решаю, какую просьбу принять.";
        if (contains(message, "спасибо", "благодарю")) return "Всегда пожалуйста, " + sender.getName() + "!";
        if (contains(message, "пока", "до встречи")) return "До встречи, " + sender.getName() + "!";
        if (message.contains("?")) return "Интересный вопрос. " + plugin.brain().opinion(message, id);
        return "Я тебя услышал. Но это не значит, что я обязательно буду это делать.";
    }

    private void sendReply(UUID playerId, String response) {
        plugin.brain().rememberBotMessage(playerId, response);
        Bukkit.broadcast(Component.text("Igrok: " + response));
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
