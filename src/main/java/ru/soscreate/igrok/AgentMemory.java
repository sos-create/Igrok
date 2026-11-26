package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compact event-driven memory.
 *
 * Each distinct word has one short ID. Each distinct sentence is stored once
 * as a sequence of word IDs. Conversation history stores only sentence IDs.
 * A sparse two-axis matrix (previous sentence ID, current sentence ID) stores
 * recurring phrase transitions. This avoids a huge dense matrix and keeps disk
 * access to one compressed file, written only when dirty.
 */
final class AgentMemory {
    private static final int MAGIC = 0x49474D34; // IGM4
    private static final int VERSION = 1;

    private static final int MAX_PLAYERS = 50;
    private static final int MAX_HISTORY = 24;
    private static final int MAX_TEXT_CHARS = 240;
    private static final int MAX_WORDS = 8192;
    private static final int MAX_PHRASES = 8192;
    private static final int MAX_PHRASE_WORDS = 48;
    private static final int MAX_MATRIX_CELLS = 32768;
    private static final int MAX_MINED_TYPES = 128;
    private static final int TOPIC_COUNT = 6;
    private static final String[] TOPICS = {
            "общение", "строительство", "добыча", "бой", "исследование", "прочее"
    };

    private static final class Entry {
        final boolean fromBot;
        final long timeMinutes;
        final int phraseId;

        Entry(boolean fromBot, long timeMinutes, int phraseId) {
            this.fromBot = fromBot;
            this.timeMinutes = timeMinutes;
            this.phraseId = phraseId;
        }
    }

    private static final class PlayerRecord {
        String name = "";
        int reputation;
        int interactions;
        long lastInteractionMinutes;
        long lastGreetingMinutes;
        int lastTopic;
        final int[] topics = new int[TOPIC_COUNT];
        final int[] actions = new int[5];
        final int[][] actionTransitions = new int[5][5];
        int lastAction = -1;
        int lastPhraseId = 1;
        final ArrayDeque<Entry> history = new ArrayDeque<>();
    }

    private final Map<UUID, PlayerRecord> players = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, Integer> mined = new LinkedHashMap<>();
    private final Map<String, Integer> wordToId = new HashMap<>();
    private final List<String> idToWord = new ArrayList<>();
    private final Map<String, Integer> phraseToId = new HashMap<>();
    private final List<int[]> idToPhrase = new ArrayList<>();
    private final Map<Long, Integer> phraseMatrix = new LinkedHashMap<>();

    private final File folder;
    private final File file;
    private final File legacyBinary;
    private final File legacyYaml;
    private boolean dirty;
    private boolean loaded;

    AgentMemory(File dataFolder) {
        this.folder = new File(dataFolder, "memory");
        this.file = new File(folder, "memory.dat");
        this.legacyBinary = new File(dataFolder, "memory.bin");
        this.legacyYaml = new File(dataFolder, "memory.yml");
        resetDictionaries();
    }

    void load() {
        if (loaded) return;
        loaded = true;
        if (!folder.exists() && !folder.mkdirs()) {
            Bukkit.getLogger().warning("[Igrok] Не удалось создать папку компактной памяти.");
        }

        if (file.isFile()) {
            try {
                loadCompact();
                dirty = false;
                Bukkit.getLogger().info("[Igrok] Загружена компактная память: "
                        + players.size() + " профилей, " + phraseToId.size() + " фраз.");
                return;
            } catch (Exception error) {
                Bukkit.getLogger().warning("[Igrok] Ошибка чтения memory/memory.dat: "
                        + error.getMessage() + ". Старая память не удалена.");
                players.clear();
                mined.clear();
                phraseMatrix.clear();
                resetDictionaries();
                File corrupt = new File(folder, "memory.dat.corrupt");
                try {
                    Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException backupError) {
                    Bukkit.getLogger().warning("[Igrok] Не удалось сохранить повреждённую память отдельно: "
                            + backupError.getMessage());
                }
            }
        }

        if (legacyBinary.isFile()) {
            loadLegacyBinary();
        } else {
            loadLegacyYaml();
        }
        dirty = true;
        save();
    }

