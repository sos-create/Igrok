package ru.soscreate.igrok;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight Russian/English lexical parser. It normalizes common inflections,
 * recognizes synonym groups and extracts intent-related signals without a model runtime.
 */
final class LanguageProcessor {
    enum Concept {
        SOCIAL, GREETING, FAREWELL, GRATITUDE, IDENTITY, STATUS, MEMORY, LEARNING,
        OPINION, HELP, BUILD, HOUSE, FOLLOW, STOP, WOOD, STONE, ORE, MINE,
        COMBAT, MOB, DANGER, EXPLORE, JOURNEY, FEELING, BOREDOM, FOOD, SURVIVAL,
        PLAYER, FRIENDSHIP, TRUST, AGREEMENT, DISAGREEMENT, QUESTION, IDEA,
        NATURE, CAVE, WEATHER, CREATIVE, RESOURCE, WORK, TIME, LOCATION
    }

    static final class Parsed {
        private final EnumMap<Concept, Double> signals;
        private final Set<String> words;
        private final boolean question;
        private final boolean command;
        private final boolean negated;
        private final int sentiment;
        private final String cleaned;

        Parsed(EnumMap<Concept, Double> signals, Set<String> words, boolean question,
               boolean command, boolean negated, int sentiment, String cleaned) {
            this.signals = signals;
            this.words = words;
            this.question = question;
            this.command = command;
            this.negated = negated;
            this.sentiment = sentiment;
            this.cleaned = cleaned;
        }

        double signal(Concept concept) { return signals.getOrDefault(concept, 0.0); }
        boolean has(Concept concept) { return signal(concept) >= 0.28; }
        boolean question() { return question; }
        boolean command() { return command; }
        boolean negated() { return negated; }
        int sentiment() { return sentiment; }
        String cleaned() { return cleaned; }
        Set<String> words() { return Set.copyOf(words); }
        int[] activeConcepts() {
            return signals.entrySet().stream().filter(e -> e.getValue() >= 0.18)
                    .mapToInt(e -> e.getKey().ordinal()).toArray();
        }
    }

    private static final Map<Concept, String[]> LEXICON = new EnumMap<>(Concept.class);
    private static final String[] QUESTION_WORDS = {
            "как", "что", "кто", "где", "куда", "откуда", "когда", "почему", "зачем",
            "сколько", "какой", "какая", "какие", "можно", "ли", "чем", "чего", "whose", "why", "what", "where", "when", "how"
    };
    private static final String[] COMMAND_ROOTS = {
            "построй", "построи", "сделай", "сдела", "поставь", "постав", "добудь", "добуд",
            "наруби", "сруби", "сруб", "копай", "копа", "атакуй", "атак", "напади", "напад",
            "иди", "следуй", "след", "остановись", "останов", "стой", "исследуй", "исслед",
            "разведай", "развед", "принеси", "принес", "найди", "найд", "дай", "возьми",
            "помоги", "расскажи", "скажи", "поговори", "пойдём", "пойдем", "сделай", "mine",
            "build", "follow", "attack", "explore", "stop", "bring", "find", "get"
    };
    private static final String[] POSITIVE = {
            "спасибо", "благодар", "круто", "класс", "молодец", "хорош", "отлич", "люблю",
            "нрав", "соглас", "верно", "помог", "друг", "great", "thanks", "good", "nice", "love"
    };
    private static final String[] NEGATIVE = {
            "идиот", "туп", "дурак", "бесполез", "ненавиж", "плохо", "ужас", "отстой",
            "злой", "разочар", "неправ", "ошиб", "bad", "stupid", "hate", "wrong"
    };

