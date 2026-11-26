package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Bounded conversation memory stored as a compact versioned binary stream
 * inside GZIP. No third-party dependency is required.
 */
final class AgentMemory {
    private static final int MAGIC = 0x49474D31; // IGM1
    private static final int VERSION = 3;
    private static final int MAX_PLAYERS = 256;
    private static final int MAX_HISTORY = 24;
    private static final int MAX_TEXT_CHARS = 240;
    private static final int TOPIC_COUNT = 6;
    private static final String[] TOPICS = {
            "общение", "строительство", "добыча", "бой", "исследование", "прочее"
    };

    private static final class Entry {
        final boolean fromBot;
        final long timeMinutes;
        final String text;

        Entry(boolean fromBot, long timeMinutes, String text) {
            this.fromBot = fromBot;
            this.timeMinutes = timeMinutes;
            this.text = text;
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
        final int[][] transitions = new int[5][5];
        int lastAction = -1;
        final ArrayDeque<Entry> history = new ArrayDeque<>();
    }

    private final Map<UUID, PlayerRecord> players = new LinkedHashMap<>(32, 0.75F, true);
    private final Map<String, Integer> mined = new LinkedHashMap<>();
    private final File file;
    private final File legacyYaml;

    AgentMemory(File file) {
        this.file = file;
        this.legacyYaml = new File(file.getParentFile(), "memory.yml");
    }

    void load() {
        if (file.exists()) {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file.toPath())))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid memory signature");
                int version = in.readUnsignedByte();
                if (version < 1 || version > VERSION) throw new IOException("Unsupported memory version " + version);

                int minedCount = readVarInt(in);
                if (minedCount > 128) throw new IOException("Too many mined-material entries");
                for (int i = 0; i < minedCount; i++) {
                    String material = readString(in, 64);
                    mined.put(material, Math.max(0, readVarInt(in)));
                }

