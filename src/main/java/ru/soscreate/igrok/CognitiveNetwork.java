package ru.soscreate.igrok;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small neural-inspired associative network. Semantic features excite concept
 * neurons; two sparse propagation passes activate intent neurons. Hebbian
 * updates adapt links from concepts to intents and between co-occurring ideas.
 * It is intentionally event-driven: it runs for chat, never every game tick.
 */
final class CognitiveNetwork {
    enum Intent {
        GREETING, FAREWELL, THANKS, IDENTITY, STATUS, MEMORY, LEARNING, OPINION,
        HELP, BUILD, FOLLOW, STOP, MINE_WOOD, MINE_STONE, ATTACK, EXPLORE,
        FEELING, BORED, AGREEMENT, DISAGREEMENT, QUESTION, SMALLTALK, GENERAL
    }

    static final class Analysis {
        private final LanguageProcessor.Parsed parsed;
        private final Intent intent;
        private final String action;
        private final double confidence;
        private final double[] activations;

        Analysis(LanguageProcessor.Parsed parsed, Intent intent, String action,
                 double confidence, double[] activations) {
            this.parsed = parsed;
            this.intent = intent;
            this.action = action;
            this.confidence = confidence;
            this.activations = activations;
        }

        Intent intent() { return intent; }
        String action() { return action; }
        double confidence() { return confidence; }
        int sentiment() { return parsed.sentiment(); }
        boolean question() { return parsed.question(); }
        boolean negated() { return parsed.negated(); }
        String cleaned() { return parsed.cleaned(); }
        LanguageProcessor.Parsed parsed() { return parsed; }
    }

    private static final int CONCEPTS = LanguageProcessor.Concept.values().length;
    private static final int INTENTS = Intent.values().length;
    private static final int NODES = CONCEPTS + INTENTS;
    private static final double MAX_WEIGHT = 1.25;
    private final LanguageProcessor language = new LanguageProcessor();
    private final double[][] synapses = new double[NODES][NODES];
    private final File storage;
    private boolean dirty;

    CognitiveNetwork(File storage) {
        this.storage = storage;
        seed();
        load();
    }

    Analysis think(String message, String rememberedTopic, int trust) {
        return think(message, rememberedTopic, trust, null);
    }

    Analysis think(String message, String rememberedTopic, int trust, int[] playerBehavior) {
        LanguageProcessor.Parsed parsed = language.parse(message);
        double[] activation = new double[NODES];

        for (LanguageProcessor.Concept concept : LanguageProcessor.Concept.values()) {
            activation[concept.ordinal()] = parsed.signal(concept);
        }
        if (trust >= 3) activation[LanguageProcessor.Concept.FRIENDSHIP.ordinal()] += 0.22;
        if (trust <= -3) activation[LanguageProcessor.Concept.DANGER.ordinal()] += 0.18;
        boostRememberedTopic(activation, rememberedTopic);
        boostPlayerBehavior(activation, playerBehavior);

        // Two shallow spreading-activation passes approximate association and context.
        for (int pass = 0; pass < 2; pass++) {
            double[] next = activation.clone();
            for (int from = 0; from < NODES; from++) {
                double source = activation[from];
                if (source < 0.08) continue;
                for (int to = 0; to < NODES; to++) {
                    double weight = synapses[from][to];
                    if (weight <= 0.01) continue;
                    next[to] += source * weight * (pass == 0 ? 0.42 : 0.28);
                }
            }
            for (int i = 0; i < NODES; i++) activation[i] = Math.min(1.6, next[i] * (i < CONCEPTS ? 0.82 : 0.94));
        }

        String action = requestedAction(parsed);
        Intent winner = actionIntent(action);
        double best = winner == null ? -1 : 1.35;
        if (winner == null) {
            for (Intent intent : Intent.values()) {
                double score = activation[CONCEPTS + intent.ordinal()];
                score += directBoost(parsed, intent);
                if (score > best) {
                    best = score;
                    winner = intent;
                }
            }
        }
        if (winner == null || best < 0.20) winner = Intent.GENERAL;
        double confidence = Math.max(0.0, Math.min(1.0, best / 1.55));
        return new Analysis(parsed, winner, action, confidence, activation);
    }


