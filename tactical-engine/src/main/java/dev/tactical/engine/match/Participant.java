package dev.tactical.engine.match;

import dev.tactical.engine.team.TeamId;

import java.util.Objects;
import java.util.UUID;

/**
 * A match participant as the ENGINE sees it. UUID is the authoritative identity; the name is display-only.
 * Mutable state is only changed by {@link MatchFlow}.
 */
public final class Participant {

    private final UUID id;
    private final String name;
    private final TeamId team;
    private boolean connected = true;
    private boolean alive;
    private String kitFirstHalf;
    private String kitSecondHalf;
    private String kitSuddenDeath;

    public Participant(UUID id, String name, TeamId team) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.team = Objects.requireNonNull(team);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Original team identity (never changes). */
    public TeamId team() {
        return team;
    }

    public boolean connected() {
        return connected;
    }

    public boolean alive() {
        return alive;
    }

    /** Alive AND connected: counts toward the round. */
    public boolean active() {
        return alive && connected;
    }

    public String kitFirstHalf() {
        return kitFirstHalf;
    }

    public String kitSecondHalf() {
        return kitSecondHalf;
    }

    public String kitSuddenDeath() {
        return kitSuddenDeath;
    }

    /** The kit this player currently plays, or null before the first selection completes. */
    public String currentKit() {
        if (kitSuddenDeath != null) return kitSuddenDeath;
        if (kitSecondHalf != null) return kitSecondHalf;
        return kitFirstHalf;
    }

    void setConnected(boolean connected) {
        this.connected = connected;
    }

    void setAlive(boolean alive) {
        this.alive = alive;
    }

    void setKitFirstHalf(String kit) {
        this.kitFirstHalf = kit;
    }

    void setKitSecondHalf(String kit) {
        this.kitSecondHalf = kit;
    }

    void setKitSuddenDeath(String kit) {
        this.kitSuddenDeath = kit;
    }

    @Override
    public String toString() {
        return name + "(" + id + "," + team + ")";
    }
}