                int playerCount = readVarInt(in);
                if (playerCount > MAX_PLAYERS) throw new IOException("Too many player-memory entries");
                for (int i = 0; i < playerCount; i++) {
                    UUID id = new UUID(in.readLong(), in.readLong());
                    PlayerRecord record = new PlayerRecord();
                    record.name = readString(in, 48);
                    record.reputation = in.readByte();
                    record.interactions = readVarInt(in);
                    record.lastInteractionMinutes = readVarLong(in);
                    record.lastGreetingMinutes = readVarLong(in);
                    record.lastTopic = Math.max(0, Math.min(TOPIC_COUNT - 1, in.readUnsignedByte()));
                    for (int topic = 0; topic < TOPIC_COUNT; topic++) {
                        record.topics[topic] = readVarInt(in);
                    }
                    if (version >= 2) {
                        for (int action = 0; action < record.actions.length; action++) record.actions[action] = readVarInt(in);
                    }
                    if (version >= 3) {
                        for (int from = 0; from < 5; from++) {
                            for (int to = 0; to < 5; to++) record.transitions[from][to] = readVarInt(in);
                        }
                        record.lastAction = in.readByte() - 1;
                        if (record.lastAction < -1 || record.lastAction >= 5) record.lastAction = -1;
                    }
                    int historyCount = in.readUnsignedByte();
                    if (historyCount > MAX_HISTORY) throw new IOException("Invalid conversation history size");
                    for (int line = 0; line < historyCount; line++) {
                        boolean fromBot = in.readBoolean();
                        long time = readVarLong(in);
                        String text = readString(in, MAX_TEXT_CHARS * 4);
                        record.history.addLast(new Entry(fromBot, time, text));
                    }
                    players.put(id, record);
                }
            } catch (Exception error) {
                Bukkit.getLogger().warning("[Igrok] Не удалось прочитать сжатую память: " + error.getMessage());
                players.clear();
                mined.clear();
                File broken = new File(file.getParentFile(), "memory.corrupt");
                try {
                    Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // Keep the original file for manual recovery.
                }
                loadLegacyYaml();
            }
        } else {
            loadLegacyYaml();
        }
        trimPlayers();
    }

    private void loadLegacyYaml() {
        if (!legacyYaml.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacyYaml);
        ConfigurationSection oldPlayers = yaml.getConfigurationSection("players");
        if (oldPlayers != null) {
            for (String key : oldPlayers.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    PlayerRecord record = record(id);
                    record.reputation = clamp(oldPlayers.getInt(key), -10, 10);
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed legacy UUID keys.
                }
            }
        }
        ConfigurationSection oldMined = yaml.getConfigurationSection("mined");
        if (oldMined != null) {
            for (String key : oldMined.getKeys(false)) {
                mined.put(key, Math.max(0, oldMined.getInt(key)));
            }
        }
        save();
        Bukkit.getLogger().info("[Igrok] Старая память перенесена в компактный формат memory.bin.");
    }

    void save() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Bukkit.getLogger().warning("[Igrok] Не удалось создать папку памяти.");
            return;
        }

        File temporary = new File(parent, file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary.toPath())))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);

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
                for (int[] row : record.transitions) for (int count : row) writeVarInt(out, Math.max(0, count));
                out.writeByte(record.lastAction + 1);
                out.writeByte(record.history.size());
                for (Entry line : record.history) {
                    out.writeBoolean(line.fromBot);
                    writeVarLong(out, line.timeMinutes);
                    writeString(out, line.text, MAX_TEXT_CHARS);
                }
            }
        } catch (IOException error) {
            Bukkit.getLogger().warning("[Igrok] Не удалось сохранить память: " + error.getMessage());
            return;
        }

        try {
            Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                Bukkit.getLogger().warning("[Igrok] Не удалось заменить файл памяти: " + error.getMessage());
            }
        }
    }

    void rememberPlayerName(UUID player, String name) {
        if (player == null || name == null || name.isBlank()) return;
        record(player).name = crop(name, 48);
    }

    void rememberMessage(UUID player, String message) {
        if (player == null || message == null) return;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        int delta = 0;
        if (contains(lower, "спасибо", "благодарю", "молодец", "помог", "круто")) delta = 1;
        if (contains(lower, "идиот", "тупой", "заткнись", "дурак", "бесполез")) delta = -1;
        if (delta != 0) {
            PlayerRecord record = record(player);
            record.reputation = clamp(record.reputation + delta, -10, 10);
        }
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
        record.history.addLast(new Entry(fromBot, now, crop(message, MAX_TEXT_CHARS)));
        while (record.history.size() > MAX_HISTORY) record.history.removeFirst();
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
            result.append(line.fromBot ? "Я: " : safeName(record) + ": ").append(line.text);
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
        record.reputation = clamp(record.reputation + delta, -10, 10);
    }


    void observeAction(UUID player, String action) {
        if (player == null || action == null) return;
        PlayerRecord record = record(player);
        int index = actionIndex(action);
        record.actions[index] = Math.min(1_000_000, record.actions[index] + 1);
        if (record.lastAction >= 0 && record.lastAction < 5 && record.lastAction != index) {
            record.transitions[record.lastAction][index] =
                    Math.min(1_000_000, record.transitions[record.lastAction][index] + 1);
        }
        record.lastAction = index;
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
            if (next < 0 || record.transitions[best][i] > record.transitions[best][next]) next = i;
        }
        String habit = next >= 0 && record.transitions[best][next] > 0
                ? " Часто после того, как ты начинаешь " + labels[best] + ", ты переходишь к тому, чтобы " + labels[next] + "."
                : "";
        return "Я заметил " + total + " твоих игровых действий. Чаще всего ты предпочитаешь " + labels[best] + "." + habit
                + " Это пока наблюдаемая привычка, а не окончательный вывод.";
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
        mined.merge(material.name(), 1, (oldValue, one) -> Math.min(1_000_000, oldValue + one));
    }

    int totalMined() {
        return mined.values().stream().mapToInt(Integer::intValue).sum();
    }

    int rememberedPlayers() { return players.size(); }

    int rememberedMessages() {
        return players.values().stream().mapToInt(record -> record.history.size()).sum();
    }

    long storedBytes() {
        return file.exists() ? file.length() : 0L;
    }

    private PlayerRecord record(UUID player) {
        PlayerRecord result = players.computeIfAbsent(player, ignored -> new PlayerRecord());
        trimPlayers();
        return result;
    }

    private void trimPlayers() {
        while (players.size() > MAX_PLAYERS) {
            UUID oldest = players.keySet().iterator().next();
            players.remove(oldest);
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
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").replaceAll("\\s+", " ").trim();
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
        int current = value;
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
