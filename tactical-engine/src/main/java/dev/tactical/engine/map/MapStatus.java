package dev.tactical.engine.map;

public enum MapStatus {
    /** Being edited; never selectable. */
    DRAFT,
    /** Passed validation since its last edit; selectable in test matches. */
    VALIDATED,
    /** Released; selectable by matchmaking if enabled in a pool. */
    PUBLISHED
}
