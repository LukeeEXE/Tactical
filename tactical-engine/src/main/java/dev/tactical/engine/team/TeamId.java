package dev.tactical.engine.team;

/**
 * A team's ORIGINAL identity, fixed for the whole match.
 * {@link #A} always started as attackers (red spectator outline), {@link #B} as defenders (blue).
 * The side a team currently plays is tracked separately by {@link Side}.
 */
public enum TeamId {
    A, B;

    public TeamId other() {
        return this == A ? B : A;
    }

    /** Side this team plays in the first half. */
    public Side startingSide() {
        return this == A ? Side.ATTACKERS : Side.DEFENDERS;
    }
}
