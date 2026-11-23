package dev.tactical.engine.match;

import dev.tactical.engine.kit.KitSelectionPhase;
import dev.tactical.engine.round.RoundEndReason;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;

import java.util.Map;
import java.util.UUID;

/**
 * Everything the engine reports to the outside world. The Paper layer publishes these on the match's
 * event bus; HUD, recorder, sounds, stats and economy subscribe. Every event carries the match tick.
 */
public sealed interface MatchFlowEvent {

    long tick();

    record StateChanged(long tick, MatchState from, MatchState to, int durationTicks) implements MatchFlowEvent {}

    record KitSelectionOpened(long tick, KitSelectionPhase phase, int durationTicks) implements MatchFlowEvent {}

    record KitHovered(long tick, UUID player, TeamId team, String kit) implements MatchFlowEvent {}

    record KitLocked(long tick, UUID player, TeamId team, String kit, boolean automatic) implements MatchFlowEvent {}

    record SkipVoted(long tick, UUID player, TeamId team, boolean teamAgrees) implements MatchFlowEvent {}

    record KitSelectionClosed(long tick, KitSelectionPhase phase, Map<UUID, String> kits) implements MatchFlowEvent {}

    record RoundStarted(long tick, int round, boolean suddenDeath, Map<TeamId, Side> sides) implements MatchFlowEvent {}

    record RoundLive(long tick, int round) implements MatchFlowEvent {}

    record PlayerEliminated(long tick, int round, UUID victim, UUID killer, EliminationCause cause) implements MatchFlowEvent {}

    record BombPlanted(long tick, int round, UUID planter, String site) implements MatchFlowEvent {}

    record BombDefused(long tick, int round, UUID defuser) implements MatchFlowEvent {}

    record BombDetonated(long tick, int round) implements MatchFlowEvent {}

    record RoundEnded(long tick, int round, Side winnerSide, TeamId winnerTeam, RoundEndReason reason,
                      int scoreA, int scoreB) implements MatchFlowEvent {}

    record HalftimeStarted(long tick, int afterRound, Map<TeamId, Side> newSides) implements MatchFlowEvent {}

    record SuddenDeathStarted(long tick, Map<TeamId, Side> sides) implements MatchFlowEvent {}

    record MatchEnded(long tick, TeamId winner, MatchEndReason reason, int scoreA, int scoreB, String detail)
            implements MatchFlowEvent {}

    record Paused(long tick, UUID by, String reason) implements MatchFlowEvent {}

    record ResumeCountdown(long tick, UUID by, int countdownTicks) implements MatchFlowEvent {}

    record Resumed(long tick, UUID by, long pausedTicks) implements MatchFlowEvent {}

    record PlayerDisconnected(long tick, UUID player, boolean wasAlive) implements MatchFlowEvent {}

    record PlayerReconnected(long tick, UUID player) implements MatchFlowEvent {}

    record TimerChanged(long tick, MatchState state, int oldTicks, int newTicks, String reason) implements MatchFlowEvent {}
}
