package dev.tactical.paper.match;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.hud.ChatStyle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /forfeit}: a team vote to give up the match. Every connected (real) teammate must accept within 20 seconds;
 * a single REJECT cancels it. Teammates get clickable ACCEPT / REJECT buttons in chat; accepts are announced without
 * names. When everyone accepted, the other team wins (end reason FORFEIT).
 */
public final class ForfeitService {

    static final int VOTE_SECONDS = 20;
    private static final TextColor TEXT = NamedTextColor.GRAY;

    private final Plugin plugin;
    private final MatchService matches;

    /** One open vote per (match, team). */
    private final Map<String, Vote> votes = new HashMap<>();

    private static final class Vote {
        final GameMatch match;
        final TeamId team;
        final Set<UUID> yes = new HashSet<>();
        BukkitTask timeout;

        Vote(GameMatch match, TeamId team) {
            this.match = match;
            this.team = team;
        }
    }

    public ForfeitService(Plugin plugin, MatchService matches) {
        this.plugin = plugin;
        this.matches = matches;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("forfeit")
                .requires(src -> src.getExecutor() instanceof Player)
                .executes(ctx -> start((Player) ctx.getSource().getExecutor()))
                .then(Commands.literal("accept").executes(ctx -> answer((Player) ctx.getSource().getExecutor(), true)))
                .then(Commands.literal("reject").executes(ctx -> answer((Player) ctx.getSource().getExecutor(), false)))
                .build();
    }

    private int start(Player p) {
        GameMatch match = matches.matchOf(p.getUniqueId()).orElse(null);
        MatchMember me = match == null ? null : match.member(p.getUniqueId()).orElse(null);
        if (me == null) return fail(p, "You are not in a match.");
        if (!canForfeit(match.flow())) return fail(p, "The match cannot be forfeited right now.");
        String key = key(match, me.team());
        if (votes.containsKey(key)) return answer(p, true);
        Vote v = new Vote(match, me.team());
        v.yes.add(p.getUniqueId());
        votes.put(key, v);
        v.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (votes.remove(key, v)) team(v, Component.text("The forfeit vote ran out of time.", TEXT));
        }, VOTE_SECONDS * 20L);
        Component buttons = Component.text()
                .append(button(HudGlyphs.CHAT_ACCEPT, "/forfeit accept", "Vote to forfeit the match", NamedTextColor.GREEN))
                .append(Component.text(" "))
                .append(button(HudGlyphs.CHAT_REJECT, "/forfeit reject", "Keep playing (cancels the vote)", NamedTextColor.RED))
                .build();
        for (Player t : teammates(v)) {
            t.sendMessage(ChatStyle.line(HudGlyphs.CHAT_WARNING, NamedTextColor.GOLD, Component.text(
                    "Your team wants to forfeit the match. Everyone has to accept within " + VOTE_SECONDS + "s.",
                    NamedTextColor.GOLD)));
            if (!t.getUniqueId().equals(p.getUniqueId())) t.sendMessage(buttons);
            t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f);
        }
        progress(v);
        return check(v, key);
    }

    private int answer(Player p, boolean accept) {
        GameMatch match = matches.matchOf(p.getUniqueId()).orElse(null);
        MatchMember me = match == null ? null : match.member(p.getUniqueId()).orElse(null);
        if (me == null) return fail(p, "You are not in a match.");
        String key = key(match, me.team());
        Vote v = votes.get(key);
        if (v == null) return fail(p, "Your team has no forfeit vote open.");
        if (!accept) {
            close(key, v);
            team(v, Component.text("A teammate rejected the forfeit. Keep fighting!", NamedTextColor.RED));
            return 1;
        }
        if (!v.yes.add(p.getUniqueId())) return fail(p, "You already accepted.");
        progress(v);
        return check(v, key);
    }

    /** "A teammate has accepted to forfeit (2/5)" (no names). */
    private void progress(Vote v) {
        team(v, Component.text("A teammate has accepted to forfeit (" + v.yes.size() + "/" + needed(v) + ").", TEXT));
    }

    private int check(Vote v, String key) {
        if (v.yes.size() < needed(v)) return 1;
        close(key, v);
        if (!v.match.flow().forfeit(v.team)) {
            team(v, Component.text("The match can no longer be forfeited.", TEXT));
            return 1;
        }
        for (MatchMember m : v.match.members()) {
            Player t = Bukkit.getPlayer(m.uuid());
            if (t == null) continue;
            t.sendMessage(ChatStyle.line(HudGlyphs.CHAT_WARNING, NamedTextColor.GOLD, Component.text()
                    .append(ChatStyle.team(v.match, v.team)).append(Component.text(" forfeited the match.", NamedTextColor.GOLD)).build()));
        }
        return 1;
    }

    /** Every connected real teammate has to accept. */
    private int needed(Vote v) {
        return Math.max(1, teammates(v).size());
    }

    private List<Player> teammates(Vote v) {
        List<Player> out = new ArrayList<>();
        for (MatchMember m : v.match.members()) {
            if (m.team() != v.team || m.virtual()) continue;
            Player t = Bukkit.getPlayer(m.uuid());
            if (t != null && t.isOnline()) out.add(t);
        }
        return out;
    }

    private void team(Vote v, Component text) {
        Component line = ChatStyle.line(HudGlyphs.CHAT_WARNING, NamedTextColor.GRAY, text);
        for (Player t : teammates(v)) t.sendMessage(line);
    }

    private void close(String key, Vote v) {
        votes.remove(key, v);
        if (v.timeout != null) v.timeout.cancel();
    }

    private static boolean canForfeit(MatchFlow f) {
        return switch (f.state()) {
            case KIT_SELECT, BUY_PHASE, ROUND_LIVE, BOMB_PLANTED, ROUND_END, HALFTIME, SUDDEN_DEATH_KIT_SELECT -> true;
            default -> false;
        };
    }

    private static Component button(char glyph, String command, String hover, NamedTextColor hoverColor) {
        return ChatStyle.icon(glyph)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, hoverColor)));
    }

    private static int fail(Player p, String text) {
        p.sendMessage(ChatStyle.line(HudGlyphs.CHAT_WARNING, NamedTextColor.RED, Component.text(text, NamedTextColor.RED)));
        return 0;
    }

    private static String key(GameMatch m, TeamId team) {
        return m.id() + "/" + team;
    }
}
