package dev.tactical.engine.match;

import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.mode.SuddenDeathSides;
import dev.tactical.engine.round.BombStatus;
import dev.tactical.engine.round.RoundEndReason;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchFlowTest {

    @Test
    void quickplayAllTimeoutsGoesToSuddenDeath() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 5);
        m.start();
        m.runUntilState(MatchState.CLEANUP);

        // Every round times out, so the defending team always wins: B wins 1-4, A wins 5-8, 4-4 → sudden death.
        List<MatchFlowEvent.RoundEnded> rounds = m.eventsOf(MatchFlowEvent.RoundEnded.class);
        assertEquals(9, rounds.size());
        for (int i = 0; i < 4; i++) assertEquals(TeamId.B, rounds.get(i).winnerTeam());
        for (int i = 4; i < 8; i++) assertEquals(TeamId.A, rounds.get(i).winnerTeam());
        rounds.forEach(r -> assertEquals(RoundEndReason.TIME_EXPIRED, r.reason()));
        assertEquals(1, m.eventsOf(MatchFlowEvent.HalftimeStarted.class).size());
        assertEquals(1, m.eventsOf(MatchFlowEvent.SuddenDeathStarted.class).size());
        // KEEP: A stays defender in sudden death and wins it.
        assertEquals(TeamId.A, m.flow.winner().orElseThrow());
        assertEquals(5, m.flow.scoreOf(TeamId.A));
        assertEquals(4, m.flow.scoreOf(TeamId.B));
        assertEquals(MatchEndReason.COMPLETED, m.flow.endReason().orElseThrow());
        // Three kit selections: start, halftime, sudden death.
        assertEquals(3, m.eventsOf(MatchFlowEvent.KitSelectionOpened.class).size());
        m.flow.markClosed();
        assertEquals(MatchState.CLOSED, m.flow.state());
    }

    @Test
    void competitiveSuddenDeathIsRound17() {
        TestMatches m = new TestMatches(TestMatches.fast(9, 8, 8, SuddenDeathSides.SWAP), 5);
        m.start();
        m.runUntilState(MatchState.CLEANUP);
        assertEquals(17, m.eventsOf(MatchFlowEvent.RoundEnded.class).size());
        // SWAP: A attacks again in sudden death, times out → B (defending) wins 9-8.
        assertEquals(TeamId.B, m.flow.winner().orElseThrow());
        assertEquals(9, m.flow.scoreOf(TeamId.B));
    }

    @Test
    void realDefaultsAreValid() {
        assertEquals(5, ModeRules.quickplayDefaults().roundsToWin());
        assertEquals(8, ModeRules.competitiveDefaults().halftimeAfterRound());
    }

    @Test
    void sidesSwapAtHalftimeButTeamIdentityDoesNot() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 2);
        m.start();
        assertEquals(Side.ATTACKERS, m.flow.sideOf(TeamId.A));
        m.runUntilState(MatchState.HALFTIME);
        assertEquals(Side.DEFENDERS, m.flow.sideOf(TeamId.A));
        assertEquals(TeamId.A, m.teamA.getFirst().team());
    }

    @Test
    void eliminationsEndRoundAndFiveZeroSkipsSuddenDeath() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 5);
        m.start();
        for (int r = 0; r < 5; r++) {
            m.runUntilState(MatchState.ROUND_LIVE);
            for (Participant b : m.teamB) assertTrue(m.flow.eliminate(b.id(), m.teamA.getFirst().id(), EliminationCause.KILLED));
            m.flow.tick();
            assertEquals(MatchState.ROUND_END, m.flow.state());
        }
        m.runUntilState(MatchState.CLEANUP);
        assertEquals(TeamId.A, m.flow.winner().orElseThrow());
        assertEquals(5, m.flow.scoreOf(TeamId.A));
        assertEquals(0, m.eventsOf(MatchFlowEvent.SuddenDeathStarted.class).size());
        List<MatchFlowEvent.RoundEnded> rounds = m.eventsOf(MatchFlowEvent.RoundEnded.class);
        assertEquals(RoundEndReason.DEFENDERS_ELIMINATED, rounds.getFirst().reason());
        assertEquals(RoundEndReason.ATTACKERS_ELIMINATED, rounds.get(4).reason()); // A defends in round 5
    }

    @Test
    void plantedBombContinuesAfterAttackersDieAndDefuseWins() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 2);
        m.start();
        m.runUntilState(MatchState.ROUND_LIVE);
        UUID planter = m.teamA.getFirst().id();
        assertTrue(m.flow.bombPlanted(planter, "A"));
        assertEquals(MatchState.BOMB_PLANTED, m.flow.state());
        for (Participant a : m.teamA) m.flow.eliminate(a.id(), null, EliminationCause.KILLED);
        m.flow.tick();
        assertEquals(MatchState.BOMB_PLANTED, m.flow.state(), "round must continue while bomb is planted");
        assertFalse(m.flow.bombDefused(m.teamA.get(1).id()), "attackers cannot defuse");
        assertTrue(m.flow.bombDefused(m.teamB.getFirst().id()));
        m.flow.tick();
        MatchFlowEvent.RoundEnded end = m.eventsOf(MatchFlowEvent.RoundEnded.class).getFirst();
        assertEquals(RoundEndReason.BOMB_DEFUSED, end.reason());
        assertEquals(TeamId.B, end.winnerTeam());
    }

    @Test
    void bombDetonatesAndDefendersEliminatedAfterPlant() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 2);
        m.start();
        m.runUntilState(MatchState.ROUND_LIVE);
        m.flow.bombPlanted(m.teamA.getFirst().id(), "B");
        m.runUntilState(MatchState.ROUND_END);
        assertEquals(RoundEndReason.BOMB_DETONATED, m.eventsOf(MatchFlowEvent.RoundEnded.class).getFirst().reason());

        m.runUntilState(MatchState.ROUND_LIVE);
        m.flow.bombPlanted(m.teamA.getFirst().id(), "B");
        for (Participant b : m.teamB) m.flow.eliminate(b.id(), null, EliminationCause.KILLED);
        m.flow.tick();
        assertEquals(RoundEndReason.DEFENDERS_ELIMINATED_AFTER_PLANT, m.eventsOf(MatchFlowEvent.RoundEnded.class).get(1).reason());
    }

    @Test
    void defuseOnDetonationTickCountsAsDefused() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        UUID defuser = m.teamB.getFirst().id();
        m.flow.addTickable(f -> {
            if (f.state() == MatchState.BOMB_PLANTED && f.stateTicksRemaining() == 1) f.bombDefused(defuser);
        });
        m.start();
        m.runUntilState(MatchState.ROUND_LIVE);
        m.flow.bombPlanted(m.teamA.getFirst().id(), "A");
        m.runUntilState(MatchState.ROUND_END);
        assertEquals(BombStatus.DEFUSED, m.flow.bomb());
        assertTrue(m.eventsOf(MatchFlowEvent.BombDetonated.class).isEmpty());
    }

    @Test
    void plantOnTimerExpiryTickCountsAsPlanted() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        UUID planter = m.teamA.getFirst().id();
        m.flow.addTickable(f -> {
            if (f.state() == MatchState.ROUND_LIVE && f.stateTicksRemaining() == 1) f.bombPlanted(planter, "A");
        });
        m.start();
        m.runUntilState(MatchState.ROUND_END);
        assertNotEquals(RoundEndReason.TIME_EXPIRED, m.eventsOf(MatchFlowEvent.RoundEnded.class).getFirst().reason());
    }

    @Test
    void invalidPlantsAreRejected() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        m.start();
        assertFalse(m.flow.bombPlanted(m.teamA.getFirst().id(), "A"), "not during kit select");
        m.runUntilState(MatchState.BUY_PHASE);
        assertFalse(m.flow.bombPlanted(m.teamA.getFirst().id(), "A"), "not during buy");
        m.runUntilState(MatchState.ROUND_LIVE);
        assertFalse(m.flow.bombPlanted(m.teamB.getFirst().id(), "A"), "defenders cannot plant");
        assertFalse(m.flow.bombPlanted(UUID.randomUUID(), "A"), "outsiders cannot plant");
    }

    @Test
    void pauseFreezesEverythingAndResumeCountsDown() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        m.start();
        m.runUntilState(MatchState.ROUND_LIVE);
        m.flow.tick();
        int remaining = m.flow.stateTicksRemaining();
        long clock = m.flow.currentTick();
        assertTrue(m.flow.pause(null, "test"));
        assertFalse(m.flow.pause(null, "again"), "double pause is a no-op");
        for (int i = 0; i < 500; i++) m.flow.tick();
        assertEquals(remaining, m.flow.stateTicksRemaining());
        assertEquals(clock, m.flow.currentTick());
        assertFalse(m.flow.eliminate(m.teamB.getFirst().id(), null, EliminationCause.KILLED), "no combat while paused");
        assertTrue(m.flow.resume(null, 60));
        for (int i = 0; i < 59; i++) m.flow.tick();
        assertTrue(m.flow.isPaused(), "still frozen during countdown");
        m.flow.tick();
        assertFalse(m.flow.isPaused());
        assertEquals(560, m.flow.pausedTicks());
        assertEquals(560, m.eventsOf(MatchFlowEvent.Resumed.class).getFirst().pausedTicks());
        m.flow.tick();
        assertEquals(remaining - 1, m.flow.stateTicksRemaining());
    }

    @Test
    void multiplePausesDoNotCorruptState() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        m.start();
        m.runUntilState(MatchState.BUY_PHASE);
        for (int i = 0; i < 5; i++) {
            assertTrue(m.flow.pause(null, "p" + i));
            m.flow.tick();
            assertTrue(m.flow.resume(null, 0));
        }
        assertFalse(m.flow.resume(null, 0), "resume when not paused is rejected");
        m.runUntilState(MatchState.CLEANUP);
        assertEquals(5, m.eventsOf(MatchFlowEvent.Paused.class).size());
    }

    @Test
    void disconnectEliminatesAndReconnectWaitsForNextRound() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 2);
        m.start();
        m.runUntilState(MatchState.ROUND_LIVE);
        Participant a0 = m.teamA.getFirst();
        m.flow.playerDisconnected(a0.id());
        assertFalse(a0.alive());
        assertEquals(EliminationCause.DISCONNECT, m.eventsOf(MatchFlowEvent.PlayerEliminated.class).getFirst().cause());
        m.flow.playerReconnected(a0.id());
        assertTrue(a0.connected());
        assertFalse(a0.alive(), "stays dead for the rest of the round");
        m.runUntilState(MatchState.BUY_PHASE);
        assertTrue(a0.alive(), "respawns next round");

        m.flow.playerDisconnected(a0.id());
        m.runUntilState(MatchState.ROUND_END);
        m.runUntilState(MatchState.BUY_PHASE);
        assertFalse(a0.alive(), "disconnected players start the round dead");
    }

    @Test
    void fullyDisconnectedTeamLosesRoundsImmediately() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        m.start();
        m.flow.playerDisconnected(m.teamB.getFirst().id());
        m.runUntilState(MatchState.ROUND_END);
        assertEquals(RoundEndReason.DEFENDERS_ELIMINATED, m.eventsOf(MatchFlowEvent.RoundEnded.class).getFirst().reason());
    }

    @Test
    void halftimeSkipNeedsBothTeamsAndAllLocks() {
        ModeRules longHalftime = new ModeRules("t", "T", 5, 5, 4, 4, 10, 10, 40, 30, 5, 10, 5, 5, 1200, 10, 5,
                SuddenDeathSides.KEEP, dev.tactical.engine.mode.SkipVoteRule.MAJORITY, false);
        TestMatches m = new TestMatches(longHalftime, 1);
        m.start();
        m.runUntilState(MatchState.HALFTIME);
        int before = m.flow.stateTicksRemaining();
        assertTrue(m.flow.lockKit(m.teamA.getFirst().id(), "k1").name().equals("OK"));
        assertTrue(m.flow.lockKit(m.teamB.getFirst().id(), "k1").name().equals("OK"), "opponents may share kits");
        assertTrue(m.flow.voteSkip(m.teamA.getFirst().id()));
        assertEquals(before, m.flow.stateTicksRemaining(), "one team is not enough");
        assertTrue(m.flow.voteSkip(m.teamB.getFirst().id()));
        assertEquals(MatchFlow.LOCKED_GRACE_TICKS, m.flow.stateTicksRemaining());
    }

    @Test
    void suddenDeathOffersOnlyTheTwoHalfKits() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 2);
        m.start();
        m.flow.lockKit(m.teamA.get(0).id(), "k1");
        m.flow.lockKit(m.teamA.get(1).id(), "k2");
        m.runUntilState(MatchState.HALFTIME);
        m.flow.lockKit(m.teamA.get(0).id(), "k3");
        m.flow.lockKit(m.teamA.get(1).id(), "k1");
        m.runUntilState(MatchState.SUDDEN_DEATH_KIT_SELECT);
        var sel = m.flow.kitSelection().orElseThrow();
        assertEquals(List.of("k3", "k1"), sel.optionsOf(m.teamA.get(0).id()));
        assertEquals(List.of("k1", "k2"), sel.optionsOf(m.teamA.get(1).id()));
        assertEquals("KIT_NOT_AVAILABLE", m.flow.lockKit(m.teamA.get(0).id(), "k5").name());
        m.runUntilState(MatchState.BUY_PHASE);
        Participant a0 = m.teamA.get(0), a1 = m.teamA.get(1);
        assertNotEquals(a0.kitSuddenDeath(), a1.kitSuddenDeath());
        assertTrue(List.of("k3", "k1").contains(a0.kitSuddenDeath()));
        assertEquals(a0.kitSuddenDeath(), a0.currentKit());
    }

    @Test
    void forceEndRoundAndAbort() {
        TestMatches m = new TestMatches(TestMatches.fast(5, 4, 4, SuddenDeathSides.KEEP), 1);
        m.start();
        m.runUntilState(MatchState.ROUND_LIVE);
        assertTrue(m.flow.forceEndRound(Side.ATTACKERS));
        assertEquals(1, m.flow.scoreOf(TeamId.A));
        m.flow.abort(MatchEndReason.ADMIN_ENDED, "test");
        assertEquals(MatchState.ABORTED, m.flow.state());
        assertTrue(m.flow.winner().isEmpty());
        m.flow.tick();
        assertEquals(MatchState.CLEANUP, m.flow.state());
        m.flow.abort(MatchEndReason.ADMIN_ENDED, "again"); // idempotent
        assertEquals(MatchState.CLEANUP, m.flow.state());
    }

    @Test
    void stateChangesAreAlwaysLegal() {
        TestMatches m = new TestMatches(TestMatches.fast(9, 8, 8, SuddenDeathSides.RANDOM), 5);
        m.start();
        m.runUntilState(MatchState.CLEANUP);
        for (MatchFlowEvent.StateChanged c : m.eventsOf(MatchFlowEvent.StateChanged.class)) {
            assertTrue(dev.tactical.engine.state.MatchStateMachine.isAllowed(c.from(), c.to()), c.toString());
        }
    }
}
