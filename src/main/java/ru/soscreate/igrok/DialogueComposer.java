package ru.soscreate.igrok;

import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Builds varied, context-aware replies from intent, memory, personality and world state. */
final class DialogueComposer {
    private final IgrokPlugin plugin;
    private final Map<UUID, String> previousReplyBases = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<UUID, Integer> repeatedReplyCounts = new LinkedHashMap<>(16, 0.75F, true);

    DialogueComposer(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    String reply(Player player, String originalMessage, CognitiveNetwork.Analysis thought) {
        UUID id = player.getUniqueId();
        AgentMemory memory = plugin.brain().memory();
        LanguageProcessor.Parsed parsed = thought.parsed();
        String name = player.getName();

        String normalizedMessage = parsed.cleaned().replace('ё', 'е');
        if (contains(normalizedMessage, "что видишь", "что ты видишь", "видишь ли",
                "посмотри вокруг", "осмотрись", "какой блок", "насколько далеко",
                "что находится рядом")) {
            return avoidImmediateRepeat(id, plugin.brain().worldObservation());
        }

        String response = switch (thought.intent()) {
            case GREETING -> choose(
                    "Привет, " + name + "! Рад, что ты заглянул.",
                    "О, " + name + ", привет! Рад тебя видеть.",
                    "Здорово, " + name + "! Хорошо, что ты рядом.",
                    "Привет! Я как раз думал, чем заняться.",
                    "И тебе привет, " + name + ". Хорошего дня в мире.");
            case FAREWELL -> choose(
                    "До встречи, " + name + "! Я пока займусь своими делами.",
                    "Увидимся. Не забудь заглянуть ещё.",
                    "Пока! Надеюсь, в следующий раз у нас будет новое приключение.",
                    "Счастливо. Я запомню нашу беседу.");
            case THANKS -> choose(
                    "Всегда пожалуйста, " + name + ". Рад, что оказался полезен.",
                    "Да пустяки. Хорошим товарищам стоит помогать.",
                    "Не за что! Мне тоже было интересно.",
                    "Приятно слышать. Значит, мы неплохо сработались.");
            case IDENTITY -> choose(
                    "Я Igrok. Я живу в этом мире, исследую его, добываю ресурсы и постепенно учусь на собственных действиях.",
                    "У меня есть характер, память и навыки. Чем больше я строю, добываю и путешествую, тем увереннее справляюсь с такими задачами.",
                    "Я не просто выполняю один сценарий: сравниваю несколько целей, смотрю на обстановку и могу изменить решение. "
                            + plugin.brain().selfModelSummary(),
                    "Мой разум алгоритмический, а опыт сохраняется между перезапусками. " + plugin.brain().selfModelSummary());
            case STATUS -> choose(
                    "Сейчас я " + plugin.brain().state() + ". " + plugin.brain().decisionReason(),
                    "Моё текущее занятие: " + plugin.brain().state() + ". " + plugin.brain().decisionReason(),
                    "Я занят своим делом: " + plugin.brain().state() + ". Если обстановка изменится, пересмотрю план.",
                    "Пока всё спокойно. " + plugin.brain().state() + ". " + plugin.brain().decisionReason());
            case MEMORY -> contains(parsed.cleaned(), "обо мне", "про меня")
                    ? memory.playerSummary(id) + " " + memory.learnedBehavior(id)
                    : "Вот что осталось в памяти: " + memory.conversationSummary(id, 6);
            case LEARNING -> memory.learnedBehavior(id) + " " + plugin.brain().skillSummary() + " "
                    + choose("Я получаю опыт за реальные действия, а не просто за проведённое время.",
                    "Ошибки помогают мне осторожнее выбирать похожие маршруты в следующий раз.",
                    "Новые уровни навыков постепенно расширяют мои возможности.");
            case OPINION -> opinion(player, originalMessage);
            case HELP -> choose(
                    "Я могу искать ресурсы, готовить инструменты, исследовать местность, строить убежища и новые проекты. Ещё я запоминаю, какие действия у меня получаются лучше.",
                    "Могу заняться добычей, разведкой, едой, защитой или строительством. Назови идею — я оценю материалы, расстояние и риск.",
                    "Я умею ходить по безопасным маршрутам, добывать видимые блоки, крафтить и строить из собственных запасов. Список проектов растёт вместе с опытом.");
            case FEELING -> feeling(player, parsed);
            case BORED -> choose(
                    "Тогда можно устроить небольшое приключение: найти пещеру или новый биом.",
                    "Я бы сменил обстановку и отправился исследовать незнакомое место.",
                    "Скука — хороший повод придумать проект. Например, необычную постройку.",
                    "Можно не торопиться. Иногда интересно просто посмотреть, что происходит вокруг.");
            case AGREEMENT -> choose(
                    "Вот и я примерно так думаю. Посмотрим, подтвердит ли это наш опыт.",
                    "Хорошо, значит мы поняли друг друга.",
                    "Согласен. Но оставим место для новых идей.",
                    "Отлично. Будем придерживаться этого плана, пока он работает.");
            case DISAGREEMENT -> choose(
                    "Не обязательно соглашаться во всём. Я готов рассмотреть другие доводы.",
                    "Возможно, я упустил что-то важное. Твоя точка зрения тоже имеет значение.",
                    "Хорошо, давай сравним аргументы, а не будем спорить просто так.",
                    "Я могу ошибаться. Учту это.");
            case QUESTION -> question(player, originalMessage, thought);
            case SMALLTALK -> choose(
                    "Неплохая тема. Мне есть над чем подумать.",
                    "Интересно. Я замечаю, что в мире многое связано между собой.",
                    "Такие разговоры тоже помогают мне лучше понимать игроков.",
                    "Хорошо, я тебя слушаю.");
            case BUILD, FOLLOW, STOP, MINE_WOOD, MINE_STONE, ATTACK, EXPLORE ->
                    general(player, originalMessage, thought);
            case GENERAL -> general(player, originalMessage, thought);
        };
        return avoidImmediateRepeat(id, response);
    }

    private String avoidImmediateRepeat(UUID playerId, String response) {
        String previous = previousReplyBases.get(playerId);
        int repeat = 0;
        String base = response;
        if (base.equals(previous)) {
            repeat = repeatedReplyCounts.getOrDefault(playerId, 0) + 1;
            // Replace the whole repeated line: appending text to a repeated
            // question would still make the bot ask that same question again.
            response = switch ((repeat - 1) % 3) {
                case 0 -> "Я уже отвечал на это. Пока моя мысль не изменилась.";
                case 1 -> "Не буду повторять одно и то же. Можем перейти к другой теме.";
                default -> "Кажется, мы ходим по кругу. Давай пока займёмся чем-нибудь другим.";
            };
        }
        previousReplyBases.put(playerId, base);
        repeatedReplyCounts.put(playerId, repeat);
        while (previousReplyBases.size() > 512) {
            UUID oldest = previousReplyBases.keySet().iterator().next();
            previousReplyBases.remove(oldest);
            repeatedReplyCounts.remove(oldest);
        }
        return response;
    }

    String actionReply(String action, boolean accepted, boolean started, Player player) {
        if (!accepted) return choose(
                "Я подумал и в этот раз откажусь. У меня другие планы.",
                "Не сейчас. Я сам выбираю, чем заниматься.",
                "Я понял, чего ты хочешь, но не хочу бросать своё дело.",
                "Спасибо за предложение, но сегодня я не настроен на это.");
        if (!started) return choose(
                "Я согласился, но сейчас выполнить это не получилось. Надо оценить обстановку.",
                "Идея неплохая, однако прямо сейчас у меня не выходит начать.",
                "Я хотел помочь, но условия пока не подходят.");
        return switch (action) {
            case "build" -> choose("Договорились. Сначала проверю место и материалы; хочу, чтобы постройка была полезной, а не просто занимала землю.",
                    "Хорошая идея. Если дом уже есть, попробую придумать другой проект — например, мастерскую или смотровую площадку.",
                    "Сначала осмотрю участок, затем решу, какой проект сейчас по силам. Строить из воздуха я не умею.");
            case "follow" -> choose("Хорошо, пойду с тобой. Только предупреждай, если резко меняешь маршрут.",
                    "Я рядом. Посмотрим, куда нас приведёт дорога.",
                    "Ладно, составлю тебе компанию. Не отставай и сам.");
            case "stop" -> choose("Хорошо, остановлюсь и немного осмотрюсь.",
                    "Понял. Сделаю паузу. Мне тоже полезно передохнуть.");
            case "wood" -> choose("Поищу деревья. Запас брёвен никогда не помешает.",
                    "Отправляюсь за древесиной. Постараюсь не уходить слишком далеко.",
                    "Хорошо, займусь деревом. Потом решу, что из него сделать.");
            case "stone" -> choose("Поищу камень и руду. Надо только не забыть про безопасный путь назад.",
                    "Ладно, проверю ближайшие склоны и пещеры.",
                    "Добыча полезна, но я постараюсь не рисковать без нужды.");
            case "attack" -> choose("Разберусь с угрозой. Лучше держись чуть в стороне.",
                    "Посмотрю, кто там угрожает. Не люблю оставлять опасность за спиной.");
            case "explore" -> choose("Пойду разведаю окрестности. Возможно, найду что-нибудь интересное.",
                    "Хочу посмотреть, что находится дальше. Новые места всегда полезны.",
                    "Отправляюсь на разведку. Постараюсь запомнить дорогу обратно.");
            default -> "Хорошо, попробую.";
        };
    }

    private String opinion(Player player, String message) {
        String base = plugin.brain().opinion(message, player.getUniqueId());
        return choose("Моя точка зрения такая: ", "Я бы рассуждал вот как: ",
                "После раздумий я склоняюсь к этому: ", "Мне кажется следующее: ")
                + base + choose(" Это мой взгляд на данный момент.", " Но я готов пересмотреть мнение, если появятся новые факты.",
                        " Пока придерживаюсь этой позиции.");
    }

    private String feeling(Player player, LanguageProcessor.Parsed parsed) {
        if (parsed.sentiment() < 0) return choose(
                "Похоже, настроение не очень. Хочешь рассказать, что случилось?",
                "Звучит невесело. Может, немного отвлечёмся и займёмся чем-нибудь в мире?",
                "Понимаю. Не всегда всё идёт гладко. Я могу тебя выслушать.");
        if (parsed.sentiment() > 0) return choose(
                "Здорово! Хорошее настроение помогает даже самые сложные дела делать легче.",
                "Рад это слышать. Давай используем этот настрой для чего-нибудь интересного.",
                "Вот это уже приятные новости!");
        return choose(
                "У меня есть свои игровые цели, но я не против поговорить о том, что ты чувствуешь.",
                "Настроение бывает разным. Что повлияло на него сегодня?",
                "Я стараюсь учитывать не только слова, но и поступки. Расскажешь подробнее?");
    }

    private String question(Player player, String message, CognitiveNetwork.Analysis thought) {
        LanguageProcessor.Parsed p = thought.parsed();
        String normalized = p.cleaned().replace('ё', 'е');
        if (contains(normalized, "видишь", "видно", "вокруг", "поблизости",
                "что рядом", "какие блоки", "что перед тобой", "что передо мной",
                "какой блок", "на каком расстоянии")) {
            return plugin.brain().worldObservation();
        }
        String answer;
        if (p.has(LanguageProcessor.Concept.BUILD) || p.has(LanguageProcessor.Concept.HOUSE)) {
            answer = choose(
                    "При строительстве я бы начал с безопасного места, прочного основания и удобного входа.",
                    "Дом должен быть не только красивым. Нужны свет, защита и достаточно места.",
                    "Мне нравится сочетать несколько материалов и делать крышу, которая выделяет постройку.");
        } else if (p.has(LanguageProcessor.Concept.MINE) || p.has(LanguageProcessor.Concept.ORE)
                || p.has(LanguageProcessor.Concept.STONE)) {
            answer = choose(
                    "Для добычи важны инструменты, освещение и запасной путь наверх.",
                    "Я предпочитаю сначала осмотреть местность. Бездумно копать вниз слишком опасно.",
                    "Руды полезны, но ресурсы не стоят того, чтобы терять всё из-за спешки.");
        } else if (p.has(LanguageProcessor.Concept.COMBAT) || p.has(LanguageProcessor.Concept.MOB)) {
            answer = choose(
                    "В бою лучше оценить расстояние, здоровье и путь отхода.",
                    "Я не люблю бессмысленные драки. Сначала нужно понять, есть ли реальная угроза.",
                    "Подготовка часто важнее силы удара. Хорошее снаряжение решает многое.");
        } else if (p.has(LanguageProcessor.Concept.EXPLORE) || p.has(LanguageProcessor.Concept.JOURNEY)
                || p.has(LanguageProcessor.Concept.NATURE)) {
            answer = choose(
                    "Исследование полезно: можно найти ресурсы, деревни и красивые места.",
                    "Я бы пошёл по незнакомому маршруту, но запомнил ориентиры для возвращения.",
                    "Новые биомы часто дают идеи для будущих построек.");
        } else if (p.has(LanguageProcessor.Concept.FRIENDSHIP) || p.has(LanguageProcessor.Concept.TRUST)
                || p.has(LanguageProcessor.Concept.PLAYER)) {
            answer = playerRelationship(player);
        } else if (p.has(LanguageProcessor.Concept.FOOD) || p.has(LanguageProcessor.Concept.SURVIVAL)) {
            answer = choose(
                    "Выживание начинается с запасов еды, укрытия и понимания ближайших опасностей.",
                    "Я бы не отправлялся далеко без еды и снаряжения.",
                    "Сначала безопасность, потом уже рискованные приключения.");
        } else {
            answer = plugin.brain().opinion(message, player.getUniqueId());
        }
        return choose("Хороший вопрос. ", "Я немного подумал. ", "С моей точки зрения, ",
                "По моему опыту, ") + answer + choose(" Так я это вижу.",
                " Но многое зависит от обстоятельств.", " Эту идею можно проверить на практике.");
    }

    private String playerRelationship(Player player) {
        int trust = plugin.brain().memory().trust(player.getUniqueId());
        if (trust >= 3) return "Я считаю, что хорошая команда строится на доверии и взаимной помощи. Ты уже заслужил часть моего доверия.";
        if (trust <= -3) return "Командная игра требует доверия. Пока я отношусь к тебе осторожно, потому что некоторые поступки мне не понравились.";
        return "Доверие не появляется за секунду. Я смотрю, как игрок ведёт себя со мной и с окружающим миром.";
    }

    private String general(Player player, String message, CognitiveNetwork.Analysis thought) {
        LanguageProcessor.Parsed p = thought.parsed();
        String detail;
        if (p.has(LanguageProcessor.Concept.BUILD) || p.has(LanguageProcessor.Concept.HOUSE)) {
            detail = choose("Строительство требует подходящего места, материалов и продуманного основания.",
                    "Мне нравится, когда постройка полезна и при этом выглядит аккуратно.",
                    "Для хорошего дома важны свет, защита и удобный вход.");
        } else if (p.has(LanguageProcessor.Concept.MINE) || p.has(LanguageProcessor.Concept.WOOD)
                || p.has(LanguageProcessor.Concept.STONE) || p.has(LanguageProcessor.Concept.ORE)) {
            detail = choose("При добыче я стараюсь оценивать инструмент, запас еды и безопасный путь назад.",
                    "Ресурсы полезны, но я не хочу рисковать всем инвентарём ради нескольких блоков.",
                    "Сначала стоит понять, какие материалы действительно нужны.");
        } else if (p.has(LanguageProcessor.Concept.COMBAT) || p.has(LanguageProcessor.Concept.MOB)) {
            detail = choose("В бою я стараюсь оценивать здоровье, расстояние и возможность отступить.",
                    "Не всякого противника стоит атаковать. Иногда безопаснее обойти его.",
                    "Подготовка и подходящее снаряжение часто важнее поспешной атаки.");
        } else if (p.has(LanguageProcessor.Concept.EXPLORE) || p.has(LanguageProcessor.Concept.JOURNEY)) {
            detail = choose("Исследовать полезно, но я стараюсь запоминать ориентиры и путь назад.",
                    "Новые места могут дать ресурсы и идеи для будущих проектов.",
                    "Я предпочитаю постепенно изучать окрестности, не заходя без подготовки слишком далеко.");
        } else if (p.has(LanguageProcessor.Concept.CREATIVE) || p.has(LanguageProcessor.Concept.IDEA)) {
            detail = choose("Мне нравятся необычные идеи. Иногда стоит попробовать нестандартный подход.",
                    "Можно развить эту мысль и проверить её в мире.",
                    "Хорошая идея может стать началом большого проекта.");
        } else if (p.has(LanguageProcessor.Concept.NATURE) || p.has(LanguageProcessor.Concept.WEATHER)) {
            detail = choose("Окружающий мир постоянно меняется, и мне интересно замечать детали.",
                    "Погода и местность сильно влияют на планы. Я стараюсь учитывать обстановку.",
                    "Иногда полезно остановиться и просто осмотреться.");
        } else if (p.has(LanguageProcessor.Concept.RESOURCE)) {
            detail = choose("Запасы полезны, но важно понимать, для какой цели мы их собираем.",
                    "Я стараюсь не тратить материалы впустую. Лучше заранее придумать, что из них сделать.",
                    "Ресурсы дают свободу действий, особенно когда есть план.");
        } else if (p.has(LanguageProcessor.Concept.TRUST) || p.has(LanguageProcessor.Concept.FRIENDSHIP)) {
            detail = playerRelationship(player);
        } else {
            detail = choose("Я пытаюсь связать это с тем, что уже знаю, и сделать собственный вывод.",
                    "Любопытно. Я запомню эту мысль и посмотрю, пригодится ли она позже.",
                    "Я понял общий смысл, но хочу узнать чуть больше деталей.",
                    "У меня пока нет уверенного ответа. Лучше разобраться, чем придумывать факт.",
                    "Это можно рассмотреть с нескольких сторон. Я не обязан сразу выбирать одну.");
        }
        String follow = choose(" Это можно обдумать.", " Я запомню эту мысль.",
                " Пока не вижу причины менять свои планы.", " Эту идею можно проверить на практике.");
        if (message.length() < 10) return choose("Расскажи чуть подробнее.", "Я слушаю. Продолжай.",
                "Хм, а что ты хочешь этим сказать?");
        return choose("Понял тебя. ", "Интересно. ", "Хорошо, я об этом подумаю. ",
                "Любопытное замечание. ", "Я бы не стал делать поспешных выводов. ")
                + detail + follow;
    }

    private String choose(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private boolean contains(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }
}
