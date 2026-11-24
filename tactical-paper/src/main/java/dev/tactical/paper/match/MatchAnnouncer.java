package dev.tactical.paper.match;

import dev.tactical.engine.kit.KitSelectionPhase;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.engine.team.Side;
import dev.tactical.paper.hud.ChatStyle;
import dev.tactical.paper.hud.TeamStyle;
import dev.tactical.paper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * System announcements to a match's own participants, in the {@code [icon] message} chat style with players shown as
 * {@code <shield> <head> Name} in their team colour. Texts come from messages/en_US.yml ({@code chat.*} keys) so they
 * can be reworded; announcements are not player chat, so mutes never block them.
 */
final class MatchAnnouncer {

    private final Messages messages;
    private final Logger log;
    private final BooleanSupplier logStates;

    MatchAnnouncer(Messages messages, Logger log, BooleanSupplier logStates) {
        this.messages = messages;
        this.log = log;
        this.logStates = logStates;
    }

    void announce(GameMatch match, MatchFlowEvent event) {
        switch (event) {
            case MatchFlowEvent.StateChanged e -> {
                if (logStates.getAsBoolean()) log.info("[match=" + match.id() + "] " + e.from() + " -> " + e.to()
                        + (e.durationTicks() > 0 ? " (" + e.durationTicks() / ModeRules.TICKS_PER_SECOND + "s)" : ""));
            }
            case MatchFlowEvent.KitSelectionOpened e -> all(match, HudGlyphs.CHAT_KIT, "chat.kit-select",
                    "seconds", e.durationTicks() / ModeRules.TICKS_PER_SECOND,
                    "phase", e.phase() == KitSelectionPhase.MATCH_START ? "first half"
                            : e.phase() == KitSelectionPhase.SECOND_HALF ? "second half" : "sudden death");
            case MatchFlowEvent.KitLocked e -> {
                if (!e.automatic()) all(match, HudGlyphs.CHAT_KIT, "chat.kit-locked", "player", ChatStyle.player(match, e.player()), "kit", e.kit());
            }
            case MatchFlowEvent.RoundStarted e -> {
                for (Player p : match.onlinePlayers()) {
                    MatchMember me = match.member(p.getUniqueId()).orElse(null);
                    if (me == null) continue;
                    Side side = e.sides().get(me.team());
                    send(p, HudGlyphs.CHAT_ROUND, e.suddenDeath() ? "chat.round-start-sudden-death" : "chat.round-start",
                            "round", e.round(), "role", Component.text(TeamStyle.roleName(side), TeamStyle.color(me.team())));
                }
            }
            case MatchFlowEvent.RoundLive e -> all(match, HudGlyphs.CHAT_WARNING, "chat.round-live", "round", e.round());
            case MatchFlowEvent.PlayerEliminated e -> {
                if (e.cause() == EliminationCause.KILLED && e.killer() != null) {
                    all(match, HudGlyphs.CHAT_KILL, "chat.kill", "victim", ChatStyle.player(match, e.victim()),
                            "killer", ChatStyle.player(match, e.killer()));
                } else {
                    all(match, HudGlyphs.CHAT_KILL, "chat.died", "victim", ChatStyle.player(match, e.victim()),
                            "cause", e.cause().name().toLowerCase(Locale.ROOT).replace('_', ' '));
                }
            }
            case MatchFlowEvent.BombPlanted e -> all(match, HudGlyphs.CHAT_BOMB, "chat.bomb-planted",
                    "player", ChatStyle.player(match, e.planter()), "site", e.site());
            case MatchFlowEvent.BombDefused e -> all(match, HudGlyphs.CHAT_DEFUSE, "chat.bomb-defused", "player", ChatStyle.player(match, e.defuser()));
            case MatchFlowEvent.BombDetonated e -> all(match, HudGlyphs.CHAT_BOMB, "chat.bomb-exploded");
            case MatchFlowEvent.RoundEnded e -> {
                for (Player p : match.onlinePlayers()) {
                    MatchMember me = match.member(p.getUniqueId()).orElse(null);
                    boolean won = me != null && me.team() == e.winnerTeam();
                    send(p, won ? HudGlyphs.CHAT_WIN : HudGlyphs.CHAT_LOSS, won ? "chat.round-won" : "chat.round-lost",
                            "team", ChatStyle.team(match, e.winnerTeam()), "round", e.round(),
                            "reason", e.reason().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                            "score_a", Component.text(e.scoreA(), TeamStyle.color(dev.tactical.engine.team.TeamId.A)),
                            "score_b", Component.text(e.scoreB(), TeamStyle.color(dev.tactical.engine.team.TeamId.B)));
                }
            }
            case MatchFlowEvent.HalftimeStarted e -> all(match, HudGlyphs.CHAT_INFO, "chat.halftime");
            case MatchFlowEvent.SuddenDeathStarted e -> all(match, HudGlyphs.CHAT_WARNING, "chat.sudden-death");
            case MatchFlowEvent.MatchEnded e -> {
                if (e.winner() == null) all(match, HudGlyphs.CHAT_WARNING, "chat.match-aborted",
                        "reason", e.reason().name().toLowerCase(Locale.ROOT) + (e.detail().isEmpty() ? "" : " - " + e.detail()));
                log.info("[match=" + match.id() + "] ended: " + e.reason() + " winner=" + e.winner() + " score " + e.scoreA() + "-" + e.scoreB());
            }
            case MatchFlowEvent.Paused e -> all(match, HudGlyphs.CHAT_WARNING, "chat.paused", "reason", e.reason().isEmpty() ? "" : " (" + e.reason() + ")");
            case MatchFlowEvent.ResumeCountdown e -> all(match, HudGlyphs.CHAT_TIMER, "chat.resume-countdown", "seconds", e.countdownTicks() / ModeRules.TICKS_PER_SECOND);
            case MatchFlowEvent.Resumed e -> all(match, HudGlyphs.CHAT_INFO, "chat.resumed");
            case MatchFlowEvent.PlayerDisconnected e -> all(match, HudGlyphs.CHAT_WARNING, "chat.disconnected", "player", ChatStyle.player(match, e.player()));
            case MatchFlowEvent.PlayerReconnected e -> all(match, HudGlyphs.CHAT_INFO, "chat.reconnected", "player", ChatStyle.player(match, e.player()));
            default -> { }
        }
    }

    private void all(GameMatch match, char icon, String key, Object... placeholders) {
        Component line = ChatStyle.line(icon, messages.render(key, placeholders));
        for (Player p : match.onlinePlayers()) p.sendMessage(line);
    }

    private void send(Player p, char icon, String key, Object... placeholders) {
        p.sendMessage(ChatStyle.line(icon, messages.render(key, placeholders)));
    }
}
