package dev.tactical.engine.kit;

/** Outcome of a select/lock request. Everything except OK is a rejection with a reason key for messages. */
public enum KitSelectionResult {
    OK,
    NOT_A_PARTICIPANT,
    ALREADY_LOCKED,
    KIT_NOT_AVAILABLE,
    TAKEN_BY_TEAMMATE,
    /** Locking this kit would leave an unlocked teammate with no valid option. */
    WOULD_STRAND_TEAMMATE,
    SELECTION_CLOSED
}
