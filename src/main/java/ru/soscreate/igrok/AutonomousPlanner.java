package ru.soscreate.igrok;

/**
 * Multi-project survival planner.
 *
 * There is no mandatory linear progression. Each planning pass scores several
 * independent projects (equipment, food, shelter, fuel, iron, exploration) and
 * chooses the best currently useful one. Missing resources lower a project's
 * score or redirect it to exploration instead of pinning the agent to a dead end.
 *
 * Actual crafting, mining, building and movement remain in the controllers;
 * this class only chooses the next verified intention.
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
            int charcoal,
            int torches,
            int rawIron,
            int ironIngots,
            int pickaxeTier,
            int axeTier,
            boolean craftingTableAvailable,
            boolean furnaceAvailable,
            boolean shelterCompleted,
            boolean shelterReady,
            int buildingSkill,
            boolean watchtowerCompleted,
            boolean workshopCompleted,
            boolean woodSearchReady,
            boolean stoneSearchReady,
            boolean coalSearchReady,
            boolean ironSearchReady,
            double curiosity,
            double creativity,
            int developmentStage,
            String preferredBranch,
            boolean workshopUnlocked,
            int redstoneDust,
            boolean redstoneSearchReady,
            int pistons,
            int redstoneBlocks,
            boolean pistonLabUnlocked,
            boolean pistonLabCompleted,
            int shelterMissingPlanks,
            int shelterMissingCobblestone,
            int projectMissingCobblestone,
            int projectMissingPiston,
            int projectMissingRedstoneBlock,
            boolean projectReady) { }

    record Decision(GoalPlanner.Goal goal, String miningMode, double utility,
                    String phase, String reason) { }

    Decision choose(Snapshot s) {
        double curiosity = clamp(s.curiosity());
        double creativity = clamp(s.creativity());
        int stage = Math.max(0, Math.min(4, s.developmentStage()));
        double independentBias = stage >= 3 ? 0.08 + (stage - 3) * 0.05 : 0.0;
        double explorerBias = "exploration".equals(s.preferredBranch()) ? 0.06 : 0.0;
        double survivalBias = "survival".equals(s.preferredBranch()) ? 0.07 : 0.0;
        double craftBias = "craft".equals(s.preferredBranch()) ? 0.07 : 0.0;
        boolean socialFocus = "social".equals(s.preferredBranch());
        Decision best = decision(GoalPlanner.Goal.EXPLORE, "",
                Math.min(0.86, 0.38 + curiosity * 0.25 + independentBias + explorerBias),
                stage >= 3 ? "INDEPENDENT_LIFE" : "DISCOVERY",
                stage >= 3
                        ? "Основы освоены. Я сам выбираю занятия: могу отправиться в путь, осмотреть окрестности или заняться новым делом."
                        : "Ищу новые места и возможности; дальнейший выбор зависит от того, что найду.");

        // Immediate survival is a hard constraint, not just another optional project.
        if (s.foodLevel() <= 8 && !s.hasFood() && s.huntReady()) {
            return decision(GoalPlanner.Goal.HUNT, "", 1.0, "SURVIVAL",
                    "Еда стала срочной задачей: сначала найду пищу, а затем вернусь к другим проектам.");
        }
        if (s.foodLevel() <= 12 && !s.hasFood()) {
            Decision foodDecision = s.huntReady()
                    ? decision(GoalPlanner.Goal.HUNT, "", 0.91 + survivalBias * 0.5, "SURVIVAL",
                        "Голод: ищу животное, но не трачу последние силы на дальний поход.")
                    : decision(GoalPlanner.Goal.EXPLORE, "", 0.82, "SURVIVAL",
                        "Рядом пищи не нашлось недавно; меняю район поиска.");
            best = better(best, foodDecision);
        }
        if (s.night() && s.bedAvailable()) {
            best = better(best, decision(GoalPlanner.Goal.SLEEP, "", 0.97, "REST",
                    "Наступила ночь, рядом есть кровать: безопаснее восстановиться."));
        }

        int usableWood = s.logs() * 4 + s.planks();

        // Equipment is a project with a short dependency chain, not a fixed stage.
        // If the wooden pick is missing, wood is useful only until a real tool can be crafted.
        if (s.pickaxeTier() < 1) {
            if (usableWood < 10) {
                best = better(best, collect(s.woodSearchReady(), "wood", 0.92,
                        "BASIC_TOOLS", "Для первой кирки и рабочего места не хватает древесины.", true));
            } else {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.84, "BASIC_TOOLS",
                        "Древесины уже достаточно: позволяю системе крафта изготовить верстак, палки и деревянные инструменты."));
            }
        } else if (s.pickaxeTier() < 3) {
            if (s.cobblestone() < 3) {
                best = better(best, collect(s.stoneSearchReady(), "stone", 0.86 + craftBias * 0.4,
                        "STONE_TOOLS", "Каменная кирка откроет больше рецептов и безопасную добычу руд.", false));
            } else if (s.cobblestone() < 8) {
                best = better(best, collect(s.stoneSearchReady(), "stone", 0.74 + craftBias,
                        "STONE_TOOLS", "Добираю камень для кирки, печи или другого полезного снаряжения.", false));
            } else if (s.cobblestone() < 12) {
                best = better(best, collect(s.stoneSearchReady(), "stone", 0.61,
                        "STONE_TOOLS", "Есть смысл сделать небольшой запас камня, но можно заняться и другими делами.", false));
            } else {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.72, "STONE_TOOLS",
                        "Камня достаточно: крафтую улучшенные инструменты и не добываю лишнее."));
            }
        }

        // An axe is an independent quality-of-life upgrade. It need not wait for a house.
        if (s.axeTier() < 1 && usableWood < 14) {
            best = better(best, collect(s.woodSearchReady(), "wood", 0.57,
                    "WOODCUTTING", "Топор ускорит сбор древесины; пополню запас, если дерево доступно.", false));
        } else if (s.axeTier() < 3 && s.pickaxeTier() >= 1 && s.cobblestone() >= 3) {
            best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.49, "WOODCUTTING",
                    "Материалы для каменного топора уже есть: проверяю возможность его изготовить."));
        }

        // Fuel has two interchangeable routes: mine coal, or turn logs into charcoal.
        int fuel = s.coal() + s.charcoal();
        if (s.torches() < 12 && fuel < 4) {
            if (s.furnaceAvailable() && s.logs() > 0) {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.68, "CHARCOAL",
                        "Уголь искать необязательно: использую печь и древесину, чтобы получать древесный уголь."));
            } else if (s.pickaxeTier() >= 1) {
                best = better(best, collect(s.coalSearchReady(), "coal", 0.63 + craftBias * 0.8,
                        "FUEL", "Нужен свет и топливо: проверяю уголь, если путь к нему сейчас доступен.", false));
            } else if (s.cobblestone() >= 8 && s.craftingTableAvailable()) {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.57, "FURNACE",
                        "Сначала изготовлю печь из уже имеющегося камня; дальше можно получать древесный уголь."));
            }
        }

        // Iron is optional until the agent has a stone pick. No pointless digging without it.
        int iron = s.rawIron() + s.ironIngots();
        if (s.pickaxeTier() >= 3 && iron < 6) {
            best = better(best, collect(s.ironSearchReady(), "iron", 0.61 + craftBias,
                    "IRON", "Железо даст более прочные инструменты, но я ищу его только с подходящей киркой.", false));
        }

        // Furnace is a utility project, not a requirement for every other action.
        if (!s.furnaceAvailable() && s.cobblestone() >= 8 && s.craftingTableAvailable()) {
            best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.59, "FURNACE",
                    "Материалы для печи собраны: создаю возможность готовить еду и получать древесный уголь."));
        }

        // A home is an attractive project when affordable, but never blocks exploration
        // or equipment development just because the builder has not completed it.
        if (!s.shelterCompleted()) {
            if (s.shelterReady()) {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.98, "SHELTER_READY",
                        "Все материалы для убежища собраны. Перехожу к строительству."));
            } else if (s.shelterMissingPlanks() > 0) {
                best = better(best, collect(s.woodSearchReady(), "wood", 0.91,
                        "SHELTER_WOOD", "Проверил план дома: не хватает " + s.shelterMissingPlanks()
                                + " блоков древесины. Иду рубить деревья.", true));
            } else if (s.shelterMissingCobblestone() > 0 && s.pickaxeTier() >= 1) {
                best = better(best, collect(s.stoneSearchReady(), "stone", 0.90,
                        "SHELTER_STONE", "Проверил план дома: не хватает " + s.shelterMissingCobblestone()
                                + " булыжника для основания. Иду добывать камень.", true));
            }
        }

        // Learned construction projects become new goals only after the shelter
        // and enough hands-on building experience. Each project competes with
        // upgrades, fuel and discovery instead of blocking them.
        // Construction branches are optional. A craft-oriented agent may choose
        // a workshop without first completing a watchtower; neither project
        // blocks exploration or the other development branches.
        if (s.shelterCompleted() && s.workshopUnlocked() && !s.workshopCompleted()) {
            if (usableWood < 24) {
                best = better(best, collect(s.woodSearchReady(), "wood", 0.64,
                        "WORKSHOP", "Я выбрал необязательный проект мастерской. Собираю материалы, но могу переключиться на другое занятие.", false));
            } else if (s.cobblestone() < 9) {
                best = better(best, collect(s.stoneSearchReady(), "stone", 0.62,
                        "WORKSHOP", "Для мастерской нужно каменное основание; поищу камень в доступной местности.", false));
            } else {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.75, "WORKSHOP",
                        "Материалы для мастерской собраны. Можно оборудовать ещё одно полезное место."));
            }
        }
        if (s.shelterCompleted() && s.buildingSkill() >= 2 && !s.watchtowerCompleted()) {
            if (usableWood < 29) {
                best = better(best, collect(s.woodSearchReady(), "wood", 0.67,
                        "LOOKOUT", "Смотровая башня — ещё один необязательный проект. Постепенно собираю доски.", false));
            } else if (s.cobblestone() < 9) {
                best = better(best, collect(s.stoneSearchReady(), "stone", 0.65,
                        "LOOKOUT", "Для основания смотровой башни не хватает камня; сначала найду безопасный участок.", false));
            } else {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.76, "LOOKOUT",
                        "Ресурсы для смотровой башни уже есть. Хочу построить точку обзора и потренировать высотную постройку."));
            }
        }
        if (s.pistonLabUnlocked() && !s.pistonLabCompleted()) {
            if (s.projectMissingCobblestone() > 0 && s.stoneSearchReady()
                    && s.pickaxeTier() >= 1) {
                best = better(best, collect(true, "stone", 0.88,
                        "PISTON_LAB_STONE", "Для поршневого стенда не хватает "
                                + s.projectMissingCobblestone() + " блоков булыжника.", false));
            }
            if (s.redstoneDust() < 9 && s.pickaxeTier() >= 4) {
                best = better(best, collect(s.redstoneSearchReady(), "redstone", 0.86,
                        "REDSTONE", "Готовлю пыль редстоуна для питания поршневого механизма.", false));
            }
            if (s.projectMissingPiston() > 0 && s.ironIngots() < 1
                    && s.pickaxeTier() >= 3) {
                best = better(best, collect(s.ironSearchReady(), "iron", 0.84,
                        "PISTON_IRON", "Для поршня не хватает железа; добываю руду, чтобы изготовить механизм.", false));
            } else if (s.projectMissingPiston() > 0) {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.85, "PISTON_CRAFT",
                        "Для стенда нужен поршень. Проверяю ингредиенты и изготавливаю его из своего инвентаря."));
            }
            if (s.projectMissingRedstoneBlock() > 0 && s.redstoneDust() >= 9) {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.87, "REDSTONE_BLOCK",
                        "Поршень готов. Собираю блок редстоуна как источник питания испытательного стенда."));
            }
            if (s.projectReady()) {
                best = better(best, decision(GoalPlanner.Goal.IDLE, "", 0.97, "PISTON_LAB_READY",
                        "Все детали готовы. Можно собрать и испытать поршневой механизм."));
            }
        }
        if (stage >= 4 && !s.night() && s.foodLevel() >= 14) {
            best = better(best, decision(GoalPlanner.Goal.IDLE, "",
                    Math.min(0.90, 0.58 + creativity * 0.18 + (socialFocus ? 0.08 : 0.0)),
                    "PERSONAL_TIME", "Мне не обязательно постоянно добывать ресурсы: иногда полезно осмотреться, передохнуть и пожить обычной жизнью."));
        }

        // A lack of a nearby resource must never become a permanent wait state.
        // The caller moves to a new area and cooldowns prevent immediate retry loops.
        return best;
    }

    private Decision collect(boolean searchReady, String mode, double utility,
                             String phase, String reason, boolean fallbackToExplore) {
        if (searchReady) {
            return decision(GoalPlanner.Goal.MINE, mode, utility, phase, reason);
        }
        double exploreUtility = Math.min(0.88, utility * (fallbackToExplore ? 0.86 : 0.91));
        return decision(GoalPlanner.Goal.EXPLORE, "", exploreUtility, phase,
                "В доступном районе этого ресурса сейчас недостаточно; меняю местность. " + reason);
    }

    private Decision better(Decision current, Decision candidate) {
        return candidate.utility() > current.utility() ? candidate : current;
    }

    private Decision decision(GoalPlanner.Goal goal, String mode, double utility,
                              String phase, String reason) {
        return new Decision(goal, mode, utility, phase, reason);
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) return 0.5;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
