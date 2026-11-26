package ru.soscreate.igrok;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Builds varied, context-aware replies from intent, memory, personality and world state. */
final class DialogueComposer {
    private final IgrokPlugin plugin;

    DialogueComposer(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    String reply(Player player, String originalMessage, CognitiveNetwork.Analysis thought) {
        UUID id = player.getUniqueId();
        AgentMemory memory = plugin.brain().memory();
        LanguageProcessor.Parsed parsed = thought.parsed();
        String name = player.getName();

        return switch (thought.intent()) {
            case GREETING -> choose(
                    "Привет, " + name + "! Рад, что ты заглянул.",
                    "О, " + name + ", привет! Как мир?",
                    "Здорово! Я как раз думал, чем заняться.",
                    "Привет! Хорошо, что ты рядом. Есть какие-нибудь новости?",
                    "И тебе привет, " + name + ". Что интересного произошло?");
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
                    "Я Igrok. Я живу в этом мире, исследую его, добываю ресурсы и строю собственные планы.",
                    "Меня зовут Igrok. У меня есть характер, память и простая система обучения на опыте.",
                    "Я не просто табличка с командами. Я наблюдаю, выбираю цели и могу не соглашаться.",
                    "Я Igrok. Пока мой разум алгоритмический, но он умеет связывать понятия и запоминать опыт.");
            case STATUS -> choose(
                    "Сейчас я " + plugin.brain().state() + ". А у тебя как дела?",
                    "Всё спокойно. Моё текущее занятие: " + plugin.brain().state() + ".",
                    "Я в порядке. Не хочу стоять на месте, поэтому " + plugin.brain().state() + ".",
                    "Сейчас занят своим делом: " + plugin.brain().state() + ". Но могу немного поговорить.");
            case MEMORY -> contains(parsed.cleaned(), "обо мне", "про меня")
                    ? memory.playerSummary(id) + " " + memory.learnedBehavior(id)
                    : "Вот что осталось в памяти: " + memory.conversationSummary(id, 6);
            case LEARNING -> memory.learnedBehavior(id) + " " + choose(
                    "Я постепенно уточняю это по новым поступкам.",
                    "Это пока наблюдение, а не окончательный вывод.",
                    "Чем больше мы играем вместе, тем точнее становится мой профиль.");
            case OPINION -> opinion(player, originalMessage);
            case HELP -> choose(
                    "Я могу добывать дерево, камень и руду, исследовать окрестности, сражаться и строить. Но решение остаётся за мной.",
                    "Могу помочь с ресурсами, разведкой или защитой. Ещё могу просто составить компанию.",
                    "Предложи цель: добыча, строительство, путь в новое место или защита. Я оценю просьбу и решу.");
            case FEELING -> feeling(player, parsed);
            case BORED -> choose(
                    "Тогда давай устроим небольшое приключение: можно найти пещеру или новый биом.",
                    "Я бы сменил обстановку. Например, отправился бы исследовать место, где мы ещё не были.",
                    "Скука — хороший повод придумать проект. Как насчёт необычной постройки?",
                    "Можно не торопиться. Иногда интересно просто посмотреть, что происходит вокруг.");
            case AGREEMENT -> choose(
                    "Вот и я примерно так думаю. Посмотрим, подтвердит ли это наш опыт.",
                    "Хорошо, значит мы поняли друг друга.",
                    "Согласен. Но оставим место для новых идей.",
                    "Отлично. Тогда будем придерживаться этого плана, пока он работает.");
            case DISAGREEMENT -> choose(
                    "Не обязательно соглашаться во всём. Расскажи, что именно кажется тебе неверным.",
                    "Возможно, я упустил что-то важное. Какие у тебя доводы?",
                    "Хорошо, давай сравним аргументы, а не будем спорить просто так.",
                    "Я могу ошибаться. Объясни свою точку зрения.");
            case QUESTION -> question(player, originalMessage, thought);
            case SMALLTALK -> choose(
                    "Мне нравится узнавать, чем живёт мир вокруг. Расскажешь, что у тебя нового?",
                    "Хорошая тема. А что ты сам об этом думаешь?",
                    "Я слушаю. Такие разговоры тоже помогают мне лучше понимать игроков.",
                    "Интересно. Продолжай, мне любопытно.");
            case BUILD, FOLLOW, STOP, MINE_WOOD, MINE_STONE, ATTACK, EXPLORE ->
                    "Я услышал просьбу. Сейчас решу, подходит ли она моим планам.";
            case GENERAL -> general(player, originalMessage, thought);
        };
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
            case "build" -> choose("Ладно, начну с основы. Хочу, чтобы дом получился надёжным.",
                    "Мне нравится эта затея. Сначала выберу место, затем займусь стенами и крышей.",
                    "Договорились. Постараюсь сделать не просто коробку, а что-нибудь уютное.");
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
                + base + choose(" А ты как считаешь?", " Но я готов пересмотреть мнение.",
                        " Это мой взгляд на данный момент.");
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
                "По моему опыту, ") + answer + choose(" А ты что думаешь?",
                " Но многое зависит от обстоятельств.", " Можно проверить эту идею на практике.");
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
        if (p.has(LanguageProcessor.Concept.CREATIVE) || p.has(LanguageProcessor.Concept.IDEA)) {
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
        String follow = choose(" Что именно ты имеешь в виду?", " Как ты к этому пришёл?",
                " Хочешь, обсудим это подробнее?", " Может, проверим это на практике?",
                " А что ты предложил бы сделать дальше?");
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
