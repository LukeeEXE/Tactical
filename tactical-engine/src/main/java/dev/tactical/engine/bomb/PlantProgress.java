package dev.tactical.engine.bomb;

import java.util.Optional;
import java.util.UUID;

/** Plant progress of the current carrier. Any interruption resets it completely (no checkpoint). */
public final class PlantProgress {

    public enum Step { IDLE, STARTED, PROGRESS, COMPLETED, INTERRUPTED }

    private final int totalTicks;
    private UUID planter;
    private int progress;
    private String site;

    public PlantProgress(int totalTicks) {
        if (totalTicks < 1) throw new IllegalArgumentException("plant time must be >= 1 tick");
        this.totalTicks = totalTicks;
    }

    /** Called every tick the carrier holds the plant input inside a site. Switching site or planter restarts. */
    public Step tick(UUID player, String siteId) {
        boolean fresh = planter == null || !planter.equals(player) || !siteId.equals(site);
        if (fresh) {
            planter = player;
            site = siteId;
            progress = 0;
        }
        progress++;
        if (progress >= totalTicks) {
            planter = null;
            site = null;
            progress = 0;
            return Step.COMPLETED;
        }
        return fresh ? Step.STARTED : Step.PROGRESS;
    }

    public Step interrupt() {
        if (planter == null) return Step.IDLE;
        planter = null;
        progress = 0;
        site = null;
        return Step.INTERRUPTED;
    }

    public Optional<UUID> planter() {
        return Optional.ofNullable(planter);
    }

    public Optional<String> site() {
        return Optional.ofNullable(site);
    }

    public int progressTicks() {
        return progress;
    }

    public double fraction() {
        return (double) progress / totalTicks;
    }
}