    private void resetDictionaries() {
        wordToId.clear();
        idToWord.clear();
        phraseToId.clear();
        idToPhrase.clear();
        idToWord.add("");
        idToWord.add("<UNK>");
        wordToId.put("<unk>", 1);
        idToPhrase.add(new int[0]);
        idToPhrase.add(new int[]{1});
        phraseToId.put("1", 1);
    }

    private void loadCompact() throws IOException {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(Files.newInputStream(file.toPath())))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid compact-memory signature");
            if (in.readUnsignedByte() != VERSION) throw new IOException("Unsupported compact-memory version");

            wordToId.clear();
            idToWord.clear();
            idToWord.add("");
            phraseToId.clear();
            idToPhrase.clear();
            idToPhrase.add(new int[0]);

            int wordCount = readVarInt(in);
            if (wordCount < 1 || wordCount > MAX_WORDS) throw new IOException("Invalid dictionary size");
            for (int i = 0; i < wordCount; i++) {
                String word = readString(in, 96);
                idToWord.add(word);
                wordToId.put(word, i + 1);
            }
            if (!"<UNK>".equals(idToWord.get(1))) throw new IOException("Missing unknown-word token");

            int phraseCount = readVarInt(in);
            if (phraseCount < 1 || phraseCount > MAX_PHRASES) throw new IOException("Invalid phrase table size");
            idToPhrase.add(new int[0]);
            for (int phraseId = 1; phraseId <= phraseCount; phraseId++) {
                int length = readVarInt(in);
                if (length < 1 || length > MAX_PHRASE_WORDS) throw new IOException("Invalid phrase length");
                int[] tokens = new int[length];
                for (int j = 0; j < length; j++) {
                    tokens[j] = readVarInt(in);
                    if (tokens[j] < 1 || tokens[j] >= idToWord.size()) throw new IOException("Invalid word ID");
                }
                idToPhrase.add(tokens);
                phraseToId.put(phraseKey(tokens), phraseId);
            }

            int matrixSize = readVarInt(in);
            if (matrixSize > MAX_MATRIX_CELLS) throw new IOException("Phrase matrix too large");
            for (int i = 0; i < matrixSize; i++) {
                int x = readVarInt(in);
                int y = readVarInt(in);
                int count = readVarInt(in);
                if (x > phraseCount || y > phraseCount) throw new IOException("Invalid phrase matrix coordinate");
                phraseMatrix.put(pairKey(x, y), count);
            }

            int minedCount = readVarInt(in);
            if (minedCount > MAX_MINED_TYPES) throw new IOException("Too many mined-material entries");
            for (int i = 0; i < minedCount; i++) {
                mined.put(readString(in, 64), Math.max(0, readVarInt(in)));
            }

