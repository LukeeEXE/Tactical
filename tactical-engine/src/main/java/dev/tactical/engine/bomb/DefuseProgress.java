package dev.tactical.engine.bomb;

import java.util.Optional;
import java.util.UUID;

/**
 * Defuse progress owned by the BOMB (not the player). Only one defender defuses at a time.
 * Interruption drops progress to 0, or to the checkpoint once the checkpoint has been reached, so another
 * defender can continue from there.
 */
public final class DefuseProgress {

    public enum Step { IDLE, STARTED, PROGRESS, CHECKPOINT, COMPLETED, INTERRUPTED, BUSY }

    private final int totalTicks;
    private final int checkpointTicks;
    private int progress;
    private boolean checkpointReached;
    private UUID defuser;
    private boolean completed;

    public DefuseProgress(int totalTicks, int checkpointTicks) {
        if (totalTicks < 1 || checkpointTicks < 0 || checkpointTicks >= totalTicks) {
            throw new IllegalArgumentException("invalid defuse timings " + totalTicks + "/" + checkpointTicks);
        }
        this.totalTicks = totalTicks;
        this.checkpointTicks = checkpointTicks;
    }

    /**
     * Called every tick for each defender who is currently trying to defuse (valid stance this tick).
     * Returns what happened for that player.
     */
    public Step tick(UUID player) {
        if (completed) return Step.IDLE;
        if (defuser == null) {
            defuser = player;
            progress++;
            return afterIncrement(Step.STARTED);
        }
        if (!defuser.equals(player)) return Step.BUSY;
        progress++;
        return afterIncrement(Step.PROGRESS);
    }

    private Step afterIncrement(Step base) {
        if (progress >= totalTicks) {
            completed = true;
            return Step.COMPLETED;
        }
        if (!checkpointReached && checkpointTicks > 0 && progress >= checkpointTicks) {
            checkpointReached = true;
            return Step.CHECKPOINT;
        }
        return base;
    }

    /** The active defuser stopped (moved, released sneak, left radius, died, disconnected...). */
    public Step interrupt() {
        if (defuser == null || completed) return Step.IDLE;
        defuser = null;
        progress = checkpointReached ? checkpointTicks : 0;
        return Step.INTERRUPTED;
    }

    public Optional<UUID> defuser() {
        return Optional.ofNullable(defuser);
    }

    public int progressTicks() {
        return progress;
    }

    public int checkpointTicks() {
        return checkpointTicks;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public boolean checkpointReached() {
        return checkpointReached;
    }

    public boolean completed() {
        return completed;
    }

    public double fraction() {
        return (double) progress / totalTicks;
    }
}
