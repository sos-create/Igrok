package ru.soscreate.igrok;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent experience for independent development branches. */
final class AgentSkillBook {
    private static final int[] LEVEL_THRESHOLDS = {0, 8, 22, 42, 68, 102, 145, 198, 264, 344, 440};
    private static final String[] SKILLS = {
            "navigation", "mining", "building", "crafting", "survival", "awareness",
            "combat", "farming", "fishing", "cooking", "social", "animal_care",
            "engineering", "exploration", "logistics"
    };
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("navigation", "ориентирование"), Map.entry("mining", "добыча"),
            Map.entry("building", "строительство"), Map.entry("crafting", "крафт"),
            Map.entry("survival", "выживание"), Map.entry("awareness", "наблюдательность"),
            Map.entry("combat", "бой"), Map.entry("farming", "фермерство"),
            Map.entry("fishing", "рыбалка"), Map.entry("cooking", "кулинария"),
            Map.entry("social", "общение"), Map.entry("animal_care", "животноводство"),
            Map.entry("engineering", "механизмы"), Map.entry("exploration", "исследование"),
            Map.entry("logistics", "управление ресурсами")
    );

    private final File file;
    private final Map<String, Integer> experience = new LinkedHashMap<>();
    private boolean dirty;

    AgentSkillBook(File file) {
        this.file = file;
        for (String skill : SKILLS) experience.put(skill, 0);
    }

    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String skill : SKILLS) {
            experience.put(skill, Math.max(0, Math.min(100_000, yaml.getInt("skills." + skill, 0))));
        }
        dirty = false;
    }

    void gain(String skill, int amount) {
        if (!experience.containsKey(skill) || amount <= 0) return;
        int old = experience.get(skill);
        int next = Math.min(100_000, old + Math.min(1000, amount));
        if (next == old) return;
        experience.put(skill, next);
        dirty = true;
    }

    int level(String skill) {
        int xp = experience.getOrDefault(skill, 0);
        int level = 0;
        for (int i = 1; i < LEVEL_THRESHOLDS.length; i++) {
            if (xp < LEVEL_THRESHOLDS[i]) break;
            level = i;
        }
        return level;
    }

    int experience(String skill) { return experience.getOrDefault(skill, 0); }

    String summary() {
        StringBuilder result = new StringBuilder("Мои навыки: ");
        boolean first = true;
        for (String skill : SKILLS) {
            if (!first) result.append("; ");
            first = false;
            result.append(LABELS.get(skill)).append(" — уровень ").append(level(skill));
            int currentLevel = level(skill);
            if (currentLevel < LEVEL_THRESHOLDS.length - 1) {
                int current = LEVEL_THRESHOLDS[currentLevel];
                int next = LEVEL_THRESHOLDS[currentLevel + 1];
                result.append(" (").append(experience(skill) - current)
                        .append('/').append(next - current).append(" опыта до следующего)");
            } else result.append(" (максимум)");
        }
        return result.append(". ").append(DevelopmentTree.describe(this)).toString();
    }

    void save() {
        if (!dirty) return;
        if (file.getParentFile() != null && !file.getParentFile().exists()
                && !file.getParentFile().mkdirs()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format-version", 2);
        for (String skill : SKILLS) yaml.set("skills." + skill, experience.get(skill));
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException error) {
            org.bukkit.Bukkit.getLogger().warning("[Igrok] Не удалось сохранить навыки: " + error.getMessage());
        }
    }
}