    static {
        put(Concept.SOCIAL, "привет", "здравств", "хай", "hello", "hi", "обща", "поговор", "бесед", "чат");
        put(Concept.GREETING, "привет", "здравств", "хай", "доброеутро", "добрыйдень", "hello", "hi", "hey");
        put(Concept.FAREWELL, "пока", "досвидан", "увидим", "встреч", "прощай", "bye", "goodbye", "later");
        put(Concept.GRATITUDE, "спасибо", "благодар", "мерси", "thanks", "thank", "appreciate");
        put(Concept.IDENTITY, "кто", "имя", "зовут", "такое", "ты", "представ", "name", "who");
        put(Concept.STATUS, "дела", "состоя", "занят", "делаешь", "работа", "сейчас", "state", "doing");
        put(Concept.MEMORY, "помн", "вспом", "обсужда", "говорили", "вчера", "прошлый", "истори", "memory", "remember");
        put(Concept.LEARNING, "науч", "узна", "замет", "обуч", "стиль", "привыч", "повед", "learn", "noticed");
        put(Concept.OPINION, "дума", "счита", "мнен", "полага", "точказрен", "мнение", "идея", "opinion", "think");
        put(Concept.HELP, "помог", "помощ", "поддерж", "спас", "help", "assist");
        put(Concept.BUILD, "стро", "созда", "возвод", "постро", "став", "build", "craft", "place");
        put(Concept.HOUSE, "дом", "домик", "здани", "хижин", "баз", "крыша", "стен", "house", "base");
        put(Concept.FOLLOW, "след", "рядом", "вместе", "сопровож", "за мной", "follow", "come");
        put(Concept.STOP, "останов", "хватит", "стой", "прекрат", "перестан", "stop", "halt");
        put(Concept.WOOD, "дерев", "бревн", "дос", "ствол", "лес", "wood", "log", "tree");
        put(Concept.STONE, "кам", "булыж", "гранит", "диорит", "андезит", "stone", "cobble");
        put(Concept.ORE, "руд", "желез", "угол", "мед", "алмаз", "золот", "лазур", "редстоун", "ore", "diamond", "iron");
        put(Concept.MINE, "коп", "шахт", "добы", "ресурс", "кирк", "mine", "mining");
        put(Concept.COMBAT, "атак", "драк", "сраж", "битв", "убей", "убива", "меч", "оруж", "бой", "fight", "attack");
        put(Concept.MOB, "моб", "зомб", "скелет", "крипер", "паук", "эндер", "монстр", "животн", "mob", "creeper");
        put(Concept.DANGER, "опас", "угроз", "страш", "риск", "урон", "умер", "смерт", "danger", "risk");
        put(Concept.EXPLORE, "исслед", "развед", "осмотр", "посмотр", "изуч", "найти", "discover", "explore");
        put(Concept.JOURNEY, "путеш", "пут", "прогул", "дорог", "пойд", "путешеств", "journey", "trip");
        put(Concept.FEELING, "чувств", "груст", "рад", "скуч", "злюсь", "настроен", "счаст", "feel", "sad", "happy");
        put(Concept.BOREDOM, "скуч", "нечегодел", "надоел", "однообраз", "bored", "boring");
        put(Concept.FOOD, "ед", "пищ", "голод", "хлеб", "мяс", "морков", "food", "hungry");
        put(Concept.SURVIVAL, "выжив", "здоров", "серд", "брон", "безопас", "жизн", "survive", "health");
        put(Concept.PLAYER, "игрок", "человек", "ты", "теб", "тво", "я", "мной", "player", "you");
        put(Concept.FRIENDSHIP, "друг", "друж", "команд", "товарищ", "вместе", "friend", "team");
        put(Concept.TRUST, "довер", "чест", "обещ", "предал", "вер", "trust", "honest");
        put(Concept.AGREEMENT, "да", "соглас", "верно", "точно", "ок", "ладно", "okay", "yes", "agree");
        put(Concept.DISAGREEMENT, "нет", "неправ", "несоглас", "против", "отказ", "no", "disagree");
        put(Concept.QUESTION, "почему", "зачем", "как", "что", "кто", "где", "когда", "сколько", "можно", "ли", "question");
        put(Concept.IDEA, "иде", "предлож", "вариант", "план", "решен", "придум", "idea", "plan");
        put(Concept.NATURE, "биом", "дерев", "лес", "река", "озер", "гора", "пустын", "nature", "forest");
        put(Concept.CAVE, "пещер", "туннел", "подзем", "шахт", " cave", "cave", "tunnel");
        put(Concept.WEATHER, "погод", "дожд", "снег", "гроз", "ноч", "день", "weather", "rain");
        put(Concept.CREATIVE, "красив", "дизайн", "стиль", "украс", "цвет", "твор", "creative", "design");
        put(Concept.RESOURCE, "ресурс", "материал", "предмет", "вещ", "инвентар", "сундук", "resource", "item");
        put(Concept.WORK, "работ", "задач", "дел", "занят", "план", "work", "task");
        put(Concept.TIME, "сегодн", "завтр", "вчера", "потом", "скоро", "позже", "time", "today");
        put(Concept.LOCATION, "мест", "здесь", "там", "координат", "точк", "рядом", "where", "location");
    }

