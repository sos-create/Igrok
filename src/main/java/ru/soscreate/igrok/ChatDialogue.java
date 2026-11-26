package ru.soscreate.igrok;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

final class ChatDialogue implements Listener {
    private final IgrokPlugin plugin;
    private final Map<UUID, Long> lastReply = new ConcurrentHashMap<>();

    ChatDialogue(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        UUID senderId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> processMessage(senderId, raw));
    }

    private void processMessage(UUID senderId, String rawMessage) {
        if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("chat.enabled", true)
                || !plugin.controller().isSpawned() || rawMessage == null || rawMessage.isBlank()) return;
        Player sender = Bukkit.getPlayer(senderId);
        Player bot = plugin.controller().bukkitPlayer();
        if (sender == null || bot == null || sender.getWorld() != bot.getWorld()) return;

        double radius = Math.max(3.0, plugin.getConfig().getDouble("chat.listen-radius", 12.0));
        if (sender.getLocation().distanceSquared(bot.getLocation()) > radius * radius) return;

        String message = rawMessage.toLowerCase(Locale.ROOT);
        String configuredName = plugin.getConfig().getString("bot-name", "Igrok");
        boolean addressed = mentionsBot(message, configuredName);
        // A short follow-up is accepted only during a recent dialogue with this player.
        boolean continuation = plugin.brain().memory().isConversationActive(senderId, 90_000L)
                && isConversationalFollowUp(message);
        if (!addressed && !continuation) return;

        long now = System.currentTimeMillis();
        long cooldown = Math.max(1L, plugin.getConfig().getLong("chat.cooldown-seconds", 2)) * 1000L;
        if (now - lastReply.getOrDefault(senderId, 0L) < cooldown) return;
        lastReply.put(senderId, now);

        plugin.brain().onPlayerMessage(sender, rawMessage);
        String action = requestedAction(message);
        if (action != null) {
            boolean permitted = switch (action) {
                case "build" -> sender.hasPermission("igrok.build")
                        && plugin.getConfig().getBoolean("building.chat-trigger", true);
                default -> sender.hasPermission("igrok.control");
            };
            if (!permitted) {
                sendReply(senderId, choose("Я услышал просьбу, но у тебя нет разрешения на это.",
                        "Понял, чего ты хочешь, но сервер не разрешает мне выполнять эту просьбу."));
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

            String reply = switch (action) {
                case "build" -> plugin.houseBuilder().start(sender.getLocation(), sender)
                        ? choose("Ладно, я решил согласиться. Попробую построить здесь дом.",
                                 "Хорошо, идея мне нравится. Сначала выберу место и начну строить.")
                        : "Я согласился, но сейчас не могу начать строительство.";
                case "follow" -> { plugin.brain().follow(sender); yield "Хорошо, пойду рядом. Только не убегай слишком далеко."; }
                case "stop" -> { plugin.brain().stop(); yield "Ладно, передохну. Посмотрим, что будет дальше."; }
                case "wood" -> plugin.brain().requestMining("wood")
                        ? "Решил немного добыть дерева. Оно всегда пригодится." : "Сейчас не могу начать добычу.";
                case "stone" -> plugin.brain().requestMining("stone")
                        ? "Поищу камень и руду. Надеюсь, найду что-нибудь полезное." : "Сейчас не могу начать добычу.";
                case "attack" -> plugin.brain().attackNearest()
                        ? "Разберусь с ближайшей угрозой. Держись на безопасном расстоянии." : "Поблизости нет подходящей цели.";
                case "explore" -> { plugin.brain().exploreNow(); yield "Хочу осмотреть окрестности. Вдруг найду что-нибудь интересное."; }
                default -> composeReply(sender, message);
            };
            sendReply(senderId, reply);
            return;
        }
        sendReply(senderId, composeReply(sender, message));
    }

    private boolean mentionsBot(String message, String configuredName) {
        return hasWord(message, configuredName.toLowerCase(Locale.ROOT))
                || hasWord(message, "игрок") || message.contains("@игрок");
    }

    private boolean hasWord(String text, String word) {
        if (word == null || word.isBlank()) return false;
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(word)
                + "(?![\\p{L}\\p{N}_])").matcher(text).find();
    }

    private boolean isConversationalFollowUp(String message) {
        if (message.length() > 120) return false;
        return message.contains("?") || contains(message, "а ты", "почему", "зачем", "и что",
                "понятно", "ясно", "да", "нет", "согласен", "не согласен", "расскажи",
                "а если", "что насчёт", "спасибо", "круто", "хорошо");
    }

    private String requestedAction(String m) {
        if (contains(m, "поставь тнт", "поставь динамит", "подожги тнт", "взорви", "взорвать", "активируй тнт")) return "tnt";
        if (contains(m, "построй дом", "построй мне дом", "построй домик", "строй дом")) return "build";
        if (contains(m, "следуй за мной", "иди за мной", "держись рядом", "пойдём со мной")) return "follow";
        if (contains(m, "остановись", "стой", "прекрати", "хватит")) return "stop";
        if (contains(m, "сруби дерево", "добудь дерево", "добудь брёвна", "наруби дерева")) return "wood";
        if (contains(m, "добудь камень", "копай камень", "копай руду", "начни копать")) return "stone";
        if (contains(m, "атакуй", "защищайся", "напади на моба")) return "attack";
        if (contains(m, "исследуй", "разведай", "прогуляйся")) return "explore";
        return null;
    }

    private String composeReply(Player sender, String message) {
        AgentMemory memory = plugin.brain().memory();
        UUID id = sender.getUniqueId();
        if (contains(message, "что мы обсуждали", "что ты помнишь", "о чём говорили", "что ты помнишь обо мне"))
            return contains(message, "обо мне") ? memory.playerSummary(id) + " " + memory.learnedBehavior(id)
                    : "Вот что я помню: " + memory.conversationSummary(id, 5);
        if (contains(message, "чему ты научился", "что ты узнал", "мой стиль игры", "что ты заметил"))
            return memory.learnedBehavior(id);
        if (contains(message, "что ты обо мне думаешь", "как ты ко мне относишься")) {
            int trust = memory.trust(id);
            return trust >= 3 ? choose("Ты производишь хорошее впечатление. Я начинаю тебе доверять.",
                    "Мне нравится с тобой играть. По поступкам видно, что на тебя можно положиться.")
                    : trust <= -3 ? "Пока я отношусь к тебе настороженно. Мне нужно время."
                    : "Я пока присматриваюсь к тебе. Доверие складывается из поступков.";
        }
        if (contains(message, "мнение", "что думаешь", "как считаешь", "твоя точка зрения"))
            return plugin.brain().opinion(message, id);
        if (contains(message, "привет", "здравствуй", "добрый день", "хай"))
            return choose("Привет, " + sender.getName() + "! Рад тебя видеть.",
                    "О, " + sender.getName() + "! Здорово, что ты здесь.", "Привет! Чем займёмся сегодня?");
        if (contains(message, "как дела", "как ты"))
            return choose("У меня всё неплохо. Сейчас я " + plugin.brain().state() + ". А у тебя как?",
                    "Жив, здоров и не собираюсь скучать. Сейчас я " + plugin.brain().state() + ".");
        if (contains(message, "что делаешь", "чем занят"))
            return "Сейчас я " + plugin.brain().state() + ". " + choose("А ты чем занят?", "Может, придумаем что-нибудь вместе?");
        if (contains(message, "кто ты", "ты кто"))
            return "Я Igrok. У меня есть характер, память и собственные игровые цели. Я наблюдаю за миром, учусь на поступках игроков и сам решаю, что делать.";
        if (contains(message, "помоги", "помощь"))
            return "Я умею добывать ресурсы, исследовать мир, строить и защищаться. Но у меня есть собственные планы, поэтому я могу и отказаться.";
        if (contains(message, "спасибо", "благодарю"))
            return choose("Всегда пожалуйста, " + sender.getName() + "!", "Не за что! Рад, что пригодился.", "Да пустяки. Обращайся.");
        if (contains(message, "пока", "до встречи"))
            return choose("До встречи, " + sender.getName() + "! Не пропадай.", "Увидимся! Я буду здесь, когда вернёшься.");
        if (contains(message, "скучно", "нечего делать"))
            return choose("Можем отправиться исследовать окрестности.", "Я бы нашёл интересную пещеру или место для постройки.", "Давай придумаем небольшую цель на сегодня.");
        if (message.contains("?")) return composeQuestion(sender, message);
        return composeStatement(sender, message);
    }

    private String composeQuestion(Player sender, String message) {
        String opinion = plugin.brain().opinion(message, sender.getUniqueId());
        String[] openings = {"Хм, хороший вопрос. ", "Дай подумать. ", "Я бы сказал так: ",
                "У меня есть соображение. ", "Если рассуждать по моему опыту, "};
        String[] endings = {" А ты как считаешь?", " Но я могу изменить мнение, если увижу новые доказательства.",
                " Впрочем, многое зависит от ситуации.", " Я бы проверил это на практике."};
        return pick(openings) + opinion + pick(endings);
    }

    private String composeStatement(Player sender, String message) {
        String[] acknowledgements = {"Понял тебя.", "Интересно, я запомню это.", "Хорошо, принял к сведению.",
                "Любопытная мысль.", "Я об этом раньше не задумывался."};
        String[] reflections = {"Мне нравится узнавать, как думают другие игроки.",
                "Я стараюсь делать собственные выводы, а не просто повторять чужие слова.",
                "Посмотрим, подтвердится ли это в игре.", "Мой опыт постепенно складывается из таких разговоров."};
        if (message.length() < 18) return pick("Хм, расскажи чуть подробнее.", "И что ты об этом думаешь?", "Понял. И что будем делать дальше?");
        return pick(acknowledgements) + " " + pick(reflections) + " " + pick("Как ты к этому пришёл?", "Что думаешь об этом сам?", "Может, проверим это в нашем мире?");
    }

    private String pick(String... choices) {
        return choices[ThreadLocalRandom.current().nextInt(choices.length)];
    }

    private String choose(String... choices) { return pick(choices); }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private void sendReply(UUID playerId, String response) {
        plugin.brain().rememberBotMessage(playerId, response);
        Bukkit.broadcast(Component.text("Igrok: " + response));
    }
}
