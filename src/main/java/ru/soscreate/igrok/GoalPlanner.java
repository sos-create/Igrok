package ru.soscreate.igrok;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lightweight hybrid reasoner.
 *
 * 1) Hard IF/THEN rules protect survival and override voluntary goals.
 * 2) A small utility layer compares possible actions instead of relying on
 *    one long priority list.
 * 3) Goal inertia avoids changing plans every few ticks without a good reason.
 *
 * Language associations and learned dialogue remain in CognitiveNetwork;
 * this class reasons over facts perceived in the Minecraft world.
 */
final class GoalPlanner {
    enum Goal { IDLE, FLEE, DEFEND, FOLLOW, MINE, HUNT, SLEEP, EXPLORE }

    record Situation(double health, double maxHealth, double threatDistance,
                     boolean threatVisible, boolean followRequested,
                     boolean miningRequested, boolean explorationRequested,
                     boolean attackRequested, double bravery, double curiosity,
                     Goal currentGoal) { }

    record Decision(Goal goal, double confidence, String reason) { }

    Decision evaluate(Situation s) {
        double maxHealth = Math.max(1.0, s.maxHealth());
        double healthRatio = clamp(s.health() / maxHealth, 0.0, 1.0);
        double distance = Double.isFinite(s.threatDistance())
                ? Math.max(0.0, s.threatDistance()) : Double.POSITIVE_INFINITY;
        double danger = s.threatVisible()
                ? clamp(1.0 - distance / 14.0, 0.0, 1.0) : 0.0;

        // Absolute truth-table rule: immediate survival outranks every order.
        if (s.threatVisible()
                && (s.health() <= 7.0 || healthRatio <= 0.40
                    || (healthRatio <= 0.52 && distance <= 8.0))) {
            return new Decision(Goal.FLEE, 1.0,
                    "ПРАВИЛО: здоровье уже опасно низкое относительно угрозы — сначала отступить и восстановиться.");
        }

        // A close threat or deliberate defense request can trigger combat,
        // but never bypasses the critical-health rule above.
        if (s.threatVisible()
                && (s.attackRequested() || distance <= 7.0 || s.bravery() > 0.78)) {
            return new Decision(Goal.DEFEND, 0.94,
                    "ПРАВИЛО: угроза обнаружена И (она близко ИЛИ включена защита ИЛИ смелость высока) — защищаться.");
        }

        Map<Goal, Double> scores = new EnumMap<>(Goal.class);
        scores.put(Goal.IDLE, 0.14);

        double fleeScore = s.threatVisible()
                ? 0.18 + danger * 0.28 + (1.0 - healthRatio) * 0.56
                    + (healthRatio < 0.58 ? 0.18 : 0.0)
                : 0.0;
        scores.put(Goal.FLEE, fleeScore);

        double defendScore = s.threatVisible()
                ? 0.14 + clamp(s.bravery(), 0.0, 1.0) * 0.27
                    + danger * 0.20 + healthRatio * 0.13
                : 0.0;
        scores.put(Goal.DEFEND, defendScore);

        scores.put(Goal.FOLLOW, s.followRequested() ? 0.78 : 0.0);
        scores.put(Goal.MINE, s.miningRequested() ? 0.78 : 0.0);

        double curiosity = clamp(s.curiosity(), 0.0, 1.0);
        double exploreScore = s.explorationRequested()
                ? 0.52 + curiosity * 0.24
                : Math.max(0.0, curiosity - 0.72) * 0.45;
        scores.put(Goal.EXPLORE, exploreScore);

        // Keep a valid ongoing plan unless a new option is meaningfully better.
        Goal previous = s.currentGoal() == null ? Goal.IDLE : s.currentGoal();
        if (previous != Goal.FLEE && scores.containsKey(previous)) {
            scores.put(previous, scores.get(previous) + 0.10);
        }

        Goal bestGoal = Goal.IDLE;
        double bestScore = -1.0;
        double secondScore = -1.0;
        for (Goal candidate : Goal.values()) {
            double score = clamp(scores.getOrDefault(candidate, 0.0), 0.0, 1.0);
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestGoal = candidate;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        String reason = switch (bestGoal) {
            case IDLE -> "Нет срочной угрозы и ни одна добровольная цель не набрала достаточный приоритет — наблюдать.";
            case FLEE -> "Оценка риска выше пользы остальных действий — отступить.";
            case DEFEND -> "Оценка угрозы и готовности к бою выше остальных вариантов — защищаться.";
            case FOLLOW -> "Есть активная цель сопровождения — идти за игроком.";
            case MINE -> "Запрошена добыча, а более важной угрозы или цели нет — добывать.";
            case HUNT -> "Нужна пища, поэтому следует добыть её охотой.";
            case SLEEP -> "Наступила ночь и есть доступная кровать — восстановить силы.";
            case EXPLORE -> "Любопытство и интерес к исследованию перевесили другие варианты — исследовать.";
        };
        double confidence = clamp(bestScore * 0.72
                + Math.max(0.0, bestScore - Math.max(0.0, secondScore)) * 0.28, 0.0, 1.0);
        return new Decision(bestGoal, confidence, reason);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
