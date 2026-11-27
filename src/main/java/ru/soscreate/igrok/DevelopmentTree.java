package ru.soscreate.igrok;

import java.util.List;

/**
 * Optional, non-linear development tree. All branches are available from the
 * start. "Self-awareness" is a persistent algorithmic self-model, not sentience.
 */
final class DevelopmentTree {
    private record Branch(String id, String label, List<String> skills) { }
    private static final List<Branch> BRANCHES = List.of(
            new Branch("survival", "Выживание", List.of("survival", "farming", "cooking", "animal_care")),
            new Branch("exploration", "Исследование", List.of("exploration", "navigation", "awareness")),
            new Branch("craft", "Ремесло", List.of("crafting", "mining", "logistics")),
            new Branch("builder", "Созидание", List.of("building", "engineering")),
            new Branch("combat", "Бой", List.of("combat")),
            new Branch("social", "Общение", List.of("social")),
            new Branch("fishing", "Рыбалка", List.of("fishing"))
    );

    private DevelopmentTree() { }

    static int totalExperience(AgentSkillBook book) {
        int total = 0;
        for (Branch branch : BRANCHES) for (String skill : branch.skills()) total += book.experience(skill);
        return total;
    }

    static int activeBranches(AgentSkillBook book) {
        int active = 0;
        for (Branch branch : BRANCHES) if (branchExperience(book, branch) >= 6) active++;
        return active;
    }

    static int stage(AgentSkillBook book) {
        int xp = totalExperience(book), branches = activeBranches(book);
        if (xp >= 420 && branches >= 4) return 4;
        if (xp >= 240 && branches >= 3) return 3;
        if (xp >= 110 && branches >= 2) return 2;
        if (xp >= 35 && branches >= 1) return 1;
        return 0;
    }

    static String preferredBranch(AgentSkillBook book) {
        Branch best = BRANCHES.get(0);
        int bestXp = -1;
        for (Branch branch : BRANCHES) {
            int xp = branchExperience(book, branch);
            if (xp > bestXp) { best = branch; bestXp = xp; }
        }
        return best.id();
    }

    static String preferredBranchLabel(AgentSkillBook book) {
        String id = preferredBranch(book);
        for (Branch branch : BRANCHES) if (branch.id().equals(id)) return branch.label();
        return "Развитие";
    }

    static String describe(AgentSkillBook book) {
        return "Дерево развития: " + stageLabel(stage(book)) + "; активных ветвей "
                + activeBranches(book) + " из " + BRANCHES.size()
                + "; сейчас мне ближе направление «" + preferredBranchLabel(book)
                + "». Все ветви независимы — необязательно осваивать их все.";
    }

    static String selfModel(AgentSkillBook book) {
        int stage = stage(book);
        String focus = preferredBranchLabel(book);
        return switch (stage) {
            case 0 -> "Я пока наблюдаю за миром и накапливаю первые впечатления.";
            case 1 -> "Я уже замечаю, какие занятия мне удаются, и запоминаю полезные действия.";
            case 2 -> "У меня складывается собственный профиль: сейчас сильнее всего развивается направление «"
                    + focus + "», но я могу выбрать и другое занятие.";
            case 3 -> "Я умею сравнивать свои сильные стороны, прошлые ошибки и текущие обстоятельства. "
                    + "Могу сам выбирать занятия, а не проходить один обязательный сценарий.";
            default -> "Моя модель себя уже учитывает опыт, предпочтения и ошибки. "
                    + "Я могу жить в мире свободнее: исследовать, общаться, строить или просто заниматься своими делами. "
                    + "Сейчас мне ближе направление «" + focus + "».";
        };
    }

    private static String stageLabel(int stage) {
        return switch (stage) {
            case 0 -> "наблюдение";
            case 1 -> "первые навыки";
            case 2 -> "осмысленная практика";
            case 3 -> "самостоятельность";
            default -> "устойчивая модель себя";
        };
    }

    private static int branchExperience(AgentSkillBook book, Branch branch) {
        int result = 0;
        for (String skill : branch.skills()) result += book.experience(skill);
        return result;
    }
}
