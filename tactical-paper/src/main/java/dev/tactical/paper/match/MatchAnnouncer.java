package dev.tactical.paper.match;

import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.paper.message.Messages;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * System announcements to a match's own participants (never to other matches or the lobby).
 * Announcements are not player chat, so moderation mutes never block them. Placeholder text only for Phase 1;
 * HUD/bossbar presentation arrives in Phase 5.
 */
final class MatchAnnouncer {

    private final Messages messages;
    private final Logger log;
    private final java.util.function.BooleanSupplier logStates;

    MatchAnnouncer(Messages messages, Logger log, java.util.function.BooleanSupplier logStates) {
        this.messages = messages;
        this.log = log;
        this.logStates = logStates;
    }

    void announce(GameMatch match, MatchFlowEvent event) {
        Audience to = Audience.audience(match.onlinePlayers());
        switch (event) {
            case MatchFlowEvent.StateChanged e -> {
                if (logStates.getAsBoolean()) log.info("[match=" + match.id() + "] " + e.from() + " -> " + e.to()
                        + (e.durationTicks() > 0 ? " (" + e.durationTicks() / ModeRules.TICKS_PER_SECOND + "s)" : ""));
            }
            case MatchFlowEvent.KitSelectionOpened e -> messages.send(to, "match.kit-select",
                    "phase", e.phase().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "seconds", e.durationTicks() / ModeRules.TICKS_PER_SECOND);
            case MatchFlowEvent.KitLocked e -> messages.send(to, "match.kit-locked", "player", name(match, e.player()), "kit", e.kit());
            case MatchFlowEvent.RoundStarted e -> messages.send(to, "match.round-start", "round", e.round(),
                    "sudden", e.suddenDeath() ? " (sudden death)" : "");
            case MatchFlowEvent.RoundLive e -> messages.send(to, "match.round-live", "round", e.round());
            case MatchFlowEvent.RoundEnded e -> messages.send(to, "match.round-end",
                    "team", e.winnerTeam().name(), "round", e.round(),
                    "reason", e.reason().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "score_a", e.scoreA(), "score_b", e.scoreB());
            case MatchFlowEvent.HalftimeStarted e -> messages.send(to, "match.halftime");
            case MatchFlowEvent.SuddenDeathStarted e -> messages.send(to, "match.sudden-death");
            case MatchFlowEvent.MatchEnded e -> {
                if (e.winner() != null) {
                    messages.send(to, "match.end", "winner", "Team " + e.winner() + " wins",
                            "score_a", e.scoreA(), "score_b", e.scoreB());
                } else {
                    messages.send(to, "match.aborted", "reason", e.reason().name().toLowerCase(Locale.ROOT)
                            + (e.detail().isEmpty() ? "" : " — " + e.detail()));
                }
                log.info("[match=" + match.id() + "] ended: " + e.reason() + " winner=" + e.winner()
                        + " score " + e.scoreA() + "-" + e.scoreB());
            }
            case MatchFlowEvent.Paused e -> messages.send(to, "match.paused", "by", name(match, e.by()),
                    "reason", e.reason().isEmpty() ? "" : " (" + e.reason() + ")");
            case MatchFlowEvent.ResumeCountdown e -> messages.send(to, "match.resume-countdown",
                    "seconds", e.countdownTicks() / ModeRules.TICKS_PER_SECOND);
            case MatchFlowEvent.Resumed e -> messages.send(to, "match.resumed");
            default -> { }
        }
    }

    private static String name(GameMatch match, java.util.UUID id) {
        if (id == null) return "Console";
        return match.member(id).map(MatchMember::name).orElseGet(() -> {
            Player p = Bukkit.getPlayer(id);
            return p != null ? p.getName() : id.toString();
        });
    }
}
