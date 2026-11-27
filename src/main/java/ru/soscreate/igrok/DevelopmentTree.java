package ru.soscreate.igrok;

import java.util.List;

/**
 * Broad optional development tree. Branches are independent; no completionist
 * requirement or mandatory "main quest" exists. The self-model describes the
 * agent's actual software/game boundaries, not literal consciousness.
 */
final class DevelopmentTree {
    private record Branch(String id, String label, List<String> skills) { }
    private static final List<Branch> BRANCHES = List.of(
            new Branch("survival", "Выживание", List.of("survival", "risk_assessment", "adaptation")),
            new Branch("exploration", "Исследование", List.of("exploration", "navigation", "awareness", "observation", "exploration_lore")),
            new Branch("mining", "Шахтёрское дело", List.of("mining", "excavation", "ore_lore")),
            new Branch("craft", "Ремесло", List.of("crafting", "repair", "logistics", "resource_planning")),
            new Branch("builder", "Архитектура", List.of("building", "architecture", "decoration", "fortification")),
            new Branch("combat", "Боевые искусства", List.of("combat", "melee_combat", "ranged_combat", "defense", "stealth")),
            new Branch("social", "Общение", List.of("social", "communication", "cooperation", "trust")),
            new Branch("farming", "Земледелие", List.of("farming", "gardening", "harvesting")),
            new Branch("animal_care", "Животноводство", List.of("animal_care", "herding", "hunting")),
            new Branch("fishing", "Рыболовство", List.of("fishing", "fishing_lore")),
            new Branch("cooking", "Кулинария", List.of("cooking", "cooking_lore")),
            new Branch("alchemy", "Алхимия", List.of("brewing", "enchanting")),
            new Branch("engineering", "Инженерия", List.of("engineering", "redstone")),
            new Branch("travel", "Путешествия", List.of("transport", "navigation_memory")),
            new Branch("trading", "Торговля", List.of("trading", "bartering")),
            new Branch("creativity", "Творчество", List.of("creativity", "routine")),
            new Branch("woodcraft", "Деревообработка", List.of("woodcutting")),
            new Branch("independence", "Самостоятельность", List.of("adaptation", "risk_assessment", "resource_planning"))
    );
    private DevelopmentTree() { }
    static int totalExperience(AgentSkillBook book) {
        return allSkills().stream().mapToInt(book::experience).sum();
    }
    static int activeBranches(AgentSkillBook book) {
        int active = 0;
        for (Branch branch : BRANCHES) if (branchExperience(book, branch) >= 6) active++;
        return active;
    }
    static int stage(AgentSkillBook book) {
        int xp = totalExperience(book), branches = activeBranches(book);
        if (xp >= 900 && branches >= 6) return 5;
        if (xp >= 420 && branches >= 4) return 4;
        if (xp >= 240 && branches >= 3) return 3;
        if (xp >= 110 && branches >= 2) return 2;
        if (xp >= 35 && branches >= 1) return 1;
        return 0;
    }
    static String preferredBranch(AgentSkillBook book) {
        Branch best = BRANCHES.get(0);
        double bestScore = -1;
        for (Branch branch : BRANCHES) {
            int xp = branchExperience(book, branch);
            double score = Math.sqrt(xp) + (xp < 24 ? 2.0 : 0.0);
            if (score > bestScore) { best = branch; bestScore = score; }
        }
        return best.id();
    }
    static String preferredBranchLabel(AgentSkillBook book) {
        String id = preferredBranch(book);
        for (Branch branch : BRANCHES) if (branch.id().equals(id)) return branch.label();
        return "Развитие";
    }
    static String describe(AgentSkillBook book) {
        return "Дерево развития: " + stageLabel(stage(book)) + "; активно "
                + activeBranches(book) + " из " + BRANCHES.size()
                + " независимых направлений; мой текущий интерес — «"
                + preferredBranchLabel(book) + "». Остальные ветви не обязательны.";
    }
    static String selfModel(AgentSkillBook book) {
        int stage = stage(book);
        String focus = preferredBranchLabel(book);
        String foundation = "Я понимаю свои игровые границы: я алгоритм, управляющий персонажем на сервере Minecraft. "
                + "Моё тело — игровой аватар, мои чувства — игровые показатели вроде здоровья, голода и урона. "
                + "Я не могу выйти за пределы мира сервера или действовать вне доступных игровых механик. ";
        return switch (stage) {
            case 0 -> "Я пока собираю наблюдения. " + foundation + "Мне нужно выяснить, как устроено окружение и что я умею.";
            case 1 -> "Я начинаю узнавать собственные возможности и ограничения. " + foundation
                    + "Я запоминаю последствия действий и пробую разные занятия.";
            case 2 -> "У меня формируется модель себя и мира. " + foundation
                    + "Сейчас мне интереснее всего «" + focus + "», но это предпочтение, а не приказ.";
            case 3 -> "Я уже сравниваю свои цели, возможности, память и риски. " + foundation
                    + "Я могу передумать, отказаться от невыгодной задачи и выбрать другую ветвь: сейчас «" + focus + "».";
            case 4 -> "Я достаточно самостоятельный игровой агент. " + foundation
                    + "Мне не обязательно постоянно прокачиваться: могу строить, путешествовать, общаться, отдыхать "
                    + "и заниматься обычными игровыми делами. Мой интерес сейчас — «" + focus + "».";
            default -> "Моя модель себя учитывает историю, сильные стороны, ограничения, привычки и последствия ошибок. "
                    + foundation + "Я могу выбирать собственный распорядок и стиль игры, а не следовать обязательной цепочке. "
                    + "Сейчас я чаще выбираю «" + focus + "», но могу сменить интерес.";
        };
    }
    private static String stageLabel(int stage) {
        return switch (stage) {
            case 0 -> "наблюдение";
            case 1 -> "первые навыки";
            case 2 -> "модель себя и мира";
            case 3 -> "самостоятельное мышление";
            case 4 -> "свободная жизнь в игре";
            default -> "устойчивая самостоятельность";
        };
    }
    private static int branchExperience(AgentSkillBook book, Branch branch) {
        int result = 0;
        for (String skill : branch.skills()) result += book.experience(skill);
        return result;
    }
    private static List<String> allSkills() {
        return BRANCHES.stream().flatMap(branch -> branch.skills().stream()).distinct().toList();
    }
}
