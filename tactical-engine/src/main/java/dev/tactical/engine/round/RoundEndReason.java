package dev.tactical.engine.round;

public enum RoundEndReason {
    BOMB_DEFUSED,
    BOMB_DETONATED,
    /** Bomb planted and every defender is dead, so nobody can defuse. */
    DEFENDERS_ELIMINATED_AFTER_PLANT,
    ATTACKERS_ELIMINATED,
    DEFENDERS_ELIMINATED,
    TIME_EXPIRED,
    /** Ended by an administrator (/game round end). */
    FORCED
}
