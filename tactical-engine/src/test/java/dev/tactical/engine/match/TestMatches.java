package dev.tactical.engine.match;

import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.mode.SkipVoteRule;
import dev.tactical.engine.mode.SuddenDeathSides;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.TeamId;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

/** Helpers that build virtual matches (no Minecraft) and drive them tick by tick. */
final class TestMatches {

    static final List<String> KITS = List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7");

    final List<MatchFlowEvent> events = new ArrayList<>();
    final List<Participant> teamA = new ArrayList<>();
    final List<Participant> teamB = new ArrayList<>();
    final MatchFlow flow;

    TestMatches(ModeRules rules, int perTeam) {
        List<Participant> all = new ArrayList<>();
        for (int i = 0; i < perTeam; i++) {
            Participant a = new Participant(new UUID(1, i), "a" + i, TeamId.A);
            Participant b = new Participant(new UUID(2, i), "b" + i, TeamId.B);
            teamA.add(a);
            teamB.add(b);
            all.add(a);
            all.add(b);
        }
        flow = new MatchFlow(MatchId.of(LocalDate.of(2026, 11, 23), 1, true), rules, all, KITS,
                new SplittableRandom(42), events::add);
    }

    /** Short timers so tests run fast; structure identical to the real mode. */
    static ModeRules fast(int roundsToWin, int halftimeAfter, int suddenDeathAt, SuddenDeathSides sd) {
        return new ModeRules("test", "Test", 5, roundsToWin, halftimeAfter, suddenDeathAt,
                10, 10, 40, 30, 5, 10, 5, 5, 20, 10, 5, sd, SkipVoteRule.MAJORITY, false);
    }

    void start() {
        flow.beginLoading();
        flow.loaded();
    }

    /** Ticks until the predicate holds; fails after a generous limit to catch stalls. */
    void runUntil(Predicate<MatchFlow> condition) {
        for (int i = 0; i < 1_000_000; i++) {
            if (condition.test(flow)) return;
            flow.tick();
        }
        throw new AssertionError("Match stalled in state " + flow.state());
    }

    void runUntilState(MatchState state) {
        runUntil(f -> f.state() == state);
    }

    List<Participant> team(TeamId t) {
        return t == TeamId.A ? teamA : teamB;
    }

    <T extends MatchFlowEvent> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
