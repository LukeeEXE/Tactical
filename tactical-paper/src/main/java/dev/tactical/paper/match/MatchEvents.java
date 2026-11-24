package dev.tactical.paper.match;

import java.util.UUID;

/** Paper-layer events published on a match's event bus (in addition to the engine's MatchFlowEvents). */
public final class MatchEvents {

    private MatchEvents() {
    }

    /** A teammate of the killer damaged the victim shortly before the kill. */
    public record AssistAwarded(UUID assister, UUID victim, UUID killer) {
    }

    /** The planted bomb beeped (the HUD flashes the bomb icon in time with it). */
    public record BombBeep() {
    }
}
