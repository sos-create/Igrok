package ru.soscreate.igrok;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Stable personality traits. They are deterministic for this bot UUID so the
 * bot keeps the same temperament after a restart.
 */
final class AgentPersonality {
    private final double curiosity;
    private final double bravery;
    private final double sociability;
    private final double patience;
    private final double creativity;

    AgentPersonality(UUID id) {
        long seed = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
        SplittableRandom random = new SplittableRandom(seed);
        curiosity = random.nextDouble(0.25, 0.96);
        bravery = random.nextDouble(0.20, 0.91);
        sociability = random.nextDouble(0.25, 0.96);
        patience = random.nextDouble(0.25, 0.96);
        creativity = random.nextDouble(0.30, 0.99);
    }

    double curiosity() { return curiosity; }
    double bravery() { return bravery; }
    double sociability() { return sociability; }
    double patience() { return patience; }
    double creativity() { return creativity; }

    String describe() {
        return String.format(java.util.Locale.ROOT,
                "Любознательность %.0f%%, смелость %.0f%%, общительность %.0f%%, терпение %.0f%%, творческий подход %.0f%%.",
                curiosity * 100, bravery * 100, sociability * 100, patience * 100, creativity * 100);
    }

    String opinion(String message, int trust) {
        String topic = message.toLowerCase(java.util.Locale.ROOT);
        if (topic.contains("крипер")) {
            return bravery > 0.58
                    ? "Криперы опасны, но паника только мешает. Лучше держать дистанцию и не подпускать их близко."
                    : "Криперы мне совсем не нравятся. Я предпочитаю заметить их издалека и отойти.";
        }
        if (topic.contains("шахт") || topic.contains("коп") || topic.contains("руд")) {
            return patience > 0.58
                    ? "Шахта — хорошее место для подготовки. Я предпочитаю сначала обеспечить безопасный выход, а потом добывать руду."
                    : "Ресурсы нужны, но бесконечно сидеть под землёй скучно. Я бы чередовал добычу и исследование.";
        }
        if (topic.contains("дом") || topic.contains("стро") || topic.contains("архит")) {
            return creativity > 0.58
                    ? "Одинаковые коробки быстро надоедают. Мне нравятся дома с контрастными материалами, окнами и узнаваемой крышей."
                    : "Главное в доме — надёжность и удобство. Красота важна, но сначала нужны стены, свет и безопасный вход.";
        }
        if (topic.contains("игрок") || topic.contains("люд") || topic.contains("друг")) {
            if (trust >= 3) return "С тобой приятно иметь дело. Я запоминаю, кто помогает и выполняет обещания.";
            if (trust <= -3) return "Я пока не очень тебе доверяю. Доверие зарабатывается поступками, а не словами.";
            return sociability > 0.58
                    ? "Мне нравится работать вместе. В одиночку можно многое, но с товарищами проще строить большие планы."
                    : "Я не против компании, но сначала предпочитаю присмотреться к человеку.";
        }
        if (topic.contains("битв") || topic.contains("драк") || topic.contains("оруж")) {
            return bravery > 0.58
                    ? "Драться стоит только тогда, когда есть причина и шанс победить. Я не люблю бессмысленный риск."
                    : "Я предпочитаю подготовку и защиту прямой драке. Живой и целый игрок полезнее героя в могиле.";
        }
        return creativity > 0.68
                ? "У меня есть своя точка зрения, но я готов её менять, когда появляются новые факты. Давай проверим идею на практике."
                : "Я стараюсь выбирать то, что приносит пользу и не создаёт лишних проблем. Не каждую идею стоит выполнять сразу.";
    }
}