    private void boostPlayerBehavior(double[] activation, int[] counts) {
        if (counts == null || counts.length < 5) return;
        long total = 0;
        for (int count : counts) total += Math.max(0, count);
        if (total == 0) return;
        LanguageProcessor.Concept[] related = {
                LanguageProcessor.Concept.BUILD, LanguageProcessor.Concept.MINE,
                LanguageProcessor.Concept.COMBAT, LanguageProcessor.Concept.EXPLORE,
                LanguageProcessor.Concept.SOCIAL
        };
        for (int i = 0; i < related.length; i++) {
            double preference = Math.max(0, counts[i]) / (double) total;
            activation[related[i].ordinal()] += preference * 0.24;
        }
    }

    private void boostRememberedTopic(double[] activation, String topic) {
        if (topic == null) return;
        LanguageProcessor.Concept concept = switch (topic) {
            case "строительство" -> LanguageProcessor.Concept.BUILD;
            case "добыча" -> LanguageProcessor.Concept.MINE;
            case "бой" -> LanguageProcessor.Concept.COMBAT;
            case "исследование" -> LanguageProcessor.Concept.EXPLORE;
            case "общение" -> LanguageProcessor.Concept.SOCIAL;
            default -> null;
        };
        if (concept != null) activation[concept.ordinal()] += 0.08;
    }

    private double directBoost(LanguageProcessor.Parsed p, Intent intent) {
        return switch (intent) {
            case GREETING -> p.signal(LanguageProcessor.Concept.GREETING) * 0.74;
            case FAREWELL -> p.signal(LanguageProcessor.Concept.FAREWELL) * 0.80;
            case THANKS -> p.signal(LanguageProcessor.Concept.GRATITUDE) * 0.78;
            case IDENTITY -> p.signal(LanguageProcessor.Concept.IDENTITY) * 0.64;
            case STATUS -> p.signal(LanguageProcessor.Concept.STATUS) * 0.66;
            case MEMORY -> p.signal(LanguageProcessor.Concept.MEMORY) * 0.76;
            case LEARNING -> p.signal(LanguageProcessor.Concept.LEARNING) * 0.78;
            case OPINION -> p.signal(LanguageProcessor.Concept.OPINION) * 0.75;
            case HELP -> p.signal(LanguageProcessor.Concept.HELP) * 0.72;
            case BUILD -> p.signal(LanguageProcessor.Concept.BUILD) * 0.55;
            case FOLLOW -> p.signal(LanguageProcessor.Concept.FOLLOW) * 0.60;
            case STOP -> p.signal(LanguageProcessor.Concept.STOP) * 0.72;
            case MINE_WOOD -> p.signal(LanguageProcessor.Concept.WOOD) * 0.54;
            case MINE_STONE -> (p.signal(LanguageProcessor.Concept.STONE) + p.signal(LanguageProcessor.Concept.ORE)) * 0.35;
            case ATTACK -> (p.signal(LanguageProcessor.Concept.COMBAT) + p.signal(LanguageProcessor.Concept.MOB)) * 0.42;
            case EXPLORE -> (p.signal(LanguageProcessor.Concept.EXPLORE) + p.signal(LanguageProcessor.Concept.JOURNEY)) * 0.38;
            case FEELING -> p.signal(LanguageProcessor.Concept.FEELING) * 0.58;
            case BORED -> p.signal(LanguageProcessor.Concept.BOREDOM) * 0.70;
            case AGREEMENT -> p.signal(LanguageProcessor.Concept.AGREEMENT) * 0.44;
            case DISAGREEMENT -> p.signal(LanguageProcessor.Concept.DISAGREEMENT) * 0.52;
            case QUESTION -> p.question() ? 0.34 : 0.0;
            case SMALLTALK -> p.signal(LanguageProcessor.Concept.SOCIAL) * 0.33;
            case GENERAL -> 0.03;
        };
    }

