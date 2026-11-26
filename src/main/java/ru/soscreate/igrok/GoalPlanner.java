package ru.soscreate.igrok;

/** Utility-based goal selection. Safety overrides all voluntary activities. */
final class GoalPlanner {
    enum Goal { IDLE, FLEE, DEFEND, FOLLOW, MINE, EXPLORE }

    record Situation(double health, double maxHealth, double threatDistance,
                     boolean threatVisible, boolean followRequested,
                     boolean miningRequested, boolean explorationRequested,
                     double bravery, double curiosity) { }

    Goal choose(Situation s) {
        if (s.threatVisible() && s.health() <= Math.max(6.0, s.maxHealth() * 0.32)) {
            return Goal.FLEE;
        }
        if (s.threatVisible() && (s.threatDistance() <= 7.0 || s.bravery() > 0.78)) {
            return Goal.DEFEND;
        }
        if (s.followRequested()) return Goal.FOLLOW;
        if (s.miningRequested()) return Goal.MINE;
        if (s.explorationRequested() || s.curiosity() > 0.82) return Goal.EXPLORE;
        return Goal.IDLE;
    }
}
