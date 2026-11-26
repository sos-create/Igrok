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
        boolean addressed = message.contains(botName) || message.contains("игрок");
        double radius = Math.max(3.0, plugin.getConfig().getDouble("chat.listen-radius", 10.0));
        boolean nearby = sender.getWorld() == bot.getWorld()
                && sender.getLocation().distanceSquared(bot.getLocation()) <= radius * radius;
        boolean ongoingConversation = nearby
                && plugin.brain().memory().isConversationActive(senderId, 120_000L);

        if (!addressed && !ongoingConversation) return;

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("chat.cooldown-seconds", 3) * 1000L;
        long previous = lastReply.getOrDefault(senderId, 0L);
        if (now - previous < cooldown) return;
        lastReply.put(senderId, now);

        plugin.brain().onPlayerMessage(sender, rawMessage);
        String response = null;

        if (sender.hasPermission("igrok.build")
                && plugin.getConfig().getBoolean("building.chat-trigger", true)
                && contains(message, "построй дом", "построй мне дом", "построй домик", "строй дом")) {
            if (plugin.houseBuilder().start(sender.getLocation(), sender)) {
                response = "Хорошо, подберу место и займусь домом.";
            } else {
                response = "Не получилось начать строительство. Возможно, нет подходящей площадки или я уже занят.";
            }
        } else if (sender.hasPermission("igrok.control")) {
            if (contains(message, "следуй за мной", "иди за мной", "держись рядом")) {
                plugin.brain().follow(sender);
                response = "Хорошо, буду держаться рядом с тобой.";
            } else if (contains(message, "остановись", "стой", "прекрати")) {
                plugin.brain().stop();
                response = "Ладно, остановлюсь.";
            } else if (contains(message, "сруби дерево", "добудь дерево", "добудь брёвна", "наруби дерева")) {
                plugin.brain().requestMining("wood");
                response = "Поищу дерево и добуду немного брёвен.";
            } else if (contains(message, "добудь камень", "копай камень", "копай руду", "начни копать")) {
                plugin.brain().requestMining("stone");
                response = "Поищу камень и подходящую руду.";
            } else if (contains(message, "атакуй", "защищайся", "напади на моба")) {
                response = plugin.brain().attackNearest()
                        ? "Вижу угрозу. Попробую с ней справиться."
                        : "Поблизости не вижу подходящих враждебных мобов.";
            } else if (contains(message, "исследуй", "разведай", "прогуляйся")) {
                plugin.brain().exploreNow();
                response = "Хорошо, осмотрю окрестности.";
            }
        }

        if (response == null) response = response(sender, message);
        sendReply(senderId, sender.getName(), response);
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
                case "строительство" -> "Мы говорили о строительстве. Я считаю, что сначала нужно выбрать безопасное место и продумать основу дома.";
                case "добыча" -> "Мы говорили о ресурсах. Я предпочитаю сначала подготовить инструмент и не копать вслепую.";
                case "бой" -> "Мы говорили о сражениях. Я считаю, что сначала нужно оценить угрозу и своё здоровье.";
                case "исследование" -> "Мы говорили об исследовании. Мне интересно находить новые места, но я стараюсь не терять безопасный путь назад.";
                default -> "Я помню общий ход нашего разговора, но уточни, какую именно мысль ты хочешь продолжить.";
            };
        }
        if (contains(message, "что ты обо мне думаешь", "как ты ко мне относишься")) {
            int trust = memory.trust(id);
            if (trust >= 3) return "Ты производишь хорошее впечатление. Я уже начинаю тебе доверять.";
            if (trust <= -3) return "Пока я отношусь к тебе настороженно. Надеюсь, со временем мы лучше поладим.";
            return "Я пока присматриваюсь к тебе. Мы ещё можем узнать друг друга лучше.";
        }
        if (contains(message, "мнение", "что думаешь", "как считаешь", "твоя точка зрения")) {
            return plugin.brain().opinion(message, id);
        }
        if (contains(message, "привет", "здравствуй", "добрый день", "хай")) {
            return "Привет, " + sender.getName() + "! Рад снова тебя видеть.";
        }
        if (contains(message, "как дела", "как ты")) {
            return "Всё нормально. Сейчас я: " + plugin.brain().state() + ". А у тебя как дела?";
        }
        if (contains(message, "что делаешь", "чем занят")) {
            return "Сейчас я: " + plugin.brain().state() + ". А ты чем занимаешься?";
        }
        if (contains(message, "кто ты", "ты кто")) {
            return "Я Igrok. У меня есть характер, память о наших беседах и собственные игровые цели.";
        }
        if (contains(message, "помоги", "помощь")) {
            return "Могу следовать за тобой, исследовать местность, добывать дерево и камень, защищаться от мобов и строить.";
        }
        if (contains(message, "спасибо", "благодарю")) return "Всегда пожалуйста, " + sender.getName() + "!";
        if (contains(message, "пока", "до встречи")) return "До встречи, " + sender.getName() + "! Надеюсь, ещё поговорим.";
        if (message.contains("?")) {
            return "Интересный вопрос. Я думаю об этом так: " + plugin.brain().opinion(message, id)
                    + " А ты сам как считаешь?";
        }
        return "Я тебя услышал, " + sender.getName() + ". Сейчас я: " + plugin.brain().state()
                + ". Расскажи, что ты об этом думаешь.";
    }

    private void sendReply(UUID playerId, String playerName, String response) {
        plugin.brain().rememberBotMessage(playerId, response);
        Bukkit.broadcast(Component.text("Igrok: " + response));
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
