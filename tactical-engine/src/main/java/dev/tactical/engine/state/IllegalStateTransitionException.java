package dev.tactical.engine.state;

public final class IllegalStateTransitionException extends IllegalStateException {
    private final MatchState from;
    private final MatchState to;

    public IllegalStateTransitionException(MatchState from, MatchState to) {
        super("Illegal match state transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public MatchState from() {
        return from;
    }

    public MatchState to() {
        return to;
    }
}