    private String requestedAction(LanguageProcessor.Parsed p) {
        String text = p.cleaned().replace("ё", "е");
        boolean explosiveRequest = containsAny(text, "взорви", "взорвать", "подожги", "запали")
                || (containsAny(text, "тнт", "динамит")
                && containsAny(text, "постав", "подож", "запал", "взорв", "активир"));
        if (explosiveRequest) return "tnt";
        if (p.negated() && !text.contains("не мог бы") && !text.contains("не могла бы")) return null;
        boolean explanatoryRequest = containsAny(text, "объясни", "расскажи", "научи", "покажи", "как сделать", "как построить");
        if (explanatoryRequest) return null;
        boolean politeRequest = containsAny(text, "можешь", "мог бы", "могла бы", "пожалуйста",
                "давай", "не мог бы", "не могла бы", "хочу чтобы ты", "мне нужно чтобы ты",
                "could you", "please");
        boolean imperative = p.command() || politeRequest;
        if (!imperative) return null;

        if (containsAny(text, "постро", "дом", "строй", "build", "house")) return "build";
        // Clear "come here" variants must resume the bot after an explicit stop.
        if (containsAny(text, "след", "иди за", "иди ко мне", "иди сюда", "пойдем", "пойдём",
                "подойди", "подойти", "подходи", "приходи", "ко мне", "сюда", "рядом",
                "follow", "come with", "come here", "come to me", "approach")) return "follow";
        if (containsAny(text, "останов", "стой", "прекрат", "stop", "halt")) return "stop";
        if (containsAny(text, "атак", "напад", "защит", "убей", "attack", "fight")) return "attack";
        if (containsAny(text, "исслед", "развед", "прогуля", "осмотр", "explore")) return "explore";
        if (containsAny(text, "дерев", "бревн", "лес", "wood", "tree", "log")
                && containsAny(text, "добуд", "сруб", "наруб", "коп", "собер", "принес")) return "wood";
        if (containsAny(text, "кам", "руд", "желез", "угол", "алмаз", "stone", "ore", "iron", "diamond")
                && containsAny(text, "добуд", "коп", "найд", "принес", "собер", "ищ")) return "stone";
        if (containsAny(text, "коп", "шахт", "добуд", "mine") && !containsAny(text, "дерев", "бревн")) return "stone";
        return null;
    }

    private Intent actionIntent(String action) {
        if (action == null) return null;
        return switch (action) {
            case "build" -> Intent.BUILD;
            case "follow" -> Intent.FOLLOW;
            case "stop" -> Intent.STOP;
            case "wood" -> Intent.MINE_WOOD;
            case "stone" -> Intent.MINE_STONE;
            case "attack" -> Intent.ATTACK;
            case "explore" -> Intent.EXPLORE;
            default -> null;
        };
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }


    /** Observed play patterns strengthen related semantic pathways. */
    void observeBehavior(String action) {
        switch (action) {
            case "build" -> reinforcePath(LanguageProcessor.Concept.BUILD,
                    LanguageProcessor.Concept.HOUSE, LanguageProcessor.Concept.CREATIVE,
                    LanguageProcessor.Concept.IDEA);
            case "mine" -> reinforcePath(LanguageProcessor.Concept.MINE,
                    LanguageProcessor.Concept.RESOURCE, LanguageProcessor.Concept.STONE,
                    LanguageProcessor.Concept.ORE);
            case "combat" -> reinforcePath(LanguageProcessor.Concept.COMBAT,
                    LanguageProcessor.Concept.MOB, LanguageProcessor.Concept.DANGER,
                    LanguageProcessor.Concept.SURVIVAL);
            case "explore" -> reinforcePath(LanguageProcessor.Concept.EXPLORE,
                    LanguageProcessor.Concept.JOURNEY, LanguageProcessor.Concept.NATURE,
                    LanguageProcessor.Concept.LOCATION);
            case "social" -> reinforcePath(LanguageProcessor.Concept.SOCIAL,
                    LanguageProcessor.Concept.PLAYER, LanguageProcessor.Concept.FRIENDSHIP,
                    LanguageProcessor.Concept.TRUST);
            default -> { }
        }
        dirty = true;
    }

