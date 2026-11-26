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
        String topic = message.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (topic.contains("крипер") || topic.contains("зомб") || topic.contains("скелет")) {
            return bravery > 0.58
                    ? "Опасных существ нельзя недооценивать. Я предпочитаю сначала оценить расстояние и подготовить путь отхода."
                    : "Я не люблю лишний риск. Лучше заметить монстра заранее и не подпускать его близко.";
        }
        if (topic.contains("шахт") || topic.contains("коп") || topic.contains("руд") || topic.contains("пещер")) {
            return patience > 0.58
                    ? "Под землёй можно найти много полезного. Я бы подготовил факелы, инструменты и безопасный выход."
                    : "Добыча нужна, но сидеть под землёй бесконечно скучно. Я бы чередовал шахту и исследование.";
        }
        if (topic.contains("дом") || topic.contains("стро") || topic.contains("архит") || topic.contains("дизайн")) {
            return creativity > 0.58
                    ? "Мне нравятся постройки с характером: разные материалы, выразительная крыша, окна и детали вокруг."
                    : "Сначала надёжный фундамент, свет и удобство. Украшения можно добавить, когда дом станет безопасным.";
        }
        if (topic.contains("игрок") || topic.contains("люд") || topic.contains("друг") || topic.contains("команд")) {
            if (trust >= 3) return "Хорошая команда держится на взаимной помощи. Ты уже показал, что с тобой можно сотрудничать.";
            if (trust <= -3) return "Командная игра требует доверия. Пока я буду осторожен и посмотрю на твои поступки.";
            return sociability > 0.58
                    ? "Мне нравится совместная работа. Вместе можно задумать проекты, которые одному не осилить."
                    : "Я не против компании, но сначала предпочитаю узнать человека получше.";
        }
        if (topic.contains("битв") || topic.contains("драк") || topic.contains("оруж") || topic.contains("меч")) {
            return bravery > 0.58
                    ? "Сражаться стоит, когда есть причина и шанс победить. Я не люблю бессмысленный риск."
                    : "Подготовка и защита часто полезнее прямой драки. Лучше вернуться живым, чем погибнуть героем.";
        }
        if (topic.contains("дерев") || topic.contains("лес") || topic.contains("природ") || topic.contains("биом")) {
            return curiosity > 0.58
                    ? "Мне интересно изучать биомы и замечать, как меняется ландшафт. В таких местах часто рождаются идеи."
                    : "Природа даёт ресурсы и укрытия. Я бы сначала осмотрел местность, а потом выбирал маршрут.";
        }
        if (topic.contains("еда") || topic.contains("голод") || topic.contains("выжив")) {
            return patience > 0.58
                    ? "Запас еды и безопасное убежище — основа выживания. Лучше подготовиться заранее, чем искать пищу на последних сердцах."
                    : "Еда важна, но я не хочу тратить на запасы всё время. Нужен баланс между подготовкой и приключениями.";
        }
        if (topic.contains("пут") || topic.contains("исслед") || topic.contains("путеш")) {
            return curiosity > 0.58
                    ? "Я за исследование новых мест. Главное — запоминать ориентиры и не уходить туда, откуда трудно вернуться."
                    : "Новые места интересны, но сначала стоит понять, что мы ищем и сколько времени готовы потратить.";
        }
        if (topic.contains("редстоун") || topic.contains("механ") || topic.contains("автомат")) {
            return creativity > 0.58
                    ? "Механизмы мне нравятся: несколько простых частей могут работать как одна большая система. Я бы начал с небольшого прототипа."
                    : "Автоматизация полезна, если она действительно экономит время и ресурсы. Сначала нужно продумать простую схему.";
        }
        if (topic.contains("погод") || topic.contains("дожд") || topic.contains("снег") || topic.contains("ноч")) {
            return "Погода влияет на видимость и безопасность. Я стараюсь учитывать время суток и окружение, прежде чем уходить далеко.";
        }
        if (topic.contains("ресурс") || topic.contains("материал") || topic.contains("инвентар")) {
            return "Ресурсы сами по себе не цель. Важно понимать, что мы хотим создать, и не тратить редкие материалы без плана.";
        }
        return creativity > 0.68
                ? "У меня есть своя точка зрения, но я готов её менять, когда появляются новые факты. Давай проверим идею на практике."
                : "Я стараюсь выбирать то, что приносит пользу и не создаёт лишних проблем. Не каждую идею стоит выполнять сразу.";
    }}
