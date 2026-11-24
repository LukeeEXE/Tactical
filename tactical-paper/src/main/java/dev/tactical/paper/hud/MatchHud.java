package dev.tactical.paper.hud;

import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchLifecycleHook;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import dev.tactical.paper.stats.MatchStats;
import dev.tactical.paper.stats.StatsTracker;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Basic match HUD (the full resource-pack HUD with custom fonts arrives in Phase 5):
 * <ul>
 *   <li>Boss bar: {@code score  [alive heads]  alive | phase timer | alive  [alive heads]  score}, your team on the
 *       left, teams named by role and coloured by identity (red = started attacking, blue = started defending).
 *       The bar itself shows the remaining time of the current phase.</li>
 *   <li>Tab list names: a space after the head, then the name in the team colour.</li>
 *   <li>End of match: VICTORY/DEFEAT title, results in chat (K/D/A, damage, MVP), countdown back to the lobby.</li>
 * </ul>
 * One boss bar per team perspective per match; text is only re-sent when it changed.
 */
public final class MatchHud implements Service, Listener, MatchLifecycleHook {

    private final Plugin plugin;
    private final MatchService matches;
    private final StatsTracker stats;
    private final Map<MatchId, EnumMap<TeamId, BossBar>> bars = new HashMap<>();

    public MatchHud(Plugin plugin, MatchService matches, StatsTracker stats) {
        this.plugin = plugin;
        this.matches = matches;
        this.stats = stats;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        matches.addHook(this);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        for (GameMatch m : matches.matches()) teardown(m);
    }

