package dev.tactical.engine.round;

import dev.tactical.engine.team.Side;
import org.junit.jupiter.api.Test;

import static dev.tactical.engine.round.BombStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundOutcomeResolverTest {

    private static RoundOutcome r(int atk, int def, BombStatus b, boolean expired) {
        return RoundOutcomeResolver.resolve(new RoundSnapshot(atk, def, b, expired)).orElse(null);
    }

    @Test
    void precedence() {
        assertNull(r(5, 5, NOT_PLANTED, false));
        assertEquals(RoundEndReason.TIME_EXPIRED, r(3, 3, NOT_PLANTED, true).reason());
        assertEquals(Side.DEFENDERS, r(0, 3, NOT_PLANTED, false).winner());
        assertEquals(Side.ATTACKERS, r(2, 0, NOT_PLANTED, false).winner());
        // Both teams dead at once with no plant: defenders (attackers failed the objective).
        assertEquals(RoundEndReason.ATTACKERS_ELIMINATED, r(0, 0, NOT_PLANTED, false).reason());
        // Planted: attackers dead does NOT end the round.
        assertNull(r(0, 2, PLANTED, false));
        assertEquals(RoundEndReason.DEFENDERS_ELIMINATED_AFTER_PLANT, r(0, 0, PLANTED, false).reason());
        assertEquals(RoundEndReason.DEFENDERS_ELIMINATED_AFTER_PLANT, r(3, 0, PLANTED, true).reason());
        assertEquals(Side.DEFENDERS, r(4, 0, DEFUSED, false).winner());
        assertEquals(Side.ATTACKERS, r(0, 5, DETONATED, false).winner());
    }
}
