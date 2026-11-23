package dev.tactical.engine.round;

import dev.tactical.engine.team.Side;

public record RoundOutcome(Side winner, RoundEndReason reason) {
}
