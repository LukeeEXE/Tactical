package dev.tactical.engine.kit;

import dev.tactical.engine.team.TeamId;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KitSelectionTest {

    private static final UUID A1 = new UUID(1, 1), A2 = new UUID(1, 2), A3 = new UUID(1, 3);
    private static final UUID B1 = new UUID(2, 1);

    @Test
    void teammatesCannotDuplicateButOpponentsCan() {
        KitSelection s = KitSelection.open(KitSelectionPhase.MATCH_START,
                Map.of(A1, TeamId.A, A2, TeamId.A, B1, TeamId.B), List.of("x", "y", "z"));
        assertEquals(KitSelectionResult.OK, s.lock(A1, "x"));
        assertEquals(KitSelectionResult.TAKEN_BY_TEAMMATE, s.lock(A2, "x"));
        assertEquals(KitSelectionResult.OK, s.lock(B1, "x"));
        assertEquals(KitSelectionResult.ALREADY_LOCKED, s.lock(A1, "y"));
        assertEquals(KitSelectionResult.NOT_A_PARTICIPANT, s.lock(UUID.randomUUID(), "y"));
    }

    @Test
    void lockThatWouldStrandATeammateIsRejected() {
        // Cycle: A1 {x,y}, A2 {y,z}, A3 {z,x}. A1 taking y is fine (A2→z, A3→x); but with A2 {y} only, A1 cannot take y.
        Map<UUID, TeamId> teams = Map.of(A1, TeamId.A, A2, TeamId.A, A3, TeamId.A);
        Map<UUID, List<String>> opts = new HashMap<>();
        opts.put(A1, List.of("x", "y"));
        opts.put(A2, List.of("y", "z"));
        opts.put(A3, List.of("x", "z"));
        KitSelection s = new KitSelection(KitSelectionPhase.SUDDEN_DEATH, teams, opts);
        assertEquals(KitSelectionResult.OK, s.canLock(A1, "y"));
        assertEquals(KitSelectionResult.OK, s.lock(A3, "z"));
        // Now A2 must take y, so A1 taking y would strand A2.
        assertEquals(KitSelectionResult.WOULD_STRAND_TEAMMATE, s.lock(A1, "y"));
        assertEquals(KitSelectionResult.OK, s.lock(A1, "x"));
        assertEquals("y", s.autoFill().get(A2));
        assertTrue(s.allLocked());
    }

    @Test
    void singleOptionPlayersAreLockedImmediately() {
        KitSelection s = KitSelection.suddenDeath(Map.of(A1, TeamId.A), Map.of(A1, "x"), Map.of(A1, "x"));
        assertEquals("x", s.lockedBy(A1).orElseThrow());
        assertTrue(s.allLocked());
    }

    @Test
    void autoFillPrefersHoveredThenSecondHalfKit() {
        KitSelection s = KitSelection.suddenDeath(Map.of(A1, TeamId.A, A2, TeamId.A),
                Map.of(A1, "x", A2, "y"), Map.of(A1, "y", A2, "x"));
        assertEquals(KitSelectionResult.OK, s.select(A2, "y"));
        Map<UUID, String> auto = s.autoFill();
        assertEquals("y", auto.get(A2), "hovered kit wins");
        assertEquals("x", auto.get(A1));
    }

    @Test
    void fewerKitsThanPlayersAllowsDuplicates() {
        KitSelection s = KitSelection.open(KitSelectionPhase.MATCH_START,
                Map.of(A1, TeamId.A, A2, TeamId.A), List.of("only"));
        assertTrue(s.duplicatesAllowed(TeamId.A));
        assertTrue(s.allLocked());
    }

    /** Fuzz: random sudden-death setups derived from valid halves can always be completed without duplicates. */
    @RepeatedTest(300)
    void suddenDeathNeverDeadlocks() {
        Random r = new Random();
        List<String> kits = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        List<UUID> players = new ArrayList<>();
        Map<UUID, TeamId> teams = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID id = new UUID(9, i);
            players.add(id);
            teams.put(id, TeamId.A);
        }
        Map<UUID, String> first = assign(players, kits, r);
        Map<UUID, String> second = assign(players, kits, r);
        KitSelection s = KitSelection.suddenDeath(teams, first, second);
        // Players lock random allowed options in random order; rejections are fine, deadlocks are not.
        List<UUID> order = new ArrayList<>(players);
        java.util.Collections.shuffle(order, r);
        for (UUID p : order) {
            List<String> o = new ArrayList<>(s.optionsOf(p));
            java.util.Collections.shuffle(o, r);
            s.lock(p, o.getFirst());
        }
        s.autoFill();
        assertTrue(s.allLocked());
        assertFalse(s.duplicatesAllowed(TeamId.A));
        Set<String> used = new HashSet<>(s.locks().values());
        assertEquals(5, used.size(), "no duplicate kits");
        for (UUID p : players) {
            assertTrue(List.of(first.get(p), second.get(p)).contains(s.lockedBy(p).orElseThrow()));
        }
    }

    private static Map<UUID, String> assign(List<UUID> players, List<String> kits, Random r) {
        List<String> shuffled = new ArrayList<>(kits);
        java.util.Collections.shuffle(shuffled, r);
        Map<UUID, String> m = new HashMap<>();
        for (int i = 0; i < players.size(); i++) m.put(players.get(i), shuffled.get(i));
        return m;
    }
}
