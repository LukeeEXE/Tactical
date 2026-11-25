package dev.tactical.paper.match;

import dev.tactical.engine.kit.KitSelectionPhase;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;
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
            case MatchFlowEvent.KitSelectionOpened e -> all(match, HudGlyphs.CHAT_WARNING, "chat.kit-select",
                    "seconds", e.durationTicks() / ModeRules.TICKS_PER_SECOND,
                    "phase", e.phase() == KitSelectionPhase.MATCH_START ? "first half"
                            : e.phase() == KitSelectionPhase.SECOND_HALF ? "second half" : "sudden death");
            case MatchFlowEvent.KitLocked e -> {
                if (!e.automatic()) all(match, HudGlyphs.CHAT_WARNING, "chat.kit-locked", "player", ChatStyle.player(match, e.player()), "kit", e.kit());
            }
            case MatchFlowEvent.RoundStarted e -> {
                for (Player p : match.onlinePlayers()) {
                    MatchMember me = match.member(p.getUniqueId()).orElse(null);
                    if (me == null) continue;
                    Side side = e.sides().get(me.team());
                    send(p, HudGlyphs.CHAT_WARNING, e.suddenDeath() ? "chat.round-start-sudden-death" : "chat.round-start",
                            "round", e.round(), "role", Component.text(TeamStyle.roleName(side), TeamStyle.color(me.team())));
                }
            }
            case MatchFlowEvent.RoundLive e -> all(match, HudGlyphs.CHAT_WARNING, "chat.round-live", "round", e.round());
            case MatchFlowEvent.PlayerEliminated e -> {
                String method = match.takeKillMethod(e.victim()).orElse(
                        e.cause() == EliminationCause.OUT_OF_BOUNDS ? "void" : "generic");
                if (e.cause() == EliminationCause.KILLED && e.killer() != null) {
                    String key = messages.has("chat.kill." + method) ? "chat.kill." + method : "chat.kill";
                    all(match, HudGlyphs.CHAT_KILL, key, "victim", ChatStyle.player(match, e.victim()),
                            "killer", ChatStyle.player(match, e.killer()));
                } else {
                    String key = messages.has("chat.died." + method) ? "chat.died." + method : "chat.died";
                    all(match, HudGlyphs.CHAT_KILL, key, "victim", ChatStyle.player(match, e.victim()),
                            "cause", e.cause().name().toLowerCase(Locale.ROOT).replace('_', ' '));
                }
            }
            case MatchFlowEvent.BombPlanted e -> {
                // Only the planting team is told the site; everyone else just hears that it is down.
                TeamId planters = match.member(e.planter()).map(MatchMember::team).orElse(null);
                for (Player p : match.onlinePlayers()) {
                    boolean own = planters != null && match.member(p.getUniqueId()).map(MatchMember::team).orElse(null) == planters;
                    send(p, HudGlyphs.CHAT_WARNING, own ? "chat.bomb-planted" : "chat.bomb-planted-enemy",
                            "player", ChatStyle.player(match, e.planter()), "site", e.site());
                }
            }
            case MatchFlowEvent.BombDefused e -> all(match, HudGlyphs.CHAT_WARNING, "chat.bomb-defused", "player", ChatStyle.player(match, e.defuser()));
            case MatchFlowEvent.BombDetonated e -> all(match, HudGlyphs.CHAT_WARNING, "chat.bomb-exploded");
            case MatchFlowEvent.RoundEnded e -> {
                for (Player p : match.onlinePlayers()) {
                    MatchMember me = match.member(p.getUniqueId()).orElse(null);
                    boolean won = me != null && me.team() == e.winnerTeam();
                    send(p, won ? HudGlyphs.CHAT_WARNING : HudGlyphs.CHAT_WARNING, won ? "chat.round-won" : "chat.round-lost",
                            "team", ChatStyle.team(match, e.winnerTeam()), "round", e.round(),
                            "reason", e.reason().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                            "score_a", Component.text(e.scoreA(), TeamStyle.color(dev.tactical.engine.team.TeamId.A)),
                            "score_b", Component.text(e.scoreB(), TeamStyle.color(dev.tactical.engine.team.TeamId.B)));
                }
            }
            case MatchFlowEvent.HalftimeStarted e -> all(match, HudGlyphs.CHAT_WARNING, "chat.halftime");
            case MatchFlowEvent.SuddenDeathStarted e -> all(match, HudGlyphs.CHAT_WARNING, "chat.sudden-death");
            case MatchFlowEvent.MatchEnded e -> {
                if (e.winner() == null) all(match, HudGlyphs.CHAT_WARNING, "chat.match-aborted",
                        "reason", e.reason().name().toLowerCase(Locale.ROOT) + (e.detail().isEmpty() ? "" : " - " + e.detail()));
                log.info("[match=" + match.id() + "] ended: " + e.reason() + " winner=" + e.winner() + " score " + e.scoreA() + "-" + e.scoreB());
            }
            case MatchFlowEvent.Paused e -> all(match, HudGlyphs.CHAT_WARNING, "chat.paused", "reason", e.reason().isEmpty() ? "" : " (" + e.reason() + ")");
            case MatchFlowEvent.ResumeCountdown e -> all(match, HudGlyphs.CHAT_WARNING, "chat.resume-countdown", "seconds", e.countdownTicks() / ModeRules.TICKS_PER_SECOND);
            case MatchFlowEvent.Resumed e -> all(match, HudGlyphs.CHAT_WARNING, "chat.resumed");
            case MatchFlowEvent.PlayerDisconnected e -> all(match, HudGlyphs.CHAT_WARNING, "chat.disconnected", "player", ChatStyle.player(match, e.player()));
            case MatchFlowEvent.PlayerReconnected e -> all(match, HudGlyphs.CHAT_WARNING, "chat.reconnected", "player", ChatStyle.player(match, e.player()));
            default -> { }
        }
    }

    private void all(GameMatch match, char icon, String key, Object... placeholders) {
        Component line = line(icon, key, placeholders);
        for (Player p : match.onlinePlayers()) p.sendMessage(line);
    }

    private void send(Player p, char icon, String key, Object... placeholders) {
        p.sendMessage(line(icon, key, placeholders));
    }

    /** The warning icon takes the colour of its message's own text; other icons keep their art colours. */
    private Component line(char icon, String key, Object... placeholders) {
        Component body = messages.render(key, placeholders);
        if (icon != HudGlyphs.CHAT_WARNING) return ChatStyle.line(icon, body);
        return ChatStyle.line(icon, messageColor(key), body);
    }

    /**
     * First colour the message template itself gives to visible text (placeholders such as {@code <player>} are left
     * unresolved, so a player's team colour never counts). White if the template has no colour.
     */
    private net.kyori.adventure.text.format.TextColor messageColor(String key) {
        net.kyori.adventure.text.format.TextColor c = firstColor(messages.render(key), null);
        return c == null ? NamedTextColor.WHITE : c;
    }

    private static net.kyori.adventure.text.format.TextColor firstColor(Component c, net.kyori.adventure.text.format.TextColor inherited) {
        net.kyori.adventure.text.format.TextColor color = c.color() != null ? c.color() : inherited;
        if (c instanceof net.kyori.adventure.text.TextComponent t) {
            String visible = t.content().replaceAll("<[^>]*>", "").trim();
            if (!visible.isEmpty() && color != null) return color;
        }
        for (Component child : c.children()) {
            net.kyori.adventure.text.format.TextColor found = firstColor(child, color);
            if (found != null) return found;
        }
        return null;
    }
}
