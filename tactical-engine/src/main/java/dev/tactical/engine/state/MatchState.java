package dev.tactical.engine.state;

/**
 * Every state a match can be in. PAUSED is NOT a state: it is an overlay flag that freezes
 * whatever state the match is in (see {@code MatchFlow#pause}).
 */
public enum MatchState {
    CREATED,
    LOADING,
    KIT_SELECT,
    BUY_PHASE,
    ROUND_LIVE,
    BOMB_PLANTED,
    ROUND_END,
    HALFTIME,
    SUDDEN_DEATH_KIT_SELECT,
    MATCH_END,
    POSTGAME,
    CLEANUP,
    CLOSED,
    ABORTED;

    public boolean isTerminal() {
        return this == CLOSED;
    }

    /** States in which a round is being played (players are alive and can fight). */
    public boolean isRoundActive() {
        return this == ROUND_LIVE || this == BOMB_PLANTED;
    }

    /** States in which players are selecting kits. */
    public boolean isKitSelection() {
        return this == KIT_SELECT || this == HALFTIME || this == SUDDEN_DEATH_KIT_SELECT;
    }
}
