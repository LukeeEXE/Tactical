package dev.tactical.engine.record;

import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.match.MatchFlowEvent.*;
import dev.tactical.engine.team.TeamId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Translates engine {@link MatchFlowEvent}s into recording lines. Keeps the recording schema in one place. */
public final class FlowEventRecorder {

    private final MatchRecorder recorder;

    public FlowEventRecorder(MatchRecorder recorder) {
        this.recorder = recorder;
    }

    public void accept(MatchFlowEvent event) {
        switch (event) {
            case StateChanged e -> rec(EventTypes.MATCH_STATE, List.of(), null,
                    map("from", e.from(), "to", e.to(), "duration_ticks", e.durationTicks()), RecordingSink.Priority.CRITICAL);
            case KitSelectionOpened e -> rec(EventTypes.KIT_SELECTION_OPEN, List.of(), null,
                    map("phase", e.phase(), "duration_ticks", e.durationTicks()), null);
            case KitHovered e -> rec(EventTypes.KIT_HOVER, List.of(e.player()), e.team(), map("kit", e.kit()), null);
            case KitLocked e -> rec(EventTypes.KIT_LOCK, List.of(e.player()), e.team(),
                    map("kit", e.kit(), "automatic", e.automatic()), null);
            case SkipVoted e -> rec(EventTypes.SKIP_VOTE, List.of(e.player()), e.team(),
                    map("team_agrees", e.teamAgrees()), null);
            case KitSelectionClosed e -> rec(EventTypes.KIT_SELECTION_CLOSE, List.of(), null,
                    map("phase", e.phase(), "kits", e.kits()), null);
            case RoundStarted e -> rec(EventTypes.ROUND_START, List.of(), null,
                    map("round", e.round(), "sudden_death", e.suddenDeath(), "sides", e.sides()), RecordingSink.Priority.CRITICAL);
            case RoundLive e -> rec(EventTypes.ROUND_LIVE, List.of(), null, map("round", e.round()), null);
            case PlayerEliminated e -> {
                List<UUID> actors = new ArrayList<>();
                actors.add(e.victim());
                if (e.killer() != null) actors.add(e.killer());
                rec(EventTypes.ELIMINATION, actors, null,
                        map("victim", e.victim(), "killer", e.killer(), "cause", e.cause()), null);
            }
            case BombPlanted e -> rec(EventTypes.BOMB_PLANTED, List.of(e.planter()), null, map("site", e.site()),
                    RecordingSink.Priority.CRITICAL);
            case BombDefused e -> rec(EventTypes.BOMB_DEFUSED, List.of(e.defuser()), null, map(),
                    RecordingSink.Priority.CRITICAL);
            case BombDetonated e -> rec(EventTypes.BOMB_EXPLODED, List.of(), null, map(), RecordingSink.Priority.CRITICAL);
            case RoundEnded e -> rec(EventTypes.ROUND_END, List.of(), e.winnerTeam(),
                    map("round", e.round(), "winner_side", e.winnerSide(), "winner_team", e.winnerTeam(),
                            "reason", e.reason(), "score_a", e.scoreA(), "score_b", e.scoreB()), RecordingSink.Priority.CRITICAL);
            case HalftimeStarted e -> rec(EventTypes.HALFTIME, List.of(), null,
                    map("after_round", e.afterRound(), "sides", e.newSides()), RecordingSink.Priority.CRITICAL);
            case SuddenDeathStarted e -> rec(EventTypes.SUDDEN_DEATH, List.of(), null, map("sides", e.sides()),
                    RecordingSink.Priority.CRITICAL);
            case MatchEnded e -> rec(EventTypes.MATCH_END, List.of(), e.winner(),
                    map("winner", e.winner(), "reason", e.reason(), "score_a", e.scoreA(), "score_b", e.scoreB(),
                            "detail", e.detail()), RecordingSink.Priority.CRITICAL);
            case Paused e -> rec(EventTypes.PAUSE, actor(e.by()), null, map("reason", e.reason()), RecordingSink.Priority.CRITICAL);
            case ResumeCountdown e -> rec(EventTypes.RESUME_COUNTDOWN, actor(e.by()), null,
                    map("countdown_ticks", e.countdownTicks()), null);
            case Resumed e -> rec(EventTypes.RESUME, actor(e.by()), null, map("paused_ticks", e.pausedTicks()),
                    RecordingSink.Priority.CRITICAL);
            case PlayerDisconnected e -> rec(EventTypes.PLAYER_DISCONNECT, List.of(e.player()), null,
                    map("was_alive", e.wasAlive()), RecordingSink.Priority.CRITICAL);
            case PlayerReconnected e -> rec(EventTypes.PLAYER_RECONNECT, List.of(e.player()), null, map(),
                    RecordingSink.Priority.CRITICAL);
            case TimerChanged e -> rec(EventTypes.TIMER_CHANGE, List.of(), null,
                    map("state", e.state(), "old_ticks", e.oldTicks(), "new_ticks", e.newTicks(), "reason", e.reason()), null);
        }
    }

    private void rec(String type, List<UUID> actors, TeamId team, Map<String, Object> data, RecordingSink.Priority p) {
        recorder.record(type, actors, team, data, "OK", p == null ? RecordingSink.Priority.NORMAL : p);
    }

    private static List<UUID> actor(UUID u) {
        return u == null ? List.of() : List.of(u);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
