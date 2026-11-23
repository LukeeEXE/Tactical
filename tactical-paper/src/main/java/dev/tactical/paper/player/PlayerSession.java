package dev.tactical.paper.player;

import dev.tactical.engine.id.MatchId;

import java.util.Optional;
import java.util.UUID;

/** Per-online-player state. Main thread only. */
public final class PlayerSession {

    private final UUID uuid;
    private final String name;
    private final long joinedAt;
    private SessionContext context = SessionContext.LOBBY;
    private MatchId matchId;

    PlayerSession(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.joinedAt = System.currentTimeMillis();
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public SessionContext context() {
        return context;
    }

    public Optional<MatchId> matchId() {
        return Optional.ofNullable(matchId);
    }

    public void enterMatch(MatchId id, SessionContext ctx) {
        this.matchId = id;
        this.context = ctx;
    }

    public void returnToLobby() {
        this.matchId = null;
        this.context = SessionContext.LOBBY;
    }
}
