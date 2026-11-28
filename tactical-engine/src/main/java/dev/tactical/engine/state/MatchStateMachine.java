package dev.tactical.engine.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static dev.tactical.engine.state.MatchState.*;

/**
 * The single source of truth for which state transitions are legal. Anything not listed here
 * throws {@link IllegalStateTransitionException}; the caller aborts the match safely.
 */
public final class MatchStateMachine {

    private static final Map<MatchState, Set<MatchState>> ALLOWED = new EnumMap<>(MatchState.class);

    static {
        allow(CREATED, LOADING, ABORTED);
        allow(LOADING, KIT_SELECT, ABORTED);
        allow(KIT_SELECT, BUY_PHASE, MATCH_END, ABORTED); // MATCH_END: a team forfeits
        allow(BUY_PHASE, ROUND_LIVE, ROUND_END, MATCH_END, ABORTED); // ROUND_END: admin force-end during buy
        allow(ROUND_LIVE, BOMB_PLANTED, ROUND_END, MATCH_END, ABORTED);
        allow(BOMB_PLANTED, ROUND_END, MATCH_END, ABORTED);
        allow(ROUND_END, BUY_PHASE, HALFTIME, SUDDEN_DEATH_KIT_SELECT, MATCH_END, ABORTED);
        allow(HALFTIME, BUY_PHASE, MATCH_END, ABORTED);
        allow(SUDDEN_DEATH_KIT_SELECT, BUY_PHASE, MATCH_END, ABORTED);
        allow(MATCH_END, POSTGAME, CLEANUP, ABORTED);
        allow(POSTGAME, CLEANUP, ABORTED);
        allow(ABORTED, CLEANUP);
        allow(CLEANUP, CLOSED);
        allow(CLOSED);
    }

    private static void allow(MatchState from, MatchState... to) {
        Set<MatchState> set = to.length == 0 ? EnumSet.noneOf(MatchState.class) : EnumSet.of(to[0], to);
        ALLOWED.put(from, Collections.unmodifiableSet(set));
    }

    private MatchState current = CREATED;

    public MatchState current() {
        return current;
    }

    public static boolean isAllowed(MatchState from, MatchState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<MatchState> allowedFrom(MatchState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    /** Transitions or throws. Returns the previous state. */
    public MatchState transition(MatchState to) {
        if (!isAllowed(current, to)) {
            throw new IllegalStateTransitionException(current, to);
        }
        MatchState previous = current;
        current = to;
        return previous;
    }

    /** Used only by crash recovery to restore a checkpointed state. */
    public void restore(MatchState state) {
        current = state;
    }
}
