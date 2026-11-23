package dev.tactical.engine.mode;

/** Which sides teams play in the sudden-death round. */
public enum SuddenDeathSides {
    /** Keep second-half sides. */
    KEEP,
    /** Swap back to first-half sides. */
    SWAP,
    /** Fair coin flip. */
    RANDOM
}
