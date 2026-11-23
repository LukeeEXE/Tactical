package dev.tactical.engine.kit;

public enum KitSelectionPhase {
    /** Before round 1: any enabled kit. */
    MATCH_START,
    /** Halftime, before the first round of the second half: any enabled kit. */
    SECOND_HALF,
    /** Before sudden death: only the player's first-half or second-half kit. */
    SUDDEN_DEATH
}