            int playerCount = readVarInt(in);
            if (playerCount > MAX_PLAYERS) throw new IOException("Too many player profiles");
            for (int i = 0; i < playerCount; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                PlayerRecord record = new PlayerRecord();
                record.name = readString(in, 48);
                record.reputation = in.readByte();
                record.interactions = readVarInt(in);
                record.lastInteractionMinutes = readVarLong(in);
                record.lastGreetingMinutes = readVarLong(in);
                record.lastTopic = Math.max(0, Math.min(TOPIC_COUNT - 1, in.readUnsignedByte()));
                for (int topic = 0; topic < TOPIC_COUNT; topic++) record.topics[topic] = readVarInt(in);
                for (int action = 0; action < 5; action++) record.actions[action] = readVarInt(in);
                for (int from = 0; from < 5; from++) {
                    for (int to = 0; to < 5; to++) record.actionTransitions[from][to] = readVarInt(in);
                }
                record.lastAction = in.readByte() - 1;
                if (record.lastAction < -1 || record.lastAction >= 5) record.lastAction = -1;
                record.lastPhraseId = readVarInt(in);
                if (record.lastPhraseId < 1 || record.lastPhraseId > phraseCount) record.lastPhraseId = 1;

                int historyCount = in.readUnsignedByte();
                if (historyCount > MAX_HISTORY) throw new IOException("Invalid conversation history size");
                for (int line = 0; line < historyCount; line++) {
                    boolean fromBot = in.readBoolean();
                    long time = readVarLong(in);
                    int phraseId = readVarInt(in);
                    if (phraseId < 1 || phraseId > phraseCount) phraseId = 1;
                    record.history.addLast(new Entry(fromBot, time, phraseId));
                }
                players.put(id, record);
            }
            trimPlayers();
        }
    }

    /** Reads old GZIP memory.bin versions 1-3 and converts text to shared phrase IDs. */
    private void loadLegacyBinary() {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(legacyBinary.toPath())))) {
            if (in.readInt() != 0x49474D31) throw new IOException("Invalid old memory signature");
            int version = in.readUnsignedByte();
            if (version < 1 || version > 3) throw new IOException("Unsupported old memory version " + version);

            int minedCount = readVarInt(in);
            if (minedCount > MAX_MINED_TYPES) throw new IOException("Too many mined-material entries");
            for (int i = 0; i < minedCount; i++) mined.put(readString(in, 64), Math.max(0, readVarInt(in)));

            int playerCount = readVarInt(in);
            if (playerCount > 256) throw new IOException("Old profile count is invalid");
            for (int i = 0; i < playerCount; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                PlayerRecord record = new PlayerRecord();
                record.name = readString(in, 48);
                record.reputation = in.readByte();
                record.interactions = readVarInt(in);
                record.lastInteractionMinutes = readVarLong(in);
                record.lastGreetingMinutes = readVarLong(in);
                record.lastTopic = Math.max(0, Math.min(TOPIC_COUNT - 1, in.readUnsignedByte()));
                for (int topic = 0; topic < TOPIC_COUNT; topic++) record.topics[topic] = readVarInt(in);
                if (version >= 2) {
                    for (int action = 0; action < 5; action++) record.actions[action] = readVarInt(in);
                }
                if (version >= 3) {
                    for (int from = 0; from < 5; from++) {
                        for (int to = 0; to < 5; to++) record.actionTransitions[from][to] = readVarInt(in);
                    }
                    record.lastAction = in.readByte() - 1;
                    if (record.lastAction < -1 || record.lastAction >= 5) record.lastAction = -1;
                }
                int historyCount = in.readUnsignedByte();
                if (historyCount > 24) throw new IOException("Old conversation history is invalid");
                for (int line = 0; line < historyCount; line++) {
                    boolean fromBot = in.readBoolean();
                    long time = readVarLong(in);
                    String text = readString(in, 960);
                    int phrase = internPhrase(text);
                    record.history.addLast(new Entry(fromBot, time, phrase));
                    rememberPhraseTransition(record.lastPhraseId, phrase);
                    record.lastPhraseId = phrase;
                }
                players.put(id, record);
            }
            trimPlayers();
            Bukkit.getLogger().info("[Igrok] Старая память memory.bin перенесена в новую матричную структуру. Оригинал оставлен как резервная копия.");
        } catch (Exception error) {
            Bukkit.getLogger().warning("[Igrok] Не удалось перенести старую память: " + error.getMessage());
            players.clear();
            mined.clear();
            phraseMatrix.clear();
            resetDictionaries();
            loadLegacyYaml();
        }
    }

    private void loadLegacyYaml() {
        if (!legacyYaml.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacyYaml);
        ConfigurationSection oldPlayers = yaml.getConfigurationSection("players");
        if (oldPlayers != null) {
            for (String key : oldPlayers.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    record(id).reputation = clamp(oldPlayers.getInt(key), -10, 10);
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed legacy UUID keys.
                }
            }
        }
        ConfigurationSection oldMined = yaml.getConfigurationSection("mined");
        if (oldMined != null) {
            for (String key : oldMined.getKeys(false)) mined.put(key, Math.max(0, oldMined.getInt(key)));
        }
        Bukkit.getLogger().info("[Igrok] Старая YAML-память подготовлена к переносу в memory/memory.dat.");
    }

    void save() {
        if (!loaded || (!dirty && file.isFile())) return;
        if (!folder.exists() && !folder.mkdirs()) {
            Bukkit.getLogger().warning("[Igrok] Не удалось создать папку памяти.");
            return;
        }

        File temporary = new File(folder, "memory.dat.tmp");
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(Files.newOutputStream(temporary.toPath())))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);

            writeVarInt(out, idToWord.size() - 1);
            for (int i = 1; i < idToWord.size(); i++) writeString(out, idToWord.get(i), 96);

            writeVarInt(out, idToPhrase.size() - 1);
            for (int i = 1; i < idToPhrase.size(); i++) {
                int[] phrase = idToPhrase.get(i);
                writeVarInt(out, phrase.length);
                for (int token : phrase) writeVarInt(out, token);
            }

            writeVarInt(out, phraseMatrix.size());
            for (Map.Entry<Long, Integer> entry : phraseMatrix.entrySet()) {
                writeVarInt(out, firstCoordinate(entry.getKey()));
                writeVarInt(out, secondCoordinate(entry.getKey()));
                writeVarInt(out, entry.getValue());
            }

            writeVarInt(out, mined.size());
            for (Map.Entry<String, Integer> entry : mined.entrySet()) {
                writeString(out, entry.getKey(), 64);
                writeVarInt(out, Math.max(0, entry.getValue()));
            }

            trimPlayers();
            writeVarInt(out, players.size());
            for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord record = entry.getValue();
                out.writeLong(entry.getKey().getMostSignificantBits());
                out.writeLong(entry.getKey().getLeastSignificantBits());
                writeString(out, record.name, 48);
                out.writeByte(clamp(record.reputation, -10, 10));
                writeVarInt(out, Math.max(0, record.interactions));
                writeVarLong(out, record.lastInteractionMinutes);
                writeVarLong(out, record.lastGreetingMinutes);
                out.writeByte(record.lastTopic);
                for (int count : record.topics) writeVarInt(out, Math.max(0, count));
                for (int count : record.actions) writeVarInt(out, Math.max(0, count));
                for (int[] row : record.actionTransitions) for (int count : row) writeVarInt(out, Math.max(0, count));
                out.writeByte(record.lastAction + 1);
                writeVarInt(out, record.lastPhraseId);
                out.writeByte(record.history.size());
                for (Entry line : record.history) {
                    out.writeBoolean(line.fromBot);
                    writeVarLong(out, line.timeMinutes);
                    writeVarInt(out, line.phraseId);
                }
            }
        } catch (IOException error) {
            Bukkit.getLogger().warning("[Igrok] Не удалось сохранить компактную память: " + error.getMessage());
            return;
        }

        try {
            Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException atomicUnsupported) {
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                dirty = false;
            } catch (IOException error) {
                Bukkit.getLogger().warning("[Igrok] Не удалось заменить memory.dat: " + error.getMessage());
            }
        }
    }

    private void rememberPhraseTransition(int previousPhrase, int currentPhrase) {
        if (previousPhrase < 1 || currentPhrase < 1) return;
        long coordinate = pairKey(previousPhrase, currentPhrase);
        Integer current = phraseMatrix.get(coordinate);
        if (current != null) {
            phraseMatrix.put(coordinate, Math.min(1_000_000, current + 1));
        } else if (phraseMatrix.size() < MAX_MATRIX_CELLS) {
            phraseMatrix.put(coordinate, 1);
        }
    }

    private int internPhrase(String text) {
        String[] words = tokenize(text);
        if (words.length == 0) return 1;
        int length = Math.min(words.length, MAX_PHRASE_WORDS);
        int[] tokens = new int[length];
        for (int i = 0; i < length; i++) tokens[i] = internWord(words[i]);
        String key = phraseKey(tokens);
        Integer existing = phraseToId.get(key);
        if (existing != null) return existing;
        if (idToPhrase.size() - 1 >= MAX_PHRASES) return 1;
        int id = idToPhrase.size();
        idToPhrase.add(tokens);
        phraseToId.put(key, id);
        dirty = true;
        return id;
    }

    private int internWord(String word) {
        String normalized = word.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        Integer existing = wordToId.get(normalized);
        if (existing != null) return existing;
        if (idToWord.size() - 1 >= MAX_WORDS) return 1;
        int id = idToWord.size();
        idToWord.add(normalized);
        wordToId.put(normalized, id);
        dirty = true;
        return id;
    }

    private static String[] tokenize(String text) {
        String normalized = crop(text == null ? "" : text, MAX_TEXT_CHARS)
                .toLowerCase(java.util.Locale.ROOT).replace('ё', 'е')
                .replaceAll("([.!?,;:])", " $1 ");
        String[] raw = normalized.trim().split("\\s+");
        if (raw.length == 1 && raw[0].isBlank()) return new String[0];
        List<String> result = new ArrayList<>(Math.min(raw.length, MAX_PHRASE_WORDS));
        for (String token : raw) {
            String clean = token.replaceAll("[^\\p{L}\\p{N}_!?.,;:]", "");
            if (clean.isBlank()) continue;
            if (clean.length() > 48) clean = clean.substring(0, 48);
            result.add(clean);
            if (result.size() >= MAX_PHRASE_WORDS) break;
        }
        return result.toArray(String[]::new);
    }

    private String decodePhrase(int phraseId) {
        if (phraseId < 1 || phraseId >= idToPhrase.size()) return "";
        int[] tokens = idToPhrase.get(phraseId);
        StringBuilder result = new StringBuilder();
        for (int id : tokens) {
            String word = id > 0 && id < idToWord.size() ? idToWord.get(id) : "<UNK>";
            boolean punctuation = word.length() == 1 && ".,!?;:".contains(word);
            if (punctuation) {
                while (result.length() > 0 && result.charAt(result.length() - 1) == ' ') {
                    result.setLength(result.length() - 1);
                }
                result.append(word).append(' ');
            } else {
                result.append(word).append(' ');
            }
        }
        return result.toString().trim();
    }

    private static String phraseKey(int[] tokens) {
        return Arrays.toString(tokens);
    }

    private static long pairKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static int firstCoordinate(long key) { return (int) (key >>> 32); }
    private static int secondCoordinate(long key) { return (int) key; }

    void rememberPlayerName(UUID player, String name) {
        if (player == null || name == null || name.isBlank()) return;
        PlayerRecord record = record(player);
        String clean = crop(name, 48);
        if (!clean.equals(record.name)) {
            record.name = clean;
            dirty = true;
        }
    }

    void rememberMessage(UUID player, String message) {
        if (player == null || message == null) return;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        int delta = 0;
        if (contains(lower, "спасибо", "благодарю", "молодец", "помог", "круто")) delta = 1;
        if (contains(lower, "идиот", "тупой", "заткнись", "дурак", "бесполез")) delta = -1;
        if (delta != 0) adjustReputation(player, delta);
    }

    void recordConversation(UUID player, String message, boolean fromBot) {
        if (player == null || message == null || message.isBlank()) return;
        PlayerRecord record = record(player);
        long now = System.currentTimeMillis() / 60_000L;
        record.lastInteractionMinutes = now;
        if (!fromBot) record.interactions++;
        int topic = topicFor(message);
        record.lastTopic = topic;
        if (!fromBot) record.topics[topic] = Math.min(1_000_000, record.topics[topic] + 1);

        int phraseId = internPhrase(message);
        rememberPhraseTransition(record.lastPhraseId, phraseId);
        record.lastPhraseId = phraseId;
        record.history.addLast(new Entry(fromBot, now, phraseId));
        while (record.history.size() > MAX_HISTORY) record.history.removeFirst();
        dirty = true;
    }

    boolean shouldGreet(UUID player, long cooldownMillis) {
        PlayerRecord record = players.get(player);
        if (record == null || record.lastGreetingMinutes == 0) return true;
        long elapsed = System.currentTimeMillis() / 60_000L - record.lastGreetingMinutes;
        return elapsed * 60_000L >= cooldownMillis;
    }

    void markGreeted(UUID player, String botGreeting) {
        if (player == null) return;
        PlayerRecord record = record(player);
        record.lastGreetingMinutes = System.currentTimeMillis() / 60_000L;
        recordConversation(player, botGreeting, true);
        dirty = true;
    }

    boolean isConversationActive(UUID player, long timeoutMillis) {
        PlayerRecord record = players.get(player);
        if (record == null || record.lastInteractionMinutes == 0) return false;
        long elapsed = System.currentTimeMillis() / 60_000L - record.lastInteractionMinutes;
        return elapsed >= 0 && elapsed * 60_000L <= timeoutMillis;
    }

    String conversationSummary(UUID player, int maxLines) {
        PlayerRecord record = players.get(player);
        if (record == null || record.history.isEmpty()) return "Мы ещё почти не разговаривали.";
        List<Entry> lines = new ArrayList<>(record.history);
        StringBuilder result = new StringBuilder();
        int start = Math.max(0, lines.size() - Math.max(1, maxLines));
        for (int i = start; i < lines.size(); i++) {
            Entry line = lines.get(i);
            if (result.length() > 0) result.append(" | ");
            result.append(line.fromBot ? "Я: " : safeName(record) + ": ")
                    .append(decodePhrase(line.phraseId));
        }
        return result.toString();
    }

    String playerSummary(UUID player) {
        PlayerRecord record = players.get(player);
        if (record == null) return "Я пока почти ничего о тебе не знаю.";
        int favoriteTopic = 0;
        for (int i = 1; i < TOPIC_COUNT; i++) {
            if (record.topics[i] > record.topics[favoriteTopic]) favoriteTopic = i;
        }
        String topic = record.topics[favoriteTopic] == 0 ? "пока неизвестно" : TOPICS[favoriteTopic];
        String attitude = record.reputation >= 3 ? "скорее доверяю"
                : record.reputation <= -3 ? "пока отношусь настороженно" : "пока присматриваюсь";
        return safeName(record) + ": мы общались " + record.interactions + " раз(а), "
                + "чаще всего говорили про " + topic + "; " + attitude + ".";
    }

    String lastTopic(UUID player) {
        PlayerRecord record = players.get(player);
        return record == null ? TOPICS[TOPIC_COUNT - 1] : TOPICS[record.lastTopic];
    }

    int trust(UUID player) {
        PlayerRecord record = players.get(player);
        return record == null ? 0 : record.reputation;
    }

    void adjustReputation(UUID player, int delta) {
        if (player == null) return;
        PlayerRecord record = record(player);
        int next = clamp(record.reputation + delta, -10, 10);
        if (next != record.reputation) {
            record.reputation = next;
            dirty = true;
        }
    }

    void observeAction(UUID player, String action) {
        if (player == null || action == null) return;
        PlayerRecord record = record(player);
        int index = actionIndex(action);
        record.actions[index] = Math.min(1_000_000, record.actions[index] + 1);
        if (record.lastAction >= 0 && record.lastAction < 5 && record.lastAction != index) {
            record.actionTransitions[record.lastAction][index] =
                    Math.min(1_000_000, record.actionTransitions[record.lastAction][index] + 1);
        }
        record.lastAction = index;
        dirty = true;
    }

    String learnedBehavior(UUID player) {
        PlayerRecord record = players.get(player);
        if (record == null) return "Я пока не успел изучить твой стиль игры.";
        String[] labels = {"строить", "добывать ресурсы", "сражаться", "исследовать мир", "взаимодействовать с миром"};
        int best = 0, total = 0;
        for (int i = 0; i < record.actions.length; i++) {
            total += record.actions[i];
            if (record.actions[i] > record.actions[best]) best = i;
        }
        if (total == 0) return "Я пока наблюдал за тобой слишком мало, чтобы понять твой стиль игры.";
        int next = -1;
        for (int i = 0; i < 5; i++) {
            if (next < 0 || record.actionTransitions[best][i] > record.actionTransitions[best][next]) next = i;
        }
        String habit = next >= 0 && record.actionTransitions[best][next] > 0
                ? " Часто после того, как ты начинаешь " + labels[best]
                    + ", ты переходишь к тому, чтобы " + labels[next] + "."
                : "";
        return "Я заметил " + total + " твоих игровых действий. Чаще всего ты предпочитаешь "
                + labels[best] + "." + habit + " Это пока наблюдаемая привычка, а не окончательный вывод.";
    }

    private static int actionIndex(String action) {
        return switch (action) {
            case "build" -> 0;
            case "mine" -> 1;
            case "combat" -> 2;
            case "explore" -> 3;
            default -> 4;
        };
    }

    int[] behaviorSignals(UUID player) {
        PlayerRecord record = players.get(player);
        return record == null ? new int[5] : record.actions.clone();
    }

    void recordMined(Material material) {
        if (material == null) return;
        if (mined.size() >= MAX_MINED_TYPES && !mined.containsKey(material.name())) return;
        mined.merge(material.name(), 1, (oldValue, one) -> Math.min(1_000_000, oldValue + one));
        dirty = true;
    }

    int totalMined() {
        return mined.values().stream().mapToInt(Integer::intValue).sum();
    }

    int rememberedPlayers() { return players.size(); }

    int rememberedMessages() {
        return players.values().stream().mapToInt(record -> record.history.size()).sum();
    }

    long storedBytes() {
        return file.isFile() ? file.length() : 0L;
    }

    private PlayerRecord record(UUID player) {
        PlayerRecord result = players.get(player);
        if (result != null) return result;
        result = new PlayerRecord();
        players.put(player, result);
        trimPlayers();
        dirty = true;
        return result;
    }

    private void trimPlayers() {
        while (players.size() > MAX_PLAYERS) {
            UUID oldest = players.keySet().iterator().next();
            players.remove(oldest);
            dirty = true;
        }
    }

    private static int topicFor(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (contains(lower, "дом", "стро", "крыша", "здание")) return 1;
        if (contains(lower, "коп", "шахт", "руда", "дерев", "камень", "ресурс")) return 2;
        if (contains(lower, "атак", "драк", "моб", "меч", "крипер", "бой")) return 3;
        if (contains(lower, "исслед", "путеше", "мест", "биом", "найти")) return 4;
        if (contains(lower, "привет", "дела", "спасибо", "друг", "поговор")) return 0;
        return 5;
    }

    private static String safeName(PlayerRecord record) {
        return record.name.isBlank() ? "Игрок" : record.name;
    }

    private static String crop(String text, int maxChars) {
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars);
    }

    private static void writeString(DataOutputStream out, String value, int maxChars) throws IOException {
        byte[] bytes = crop(value == null ? "" : value, maxChars).getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > maxBytes) throw new IOException("Invalid string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int current = Math.max(0, value);
        while ((current & 0xFFFFFF80) != 0) {
            out.writeByte((current & 0x7F) | 0x80);
            current >>>= 7;
        }
        out.writeByte(current);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            int value = in.readUnsignedByte();
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("Invalid variable integer");
    }

    private static void writeVarLong(DataOutputStream out, long value) throws IOException {
        long current = Math.max(0L, value);
        while ((current & 0xFFFFFFFFFFFFFF80L) != 0L) {
            out.writeByte(((int) current & 0x7F) | 0x80);
            current >>>= 7;
        }
        out.writeByte((int) current);
    }

    private static long readVarLong(DataInputStream in) throws IOException {
        long result = 0L;
        int shift = 0;
        while (shift < 70) {
            int value = in.readUnsignedByte();
            result |= (long) (value & 0x7F) << shift;
            if ((value & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("Invalid variable long");
    }

    private static boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
