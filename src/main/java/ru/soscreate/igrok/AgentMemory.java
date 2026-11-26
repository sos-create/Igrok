package ru.soscreate.igrok;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small persistent memory: player reputation and learned resource counts. */
final class AgentMemory {
    private final Map<UUID, Integer> reputation = new HashMap<>();
    private final Map<String, Integer> mined = new HashMap<>();
    private final File file;

    AgentMemory(File file) {
        this.file = file;
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    reputation.put(UUID.fromString(key), clamp(players.getInt(key), -10, 10));
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed entries; one bad memory must not block startup.
                }
            }
        }
        ConfigurationSection resources = yaml.getConfigurationSection("mined");
        if (resources != null) {
            for (String key : resources.getKeys(false)) {
                mined.put(key, Math.max(0, resources.getInt(key)));
            }
        }
    }

    void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create plugin data folder");
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, Integer> entry : reputation.entrySet()) {
                yaml.set("players." + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Integer> entry : mined.entrySet()) {
                yaml.set("mined." + entry.getKey(), entry.getValue());
            }
            yaml.save(file);
        } catch (IOException error) {
            // The plugin remains usable even if the disk is read-only.
        }
    }

    void rememberMessage(UUID player, String message) {
        if (player == null || message == null) return;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        int delta = 0;
        if (contains(lower, "спасибо", "благодарю", "молодец", "помог", "круто")) delta = 1;
        if (contains(lower, "идиот", "тупой", "заткнись", "дурак", "бесполез")) delta = -1;
        if (delta != 0) reputation.put(player, clamp(reputation.getOrDefault(player, 0) + delta, -10, 10));
    }

    int trust(UUID player) {
        return player == null ? 0 : reputation.getOrDefault(player, 0);
    }

    void recordMined(Material material) {
        if (material == null) return;
        mined.merge(material.name(), 1, Integer::sum);
    }

    int totalMined() {
        return mined.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