    private void reinforcePath(LanguageProcessor.Concept... concepts) {
        for (int i = 0; i < concepts.length; i++) {
            for (int j = i + 1; j < concepts.length; j++) {
                int a = concepts[i].ordinal(), b = concepts[j].ordinal();
                synapses[a][b] = clamp(synapses[a][b] + 0.004, 0.0, MAX_WEIGHT);
                synapses[b][a] = clamp(synapses[b][a] + 0.004, 0.0, MAX_WEIGHT);
            }
        }
    }

    void learn(Analysis analysis, double feedback) {
        if (analysis == null) return;
        double reward = Math.max(-1.0, Math.min(1.0, feedback));
        int intentNode = CONCEPTS + analysis.intent.ordinal();
        int[] active = analysis.parsed.activeConcepts();

        // Hebbian association: concepts that appear together become linked.
        for (int i = 0; i < active.length; i++) {
            int from = active[i];
            double signal = analysis.activations[from];
            for (int j = i + 1; j < active.length; j++) {
                int to = active[j];
                double delta = 0.0015 * signal * analysis.activations[to];
                synapses[from][to] = clamp(synapses[from][to] + delta, 0.0, MAX_WEIGHT);
                synapses[to][from] = clamp(synapses[to][from] + delta, 0.0, MAX_WEIGHT);
            }
            double association = reward == 0 ? 0.003 : 0.008 * reward;
            synapses[from][intentNode] = clamp(synapses[from][intentNode] + association * Math.max(0.2, signal), 0.0, MAX_WEIGHT);
            if (reward > 0.15) synapses[intentNode][from] = clamp(synapses[intentNode][from] + association * 0.35, 0.0, MAX_WEIGHT);
        }
        dirty = true;
    }

