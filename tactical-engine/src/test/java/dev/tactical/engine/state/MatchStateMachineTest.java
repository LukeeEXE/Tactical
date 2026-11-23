package dev.tactical.engine.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchStateMachineTest {

    @Test
    void illegalTransitionThrows() {
        MatchStateMachine m = new MatchStateMachine();
        assertThrows(IllegalStateTransitionException.class, () -> m.transition(MatchState.ROUND_LIVE));
        m.transition(MatchState.LOADING);
        assertEquals(MatchState.LOADING, m.current());
    }

    @Test
    void everyStateCanReachClosed() {
        for (MatchState s : MatchState.values()) {
            assertTrue(reachesClosed(s, new java.util.HashSet<>()), s + " cannot reach CLOSED");
        }
    }

    private boolean reachesClosed(MatchState s, java.util.Set<MatchState> seen) {
        if (s == MatchState.CLOSED) return true;
        if (!seen.add(s)) return false;
        for (MatchState n : MatchStateMachine.allowedFrom(s)) if (reachesClosed(n, seen)) return true;
        return false;
    }
}
