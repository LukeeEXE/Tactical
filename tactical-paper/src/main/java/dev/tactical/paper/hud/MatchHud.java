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
import dev.tactical.engine.pack.HudGlyphs;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
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
    private final dev.tactical.paper.config.ConfigService config;
    private final HudText text;
    private final Map<MatchId, EnumMap<TeamId, BossBar>> bars = new HashMap<>();

    public MatchHud(Plugin plugin, MatchService matches, StatsTracker stats, dev.tactical.paper.config.ConfigService config, HudText text) {
        this.plugin = plugin;
        this.matches = matches;
        this.stats = stats;
        this.config = config;
        this.text = text;
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
            perTeam.put(t, BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
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
        Component center = timerPanel(f);
        float progress = f.stateDurationTicks() <= 0 ? 1f
                : Math.max(0f, Math.min(1f, f.stateTicksRemaining() / (float) f.stateDurationTicks()));
        int gap = config.hud().sectionGap();
        for (TeamId viewer : TeamId.values()) {
            Component text = Component.text()
                    .append(score(f, viewer)).append(TextOffsets.space(gap))
                    .append(tiles(match, viewer)).append(TextOffsets.space(gap))
                    .append(center).append(TextOffsets.space(gap))
                    .append(tiles(match, viewer.other())).append(TextOffsets.space(gap))
                    .append(score(f, viewer.other()))
                    .build();
            BossBar bar = b.get(viewer);
            if (!text.equals(bar.name())) bar.name(text);
            if (Math.abs(bar.progress() - progress) > 0.001f) bar.progress(progress);
            if (bar.color() != BossBar.Color.YELLOW) bar.color(BossBar.Color.YELLOW); // restyled as a thin line by the pack
        }
    }

    private Component score(MatchFlow f, TeamId team) {
        return HudText.ttf(config.hud().bossbarFont(), config.hud().bossbarTextOffsetY(),
                Component.text(String.valueOf(f.scoreOf(team)), TeamStyle.color(team)));
    }

    /**
     * One translucent team-coloured tile per player with the player's head on top (a skull on a dark tile when
     * dead). Every tile has a fixed advance, whatever width the client gives the head, so the row never drifts.
     */
    private Component tiles(GameMatch match, TeamId team) {
        HudConfig h = config.hud();
        Key font = Key.key("tactical", "hud_bossbar");
        int tileAdvance = HudGlyphs.TILE_SIZE + 1;
        TextComponent.Builder row = Component.text();
        boolean first = true;
        for (MatchMember m : match.members()) {
            if (m.team() != team) continue;
            if (!first) row.append(TextOffsets.space(h.tileGap()));
            first = false;
            boolean active = match.flow().participant(m.uuid()).map(Participant::active).orElse(false);
            char tile = !active ? HudGlyphs.BAR_TILE_DEAD : team == TeamId.A ? HudGlyphs.BAR_TILE_RED : HudGlyphs.BAR_TILE_BLUE;
            row.append(Component.text(String.valueOf(tile)).font(font).color(NamedTextColor.WHITE));
            row.append(TextOffsets.space(-tileAdvance + 1)); // back to 1 px inside the tile
            int iconAdvance;
            if (!active) {
                row.append(Component.text(String.valueOf(HudGlyphs.BAR_SKULL)).font(font).color(NamedTextColor.WHITE));
                iconAdvance = 9;
            } else if (m.virtual()) {
                row.append(Component.text("\u25CF", TeamStyle.color(team)));
                iconAdvance = 6;
            } else {
                row.append(Heads.text(m.uuid(), m.name()));
                iconAdvance = h.headAdvance();
            }
            row.append(TextOffsets.space(tileAdvance - 1 - iconAdvance)); // to the end of the tile
        }
        return row.build();
    }

    /** {@code LABEL: 00:10} on a dark rounded panel sized to the text, like a round-start timer. */
    private Component timerPanel(MatchFlow f) {
        HudConfig h = config.hud();
        String label;
        String value;
        NamedTextColor valueColor = NamedTextColor.YELLOW;
        String time = clock(f.stateTicksRemaining());
        if (f.isPaused()) {
            label = "MATCH PAUSED";
            value = "";
        } else {
            switch (f.state()) {
                case KIT_SELECT -> { label = "SELECT YOUR KIT:"; value = time; }
                case BUY_PHASE -> { label = (f.isSuddenDeathRound() ? "SUDDEN DEATH" : "ROUND " + f.round()) + " STARTS IN:"; value = time; }
                case ROUND_LIVE -> { label = (f.isSuddenDeathRound() ? "SUDDEN DEATH:" : "ROUND " + f.round() + ":"); value = time; }
                case BOMB_PLANTED -> { label = "BOMB PLANTED:"; value = time; valueColor = NamedTextColor.RED; }
                case ROUND_END -> { label = "ROUND " + f.round() + " OVER"; value = ""; }
                case HALFTIME -> { label = "HALFTIME:"; value = time; }
                case SUDDEN_DEATH_KIT_SELECT -> { label = "SUDDEN DEATH IN:"; value = time; valueColor = NamedTextColor.RED; }
                case MATCH_END, POSTGAME, CLEANUP, CLOSED -> { label = "MATCH OVER"; value = ""; }
                case ABORTED -> { label = "MATCH ENDED"; value = ""; }
                default -> { label = "LOADING"; value = ""; }
            }
        }
        String font = h.bossbarFont();
        int textWidth = text.ttfWidth(font, value.isEmpty() ? label : label + " " + value);
        if (textWidth == 0) textWidth = HudText.vanillaWidth(value.isEmpty() ? label : label + " " + value); // font missing
        int panelWidth = textWidth + 2 * h.panelPadding();
        Component words = Component.text(label, NamedTextColor.WHITE);
        if (!value.isEmpty()) words = words.append(Component.text(" " + value, valueColor));
        return Component.text()
                .append(panel(panelWidth)).append(TextOffsets.space(-panelWidth))
                .append(TextOffsets.space(h.panelPadding()))
                .append(HudText.ttf(font, h.bossbarTextOffsetY(), words))
                .append(TextOffsets.space(h.panelPadding()))
                .build();
    }

    /** Panel of exactly {@code width} px: left cap, power-of-two fill pieces, right cap; advance = width. */
    private static Component panel(int width) {
        Key font = Key.key("tactical", "hud_bossbar");
        int fill = Math.max(0, width - 2 * HudGlyphs.PANEL_CAP);
        TextComponent.Builder b = Component.text();
        b.append(Component.text(String.valueOf(HudGlyphs.BAR_PANEL_LEFT)).font(font)).append(TextOffsets.space(-1));
        for (int i = 7; i >= 0; i--) {
            int piece = 1 << i;
            while (fill >= piece) {
                b.append(Component.text(String.valueOf((char) (HudGlyphs.BAR_PANEL_FILL + i))).font(font)).append(TextOffsets.space(-1));
                fill -= piece;
            }
        }
        b.append(Component.text(String.valueOf(HudGlyphs.BAR_PANEL_RIGHT)).font(font)).append(TextOffsets.space(-1));
        return b.build().color(NamedTextColor.WHITE);
    }

    private static String clock(int ticks) {
        int s = (Math.max(0, ticks) + ModeRules.TICKS_PER_SECOND - 1) / ModeRules.TICKS_PER_SECOND;
        return String.format("%02d:%02d", s / 60, s % 60);
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
        int postgameSecs = match.rules().postgameTicks() / ModeRules.TICKS_PER_SECOND;
        for (Player p : match.onlinePlayers()) {
            MatchMember me = match.member(p.getUniqueId()).orElse(null);
            boolean won = me != null && me.team() == end.winner();
            List<Component> lines = new ArrayList<>();
            if (end.winner() != null) {
                lines.add(ChatStyle.line(won ? dev.tactical.engine.pack.HudGlyphs.CHAT_WIN : dev.tactical.engine.pack.HudGlyphs.CHAT_LOSS,
                        ChatStyle.team(match, end.winner()).append(Component.text(" won the match! ", NamedTextColor.WHITE))
                                .append(Component.text(f.scoreOf(end.winner()), TeamStyle.color(end.winner())))
                                .append(Component.text(" - ", NamedTextColor.GRAY))
                                .append(Component.text(f.scoreOf(end.winner().other()), TeamStyle.color(end.winner().other())))));
            } else {
                lines.add(ChatStyle.line(dev.tactical.engine.pack.HudGlyphs.CHAT_WARNING,
                        Component.text("The match ended without a winner (" + end.reason().name().toLowerCase().replace('_', ' ') + ").", NamedTextColor.RED)));
            }
            for (TeamId team : end.winner() == null ? List.of(TeamId.A, TeamId.B) : List.of(end.winner(), end.winner().other())) {
                lines.add(ChatStyle.line(dev.tactical.engine.pack.HudGlyphs.CHAT_INFO,
                        ChatStyle.team(match, team).append(Component.text("   K / D / A   damage", NamedTextColor.GRAY))));
                List<MatchMember> members = new ArrayList<>(match.members().stream().filter(m -> m.team() == team).toList());
                members.sort(Comparator.comparingDouble((MatchMember m) -> st.getOrDefault(m.uuid(), new MatchStats()).score()).reversed());
                for (MatchMember m : members) {
                    MatchStats s = st.getOrDefault(m.uuid(), new MatchStats());
                    Component line = Component.text("      ").append(ChatStyle.player(match, m.uuid()))
                            .append(Component.text("   " + s.kills + " / " + s.deaths + " / " + s.assists + "   " + Math.round(s.damage), NamedTextColor.WHITE));
                    if (m.uuid().equals(mvp)) line = line.append(Component.text("  MVP", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                    lines.add(line);
                }
            }
            lines.add(ChatStyle.line(dev.tactical.engine.pack.HudGlyphs.CHAT_TIMER,
                    Component.text("Returning to the lobby in " + postgameSecs + "s.", NamedTextColor.GRAY)));
            for (Component l : lines) p.sendMessage(l);
            if (end.winner() == null || me == null) continue;
            p.showTitle(Title.title(
                    Component.text(won ? "VICTORY" : "DEFEAT", won ? NamedTextColor.GOLD : NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Component.text(f.scoreOf(me.team()) + " - " + f.scoreOf(me.team().other()), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(Math.max(3, postgameSecs - 1)), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.8f, won ? 1f : 1.4f);
        }
    }
}
