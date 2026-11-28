package dev.tactical.engine.match;

import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.TeamId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForfeitTest {

    @Test
    void forfeitOnlyAfterHalfOfTheFirstHalf() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, dev.tactical.engine.mode.SuddenDeathSides.RANDOM), 5);
        m.start();
        MatchFlow flow = m.flow;
        assertEquals(MatchState.KIT_SELECT, flow.state());
        assertTrue(flow.skipVoteAvailable());
        assertEquals(2, flow.forfeitMinRounds());
        assertFalse(flow.forfeit(TeamId.A)); // no round played yet
        m.runUntil(f -> f.roundsPlayed() >= 1 && f.state() == MatchState.BUY_PHASE);
        assertFalse(flow.forfeit(TeamId.A)); // 1 of 2
        m.runUntil(f -> f.roundsPlayed() >= 2 && f.state() == MatchState.BUY_PHASE);
        assertTrue(flow.forfeit(TeamId.A));
        assertEquals(MatchState.MATCH_END, flow.state());
        assertEquals(TeamId.B, flow.winner().orElseThrow());
        assertEquals(MatchEndReason.FORFEIT, flow.endReason().orElseThrow());
        assertFalse(flow.forfeit(TeamId.B)); // already over
    }
}
