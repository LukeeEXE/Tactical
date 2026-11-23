package dev.tactical.engine.id;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MatchIdTest {
    @Test
    void format() {
        assertEquals("MATCH-20261123-000001", MatchId.of(LocalDate.of(2026, 11, 23), 1, false).value());
        assertTrue(MatchId.of(LocalDate.of(2026, 11, 23), 7, true).isTest());
        assertThrows(IllegalArgumentException.class, () -> new MatchId("MATCH-1"));
        assertThrows(IllegalArgumentException.class, () -> MatchId.of(LocalDate.now(), 0, false));
    }
}
