package dev.tactical.paper.stats;

/** Per-player counters for one match (Phase 5 persists these and adds combat score). */
public final class MatchStats {
    public int kills;
    public int deaths;
    public int assists;
    public double damage;

    /** Provisional performance score used for the post-game ranking until the configurable combat score (Phase 5). */
    public double score() {
        return kills * 150 + assists * 50 + damage * 1.0;
    }
}