    private static void put(Concept concept, String... roots) { LEXICON.put(concept, roots); }

    Parsed parse(String source) {
        String cleaned = source == null ? "" : source.toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("(?i)https?://\\\\S+", " ");
        String[] raw = cleaned.split("[^\\\\p{L}\\\\p{N}_@]+");
        Set<String> words = new HashSet<>();
        for (String word : raw) if (!word.isBlank()) words.add(stem(word));

        EnumMap<Concept, Double> signals = new EnumMap<>(Concept.class);
        for (Map.Entry<Concept, String[]> entry : LEXICON.entrySet()) {
            double value = 0;
            for (String root : entry.getValue()) {
                String normalized = stem(root.trim());
                if (cleaned.replace(" ", "").contains(root.replace(" ", ""))) value += 0.36;
                for (String word : words) {
                    if (word.equals(normalized)) value += 0.48;
                    else if (word.length() >= 4 && (word.startsWith(normalized) || normalized.startsWith(word))) value += 0.24;
                }
            }
            if (value > 0) signals.put(entry.getKey(), Math.min(1.0, value));
        }

        boolean question = cleaned.contains("?") || containsAny(words, QUESTION_WORDS);
        boolean command = false;
        for (String word : raw) {
            String w = word.trim();
            for (String root : COMMAND_ROOTS) {
                String normalizedRoot = stem(root);
                if (!w.isBlank() && (w.equals(root) || w.startsWith(normalizedRoot) && normalizedRoot.length() >= 4
                        && (w.endsWith("и") || w.endsWith("ите") || w.endsWith("й") || w.endsWith("йте")
                        || w.endsWith("ь") || w.equals(normalizedRoot)))) {
                    command = true;
                    break;
                }
            }
            if (command) break;
        }
        boolean negated = containsAny(words, new String[]{"не", "нет", "нельзя", "никогда", "против"});
        int sentiment = 0;
        for (String token : words) {
            if (matches(token, POSITIVE)) sentiment++;
            if (matches(token, NEGATIVE)) sentiment--;
        }
        sentiment = Math.max(-3, Math.min(3, sentiment));
        return new Parsed(signals, words, question, command, negated, sentiment, cleaned);
    }

    private static boolean containsAny(Set<String> words, String[] candidates) {
        for (String candidate : candidates) {
            String normalized = stem(candidate);
            for (String word : words) {
                if (word.equals(normalized) || (normalized.length() >= 4 && word.startsWith(normalized))) return true;
            }
        }
        return false;
    }

    private static boolean matches(String token, String[] roots) {
        for (String root : roots) {
            String normalized = stem(root);
            if (token.equals(normalized) || token.startsWith(normalized)) return true;
        }
        return false;
    }

    static String stem(String word) {
        String value = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (value.length() <= 4) return value;
        String[] suffixes = {
                "иями", "ями", "ами", "ого", "ему", "ыми", "ими", "аться", "яться",
                "ировать", "ировать", "ение", "ения", "иями", "ского", "скому",
                "ать", "ять", "ить", "еть", "уть", "ешь", "ишь", "ете", "ите",
                "ать", "ять", "ешь", "ишь", "ого", "его", "ому", "ему", "ыми",
                "ими", "ами", "ями", "ов", "ев", "ей", "ам", "ям", "ах", "ях",
                "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие", "ую",
                "юю", "а", "я", "ы", "и", "у", "ю", "е"
        };
        for (String suffix : suffixes) {
            if (value.length() - suffix.length() >= 3 && value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return value;
    }
}
