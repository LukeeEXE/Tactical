package dev.tactical.engine.match;

public enum MatchEndReason {
    /** Played to completion. */
    COMPLETED,
    /** Ended by an administrator. */
    ADMIN_ENDED,
    /** Ended because of an internal error (illegal state, exception). */
    ERROR,
    /** Server restarted and the match could not be resumed. */
    INTERRUPTED,
    /** A team forfeited: it voted to give up, or had no connected players for longer than the forfeit window. */
    FORFEIT
}
