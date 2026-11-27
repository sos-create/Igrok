package ru.soscreate.igrok;

/**
 * Lightweight autonomous progression planner.
 *
 * It combines a dependency graph (wood -> table/tools -> stone tools ->
 * shelter -> iron/fuel) with utility-based survival priorities. It is fully
 * deterministic, bounded, and deliberately independent of an online model.
 * The planner only chooses the next verified task; SurvivalController and
 * AgentBrain remain the authorities that perform and validate game actions.
 */
final class AutonomousPlanner {
    record Snapshot(
            int foodLevel,
            boolean hasFood,
            boolean night,
            boolean bedAvailable,
            boolean huntReady,
            int logs,
            int planks,
            int cobblestone,
            int coal,
            int rawIron,
            int ironIngots,
            int pickaxeTier,
            int axeTier,
            boolean craftingTableAvailable,
            boolean furnaceAvailable,
            boolean shelterCompleted,
            boolean shelterReady,
            boolean woodSearchReady,
            boolean stoneSearchReady,
            boolean coalSearchReady,
            boolean ironSearchReady,
            double curiosity) { }

    record Decision(GoalPlanner.Goal goal, String miningMode, double utility,
                    String phase, String reason) { }

    Decision choose(Snapshot s) {
        // Reflex-level needs always outrank the long-term technology tree.
        if (s.foodLevel() <= 12 && !s.hasFood() && s.huntReady()) {
            return decision(GoalPlanner.Goal.HUNT, "", 1.0, "SURVIVAL",
                    "Голод: сначала найти пищу, затем продолжить развитие.");
        }
        if (s.foodLevel() <= 12 && !s.hasFood()) {
            return decision(GoalPlanner.Goal.EXPLORE, "", 0.68, "SURVIVAL",
                    "Рядом не нашёл пищу недавно; меняю маршрут и ищу другие источники еды.");
        }
        if (s.night() && s.bedAvailable()) {
            return decision(GoalPlanner.Goal.SLEEP, "", 0.96, "RECOVERY",
                    "Ночь: безопаснее поспать и восстановить силы.");
        }

        int effectivePlanks = s.planks() + s.logs() * 4;

        // Phase 1: establish the minimum work chain. CraftingController creates
        // planks, sticks, table, axe and wooden pick as soon as materials exist.
        if (s.pickaxeTier() < 1) {
            if (effectivePlanks < 16 || (!s.craftingTableAvailable() && effectivePlanks < 8)) {
                return gatherOrExplore(s.woodSearchReady(), "wood", 0.94, "WOOD_TOOLS",
                        "Этап 1: собираю древесину для досок, верстака, топора и первой кирки.");
            }
            return decision(GoalPlanner.Goal.IDLE, "", 0.72, "WOOD_TOOLS",
                    "Материал для первых инструментов собран: даю системе крафта изготовить и разместить верстак.");
        }

        // Phase 2: a wooden pick unlocks stone. Do not waste time looking for
        // iron before the bot owns a pickaxe capable of mining it.
        if (s.pickaxeTier() < 3) {
            if (s.cobblestone() < 12) {
                return gatherOrExplore(s.stoneSearchReady(), "stone", 0.91, "STONE_TOOLS",
                        "Этап 2: добываю булыжник для каменной кирки, топора и меча.");
            }
            return decision(GoalPlanner.Goal.IDLE, "", 0.74, "STONE_TOOLS",
                    "Камня достаточно: жду изготовления каменных инструментов и проверяю инвентарь.");
        }

        // Phase 3: secure a permanent shelter using only the bot's own items.
        // The exact affordability check is performed by HouseBuilder before
        // placing the first block; these thresholds guide resource collection.
        if (!s.shelterCompleted()) {
            if (s.shelterReady()) {
                return decision(GoalPlanner.Goal.IDLE, "", 0.98, "SHELTER",
                        "Все материалы для убежища собраны: передаю задачу строителю.");
            }
            if (s.planks() < 100) {
                return gatherOrExplore(s.woodSearchReady(), "wood", 0.82, "SHELTER",
                        "Этап 3: собираю доски для собственного дома; после добычи переработаю брёвна.");
            }
            if (s.cobblestone() < 17) {
                return gatherOrExplore(s.stoneSearchReady(), "stone", 0.78, "SHELTER",
                        "Для дома не хватает каменного основания: добываю булыжник.");
            }
            // If the coarse thresholds are met but the builder's exact bill of
            // materials is not, gather more planks rather than pretending ready.
            return gatherOrExplore(s.woodSearchReady(), "wood", 0.66, "SHELTER",
                    "Проверяю недостающие материалы для проекта убежища.");
        }

        // Phase 4: independent progression after a secure home exists.
        if (s.coal() < 8 && s.pickaxeTier() >= 1) {
            return gatherOrExplore(s.coalSearchReady(), "coal", 0.70, "FUEL",
                    "Пополняю запас угля для факелов и плавки руды.");
        }
        int iron = s.rawIron() + s.ironIngots();
        if (iron < 6 && s.pickaxeTier() >= 3) {
            return gatherOrExplore(s.ironSearchReady(), "iron", 0.74, "IRON_TOOLS",
                    "Добываю железо для улучшенных инструментов и экипировки.");
        }
        if (!s.furnaceAvailable() && s.cobblestone() >= 8) {
            return decision(GoalPlanner.Goal.IDLE, "", 0.60, "FURNACE",
                    "Материалы для печи есть: система крафта и размещения должна подготовить плавку.");
        }

        double curiosity = Math.max(0.0, Math.min(1.0, s.curiosity()));
        double exploreUtility = 0.42 + curiosity * 0.34;
        return decision(GoalPlanner.Goal.EXPLORE, "", exploreUtility, "DISCOVERY",
                "Основные нужды закрыты: ищу новые биомы, ресурсы, животных и полезные места.");
    }

    private Decision gatherOrExplore(boolean ready, String mode, double utility,
                                     String phase, String reason) {
        if (!ready) {
            return decision(GoalPlanner.Goal.EXPLORE, "", utility * 0.82, phase,
                    "В знакомом радиусе ресурс не найден; меняю район поиска. " + reason);
        }
        return decision(GoalPlanner.Goal.MINE, mode, utility, phase, reason);
    }

    private Decision decision(GoalPlanner.Goal goal, String mode, double utility,
                              String phase, String reason) {
        return new Decision(goal, mode, utility, phase, reason);
    }
}