    void save() {
        if (storage == null || !dirty) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        yaml.set("node-count", NODES);
        List<Double> values = new ArrayList<>(NODES * NODES);
        for (double[] row : synapses) for (double weight : row) values.add(round(weight));
        yaml.set("synapses", values);
        try {
            File parent = storage.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            File temporary = new File(parent, storage.getName() + ".tmp");
            yaml.save(temporary);
            Files.move(temporary.toPath(), storage.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            try {
                File temporary = new File(storage.getParentFile(), storage.getName() + ".tmp");
                yaml.save(temporary);
                Files.move(temporary.toPath(), storage.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                dirty = false;
            } catch (IOException error) {
                org.bukkit.Bukkit.getLogger().warning("[Igrok] Не удалось сохранить нейронные связи: " + error.getMessage());
            }
        } catch (IOException error) {
            org.bukkit.Bukkit.getLogger().warning("[Igrok] Не удалось сохранить нейронные связи: " + error.getMessage());
        }
    }

    private void load() {
        if (storage == null) return;
        File source = storage;
        if (!source.isFile()) {
            File parent = storage.getParentFile();
            File legacy = parent == null ? null : new File(parent.getParentFile(), "cognition.yml");
            if (legacy == null || !legacy.isFile()) return;
            source = legacy;
            dirty = true;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source);
        if (yaml.contains("node-count") && yaml.getInt("node-count") != NODES) return;
        List<?> values = yaml.getList("synapses");
        if (values == null || values.size() != NODES * NODES) return;
        int index = 0;
        for (int i = 0; i < NODES; i++) {
            for (int j = 0; j < NODES; j++) {
                Object value = values.get(index++);
                if (value instanceof Number number) synapses[i][j] = clamp(number.doubleValue(), 0.0, MAX_WEIGHT);
            }
        }
    }

    private void seed() {
        connect(LanguageProcessor.Concept.GREETING, Intent.GREETING, 0.82);
        connect(LanguageProcessor.Concept.FAREWELL, Intent.FAREWELL, 0.86);
        connect(LanguageProcessor.Concept.GRATITUDE, Intent.THANKS, 0.84);
        connect(LanguageProcessor.Concept.IDENTITY, Intent.IDENTITY, 0.74);
        connect(LanguageProcessor.Concept.STATUS, Intent.STATUS, 0.74);
        connect(LanguageProcessor.Concept.MEMORY, Intent.MEMORY, 0.84);
        connect(LanguageProcessor.Concept.LEARNING, Intent.LEARNING, 0.86);
        connect(LanguageProcessor.Concept.OPINION, Intent.OPINION, 0.80);
        connect(LanguageProcessor.Concept.HELP, Intent.HELP, 0.75);
        connect(LanguageProcessor.Concept.BUILD, Intent.BUILD, 0.56);
        connect(LanguageProcessor.Concept.FOLLOW, Intent.FOLLOW, 0.67);
        connect(LanguageProcessor.Concept.STOP, Intent.STOP, 0.77);
        connect(LanguageProcessor.Concept.WOOD, Intent.MINE_WOOD, 0.56);
        connect(LanguageProcessor.Concept.MINE, Intent.MINE_STONE, 0.40);
        connect(LanguageProcessor.Concept.STONE, Intent.MINE_STONE, 0.57);
        connect(LanguageProcessor.Concept.ORE, Intent.MINE_STONE, 0.60);
        connect(LanguageProcessor.Concept.COMBAT, Intent.ATTACK, 0.72);
        connect(LanguageProcessor.Concept.MOB, Intent.ATTACK, 0.53);
        connect(LanguageProcessor.Concept.EXPLORE, Intent.EXPLORE, 0.70);
        connect(LanguageProcessor.Concept.JOURNEY, Intent.EXPLORE, 0.47);
        connect(LanguageProcessor.Concept.FEELING, Intent.FEELING, 0.66);
        connect(LanguageProcessor.Concept.BOREDOM, Intent.BORED, 0.80);
        connect(LanguageProcessor.Concept.AGREEMENT, Intent.AGREEMENT, 0.55);
        connect(LanguageProcessor.Concept.DISAGREEMENT, Intent.DISAGREEMENT, 0.65);
        connect(LanguageProcessor.Concept.QUESTION, Intent.QUESTION, 0.56);
        connect(LanguageProcessor.Concept.SOCIAL, Intent.SMALLTALK, 0.60);
        associate(LanguageProcessor.Concept.HOUSE, LanguageProcessor.Concept.BUILD, 0.48);
        associate(LanguageProcessor.Concept.CAVE, LanguageProcessor.Concept.MINE, 0.50);
        associate(LanguageProcessor.Concept.ORE, LanguageProcessor.Concept.RESOURCE, 0.58);
        associate(LanguageProcessor.Concept.MOB, LanguageProcessor.Concept.DANGER, 0.62);
        associate(LanguageProcessor.Concept.FRIENDSHIP, LanguageProcessor.Concept.TRUST, 0.54);
        associate(LanguageProcessor.Concept.JOURNEY, LanguageProcessor.Concept.NATURE, 0.42);
        associate(LanguageProcessor.Concept.CREATIVE, LanguageProcessor.Concept.IDEA, 0.49);
        associate(LanguageProcessor.Concept.FOOD, LanguageProcessor.Concept.SURVIVAL, 0.50);
        associate(LanguageProcessor.Concept.WEATHER, LanguageProcessor.Concept.NATURE, 0.50);
    }

    private void connect(LanguageProcessor.Concept from, Intent to, double weight) {
        synapses[from.ordinal()][CONCEPTS + to.ordinal()] = weight;
    }

    private void associate(LanguageProcessor.Concept a, LanguageProcessor.Concept b, double weight) {
        synapses[a.ordinal()][b.ordinal()] = weight;
        synapses[b.ordinal()][a.ordinal()] = weight;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.rint(value * 10_000.0) / 10_000.0;
    }
}