    @Override
    public CompletableFuture<Void> prepare(GameMatch match, MatchService.MatchRequest request) {
        EnumMap<TeamId, BossBar> perTeam = new EnumMap<>(TeamId.class);
        for (TeamId t : TeamId.values()) {
            perTeam.put(t, BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
        }
        bars.put(match.id(), perTeam);
        for (Player p : match.onlinePlayers()) attach(match, p);
        int[] ticks = {0};
        match.flow().addTickable(f -> {
            if (++ticks[0] % 10 == 0) update(match);
        });
        // Paused matches are not ticked by the engine, so the HUD also refreshes from the event stream.
        match.bus().subscribe(MatchFlowEvent.class, e -> {
            update(match);
            if (e instanceof MatchFlowEvent.MatchEnded end) showResults(match, end);
            if (e instanceof MatchFlowEvent.PlayerReconnected r) {
                Player p = Bukkit.getPlayer(r.player());
                if (p != null) attach(match, p);
            }
        });
        update(match);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void teardown(GameMatch match) {
        EnumMap<TeamId, BossBar> b = bars.remove(match.id());
        for (MatchMember m : match.members()) {
            Player p = Bukkit.getPlayer(m.uuid());
            if (p == null) continue;
            if (b != null) b.values().forEach(p::hideBossBar);
            p.playerListName(null);
        }
    }

    private void attach(GameMatch match, Player p) {
        MatchMember m = match.member(p.getUniqueId()).orElse(null);
        EnumMap<TeamId, BossBar> b = bars.get(match.id());
        if (m == null || b == null) return;
        b.values().forEach(p::hideBossBar);
        p.showBossBar(b.get(m.team()));
        p.playerListName(Component.text(" ").append(Component.text(p.getName(), TeamStyle.color(m.team()))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Heads.remember(e.getPlayer());
        matches.matchOf(e.getPlayer().getUniqueId()).ifPresent(m -> attach(m, e.getPlayer()));
    }

    // ------------------------------------------------------------------ boss bar

    private void update(GameMatch match) {
        EnumMap<TeamId, BossBar> b = bars.get(match.id());
        if (b == null) return;
        MatchFlow f = match.flow();
        Component center = center(f);
        float progress = f.stateDurationTicks() <= 0 ? 1f
                : Math.max(0f, Math.min(1f, f.stateTicksRemaining() / (float) f.stateDurationTicks()));
        for (TeamId viewer : TeamId.values()) {
            Component text = side(match, viewer, false).append(Component.text("  |  ", NamedTextColor.DARK_GRAY)).append(center)
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY)).append(side(match, viewer.other(), true));
            BossBar bar = b.get(viewer);
            if (!text.equals(bar.name())) bar.name(text);
            if (Math.abs(bar.progress() - progress) > 0.001f) bar.progress(progress);
            BossBar.Color color = f.state() == MatchState.BOMB_PLANTED ? BossBar.Color.RED : BossBar.Color.WHITE;
            if (bar.color() != color) bar.color(color);
        }
    }

    /** Score, alive heads and alive count of one team. Mirrored on the right so scores sit at the outer edges. */
    private Component side(GameMatch match, TeamId team, boolean right) {
        MatchFlow f = match.flow();
        int alive = 0;
        TextComponent.Builder heads = Component.text();
        for (MatchMember m : match.members()) {
            if (m.team() != team) continue;
            boolean active = f.participant(m.uuid()).map(Participant::active).orElse(false);
            if (active) alive++;
            Component icon;
            if (!active) icon = Component.text("☠", NamedTextColor.DARK_GRAY); // skull: dead or disconnected
            else if (m.virtual()) icon = Component.text("●", TeamStyle.color(team));
            else icon = Heads.text(m.uuid(), m.name());
            heads.append(icon).append(Component.text(" "));
        }
        Component score = Component.text(f.scoreOf(team), TeamStyle.color(team)).decorate(TextDecoration.BOLD);
        Component role = TeamStyle.name(f, team);
        Component count = Component.text(alive, NamedTextColor.WHITE);
        if (!right) {
            return Component.text().append(score).append(Component.text(" ")).append(role).append(Component.text("  "))
                    .append(heads.build()).append(count).build();
        }
        return Component.text().append(count).append(Component.text(" ")).append(heads.build()).append(Component.text(" "))
                .append(role).append(Component.text(" ")).append(score).build();
    }

    private static Component center(MatchFlow f) {
        if (f.isPaused()) return Component.text("PAUSED", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD);
        String time = clock(f.stateTicksRemaining());
        return switch (f.state()) {
            case KIT_SELECT -> Component.text("KIT SELECT " + time, NamedTextColor.GOLD);
            case BUY_PHASE -> Component.text("BUY " + time, NamedTextColor.GREEN);
            case ROUND_LIVE -> Component.text(time, NamedTextColor.WHITE).decorate(TextDecoration.BOLD);
            case BOMB_PLANTED -> Component.text("BOMB " + time, NamedTextColor.RED).decorate(TextDecoration.BOLD);
            case ROUND_END -> Component.text("ROUND " + f.round() + " OVER", NamedTextColor.GRAY);
            case HALFTIME -> Component.text("HALFTIME " + time, NamedTextColor.GOLD);
            case SUDDEN_DEATH_KIT_SELECT -> Component.text("SUDDEN DEATH " + time, NamedTextColor.DARK_RED);
            case MATCH_END, POSTGAME, CLEANUP, CLOSED -> Component.text("MATCH OVER", NamedTextColor.GOLD);
            case ABORTED -> Component.text("MATCH ENDED", NamedTextColor.RED);
            default -> Component.text("LOADING", NamedTextColor.GRAY);
        };
    }

    private static String clock(int ticks) {
        int s = (Math.max(0, ticks) + ModeRules.TICKS_PER_SECOND - 1) / ModeRules.TICKS_PER_SECOND;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ results

    private void showResults(GameMatch match, MatchFlowEvent.MatchEnded end) {
        MatchFlow f = match.flow();
        Map<UUID, MatchStats> st = stats.stats(match.id());
        UUID mvp = null;
        double best = -1;
        if (end.winner() != null) {
            for (MatchMember m : match.members()) {
                MatchStats s = st.get(m.uuid());
                if (m.team() == end.winner() && s != null && s.score() > best) {
                    best = s.score();
                    mvp = m.uuid();
                }
            }
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("------------- MATCH RESULT -------------", NamedTextColor.DARK_GRAY));
        if (end.winner() != null) {
            lines.add(TeamStyle.name(f, end.winner()).decorate(TextDecoration.BOLD).append(Component.text(" win the match  ", NamedTextColor.GOLD))
                    .append(Component.text(f.scoreOf(end.winner()), TeamStyle.color(end.winner())))
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(f.scoreOf(end.winner().other()), TeamStyle.color(end.winner().other()))));
        } else {
            lines.add(Component.text("Match ended without a winner (" + end.reason().name().toLowerCase().replace('_', ' ') + ")", NamedTextColor.RED));
        }
        for (TeamId team : end.winner() == null ? List.of(TeamId.A, TeamId.B) : List.of(end.winner(), end.winner().other())) {
            lines.add(TeamStyle.name(f, team).append(Component.text("   K / D / A   damage", NamedTextColor.GRAY)));
            List<MatchMember> members = new ArrayList<>(match.members().stream().filter(m -> m.team() == team).toList());
            members.sort(Comparator.comparingDouble((MatchMember m) -> st.getOrDefault(m.uuid(), new MatchStats()).score()).reversed());
            for (MatchMember m : members) {
                MatchStats s = st.getOrDefault(m.uuid(), new MatchStats());
                Component line = Component.text("  ");
                line = m.virtual() ? line.append(Component.text("●", TeamStyle.color(team)))
                        : line.append(Heads.text(m.uuid(), m.name()));
                line = line.append(Component.text(" " + m.name(), TeamStyle.color(team)))
                        .append(Component.text("   " + s.kills + " / " + s.deaths + " / " + s.assists + "   " + Math.round(s.damage), NamedTextColor.WHITE));
                if (m.uuid().equals(mvp)) line = line.append(Component.text("  MVP", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                lines.add(line);
            }
        }
        lines.add(Component.text("----------------------------------------", NamedTextColor.DARK_GRAY));
        int postgameSecs = match.rules().postgameTicks() / ModeRules.TICKS_PER_SECOND;
        lines.add(Component.text("Returning to the lobby in " + postgameSecs + "s.", NamedTextColor.GRAY));

        for (Player p : match.onlinePlayers()) {
            MatchMember me = match.member(p.getUniqueId()).orElse(null);
            for (Component l : lines) p.sendMessage(l);
            if (end.winner() == null || me == null) continue;
            boolean won = me.team() == end.winner();
            p.showTitle(Title.title(
                    Component.text(won ? "VICTORY" : "DEFEAT", won ? NamedTextColor.GOLD : NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Component.text(f.scoreOf(me.team()) + " - " + f.scoreOf(me.team().other()), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(Math.max(3, postgameSecs - 1)), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.8f, won ? 1f : 1.4f);
        }
    }
}
