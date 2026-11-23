package dev.tactical.engine.bomb;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.tactical.engine.bomb.DefuseProgress.Step.*;
import static org.junit.jupiter.api.Assertions.*;

class BombProgressTest {

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);

    @Test
    void checkpointBelongsToTheBomb() {
        DefuseProgress d = new DefuseProgress(200, 100);
        assertEquals(STARTED, d.tick(A));
        for (int i = 2; i < 100; i++) assertEquals(PROGRESS, d.tick(A));
        assertEquals(CHECKPOINT, d.tick(A));
        assertEquals(BUSY, d.tick(B), "only one defuser at a time");
        d.tick(A); // 101
        assertEquals(INTERRUPTED, d.interrupt());
        assertEquals(100, d.progressTicks(), "progress falls back to the checkpoint, not zero");
        assertEquals(STARTED, d.tick(B));
        int ticks = 1;
        while (d.tick(B) != COMPLETED) ticks++;
        assertEquals(100, ticks + 1, "player B only needs the remaining half");
        assertTrue(d.completed());
    }

    @Test
    void progressBeforeCheckpointIsLost() {
        DefuseProgress d = new DefuseProgress(200, 100);
        for (int i = 0; i < 99; i++) d.tick(A);
        d.interrupt();
        assertEquals(0, d.progressTicks());
        assertFalse(d.checkpointReached());
    }

    @Test
    void plantResetsOnInterruptOrSiteChange() {
        PlantProgress p = new PlantProgress(100);
        assertEquals(PlantProgress.Step.STARTED, p.tick(A, "A"));
        for (int i = 0; i < 50; i++) p.tick(A, "A");
        assertEquals(PlantProgress.Step.STARTED, p.tick(A, "B"), "moving to another site restarts");
        assertEquals(1, p.progressTicks());
        p.interrupt();
        assertEquals(0, p.progressTicks());
        PlantProgress.Step last = null;
        for (int i = 0; i < 100; i++) last = p.tick(A, "A");
        assertEquals(PlantProgress.Step.COMPLETED, last);
    }
}
