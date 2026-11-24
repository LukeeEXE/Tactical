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
import net.kyori.adventure.text.format.TextColor;
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
import java.util.Locale;
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
    /** Per team perspective: [0] content row (tiles, scores, panel; yellow progress line), [1] centred timer text (invisible bar). */
    private final Map<MatchId, EnumMap<TeamId, BossBar[]>> bars = new HashMap<>();

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
        EnumMap<TeamId, BossBar[]> perTeam = new EnumMap<>(TeamId.class);
        for (TeamId t : TeamId.values()) {
            perTeam.put(t, new BossBar[]{
                    BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS),
                    BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)});
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
        EnumMap<TeamId, BossBar[]> b = bars.remove(match.id());
        for (MatchMember m : match.members()) {
            Player p = Bukkit.getPlayer(m.uuid());
            if (p == null) continue;
            if (b != null) b.values().forEach(pair -> { p.hideBossBar(pair[0]); p.hideBossBar(pair[1]); });
            p.playerListName(null);
        }
    }

    private void attach(GameMatch match, Player p) {
        MatchMember m = match.member(p.getUniqueId()).orElse(null);
        EnumMap<TeamId, BossBar[]> b = bars.get(match.id());
        if (m == null || b == null) return;
        b.values().forEach(pair -> { p.hideBossBar(pair[0]); p.hideBossBar(pair[1]); });
        p.showBossBar(b.get(m.team())[0]); // shown first = drawn on top
        p.showBossBar(b.get(m.team())[1]);
        p.playerListName(Component.text(" ").append(Component.text(p.getName(), TeamStyle.color(m.team()))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Heads.remember(e.getPlayer());
        matches.matchOf(e.getPlayer().getUniqueId()).ifPresent(m -> attach(m, e.getPlayer()));
    }

    // ------------------------------------------------------------------ boss bar

    /**
     * Row 1 holds the tiles, scores and timer panel, laid out SYMMETRICALLY (fixed-width tile groups and score cells),
     * so the panel's centre is exactly the screen centre. Row 2 holds only the timer text, moved up by one boss-bar
     * row; the client centres it on the screen too, so the text sits exactly in the middle of the panel whatever
     * width the client gives the font.
     */
    private void update(GameMatch match) {
        EnumMap<TeamId, BossBar[]> b = bars.get(match.id());
        if (b == null) return;
        MatchFlow f = match.flow();
        HudConfig h = config.hud();
        Timer timer = timer(f);
        int textWidth = text.ttfWidth(h.bossbarFont(), timer.plain());
        if (textWidth == 0) textWidth = HudText.vanillaWidth(timer.plain());
        int panelWidth = textWidth + 2 * h.panelPadding();
        int cell = Math.max(text.ttfWidth(h.bossbarFont(), "88"), 10);
        Component textRow = HudText.ttf(h.bossbarFont(), h.bossbarTextOffsetY() - h.rowSpacing(), timer.text());
        float progress = f.stateDurationTicks() <= 0 ? 1f
                : Math.max(0f, Math.min(1f, f.stateTicksRemaining() / (float) f.stateDurationTicks()));
        int gap = h.sectionGap();
        for (TeamId viewer : TeamId.values()) {
            Component row = Component.text()
                    .append(tiles(match, viewer)).append(TextOffsets.space(gap))
                    .append(scoreCell(f, viewer, cell, true)).append(TextOffsets.space(gap))
                    .append(panel(panelWidth)).append(TextOffsets.space(gap))
                    .append(scoreCell(f, viewer.other(), cell, false)).append(TextOffsets.space(gap))
                    .append(tiles(match, viewer.other()))
                    .build();
            BossBar[] pair = b.get(viewer);
            if (!row.equals(pair[0].name())) pair[0].name(row);
            if (!textRow.equals(pair[1].name())) pair[1].name(textRow);
            if (Math.abs(pair[0].progress() - progress) > 0.001f) pair[0].progress(progress);
        }
    }

    /** A score in a fixed-width cell, aligned towards the timer, so both sides of the row are equally wide. */
    private Component scoreCell(MatchFlow f, TeamId team, int cell, boolean leftSide) {
        HudConfig h = config.hud();
        String value = String.valueOf(f.scoreOf(team));
        int w = text.ttfWidth(h.bossbarFont(), value);
        if (w == 0) w = HudText.vanillaWidth(value);
        Component score = HudText.ttf(h.bossbarFont(), h.bossbarTextOffsetY(), Component.text(value, TeamStyle.color(team)));
        Component pad = TextOffsets.space(Math.max(0, cell - w));
        return leftSide ? Component.text().append(pad).append(score).build() : Component.text().append(score).append(pad).build();
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
        int slots = 0;
        for (MatchMember m : match.members()) {
            if (m.team() != team) continue;
            if (slots++ > 0) row.append(TextOffsets.space(h.tileGap()));
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
        // Pad to the full team size so both tile groups are equally wide (keeps the panel centred).
        for (int i = slots; i < match.rules().teamSize(); i++) {
            row.append(TextOffsets.space((i > 0 ? h.tileGap() : 0) + tileAdvance));
        }
        return row.build();
    }

    private record Timer(Component text, String plain) {
    }

    /** {@code LABEL: 00:10} (white label, yellow time), drawn over the panel. */
    private static Timer timer(MatchFlow f) {
        String label;
        String value = "";
        NamedTextColor valueColor = NamedTextColor.YELLOW;
        String time = clock(f.stateTicksRemaining());
        if (f.isPaused()) {
            label = "MATCH PAUSED";
        } else {
            switch (f.state()) {
                case KIT_SELECT -> { label = "SELECT YOUR KIT:"; value = time; }
                case BUY_PHASE -> { label = (f.isSuddenDeathRound() ? "SUDDEN DEATH" : "ROUND " + f.round()) + " STARTS IN:"; value = time; }
                case ROUND_LIVE -> { label = f.isSuddenDeathRound() ? "SUDDEN DEATH:" : "ROUND " + f.round() + ":"; value = time; }
                case BOMB_PLANTED -> { label = "BOMB PLANTED:"; value = time; valueColor = NamedTextColor.RED; }
                case ROUND_END -> label = "ROUND " + f.round() + " OVER";
                case HALFTIME -> { label = "HALFTIME:"; value = time; }
                case SUDDEN_DEATH_KIT_SELECT -> { label = "SUDDEN DEATH IN:"; value = time; valueColor = NamedTextColor.RED; }
                case MATCH_END, POSTGAME, CLEANUP, CLOSED -> label = "MATCH OVER";
                case ABORTED -> label = "MATCH ENDED";
                default -> label = "LOADING";
            }
        }
        Component words = Component.text(label, NamedTextColor.WHITE);
        if (!value.isEmpty()) words = words.append(Component.text(" " + value, valueColor));
        return new Timer(words, value.isEmpty() ? label : label + " " + value);
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

    // Results layout (chat is 320 px wide at the default chat width).
    private static final int CHAT_WIDTH = 320;
    private static final int DOTS_END = 146;
    private static final int[] STAT_RIGHT = {166, 192, 218, 252, 296}; // K, D, A, K/D, damage (number right edges)

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
        lines.add(separator());
        Component headline;
        int headlineWidth;
        if (end.winner() != null) {
            String words = " " + TeamStyle.roleName(f.sideOf(end.winner())) + " won the match!";
            headline = Component.text().append(ChatStyle.icon(end.winner() == TeamId.A ? HudGlyphs.CHAT_SHIELD_RED : HudGlyphs.CHAT_SHIELD_BLUE))
                    .append(Component.text(words, TeamStyle.color(end.winner()))).build();
            headlineWidth = 9 + HudText.vanillaWidth(words);
        } else {
            String words = "The match ended without a winner";
            headline = Component.text(words, NamedTextColor.RED);
            headlineWidth = HudText.vanillaWidth(words);
        }
        lines.add(Component.text().append(TextOffsets.space(Math.max(0, (CHAT_WIDTH - headlineWidth) / 2))).append(headline).build());
        for (TeamId team : end.winner() == null ? List.of(TeamId.A, TeamId.B) : List.of(end.winner(), end.winner().other())) {
            lines.add(Component.empty());
            lines.add(teamHeader(match, team, team == end.winner()));
            List<MatchMember> members = new ArrayList<>(match.members().stream().filter(m -> m.team() == team).toList());
            members.sort(Comparator.comparingDouble((MatchMember m) -> st.getOrDefault(m.uuid(), new MatchStats()).score()).reversed());
            int rank = 1;
            for (MatchMember m : members) lines.add(playerRow(match, m, rank++, st.getOrDefault(m.uuid(), new MatchStats()), m.uuid().equals(mvp)));
        }
        lines.add(separator());
        int postgameSecs = match.rules().postgameTicks() / ModeRules.TICKS_PER_SECOND;
        lines.add(ChatStyle.line(HudGlyphs.CHAT_TIMER, Component.text("Returning to the lobby in " + postgameSecs + "s.", NamedTextColor.GRAY)));

        for (Player p : match.onlinePlayers()) {
            for (Component l : lines) p.sendMessage(l);
            MatchMember me = match.member(p.getUniqueId()).orElse(null);
            if (end.winner() == null || me == null) continue;
            boolean won = me.team() == end.winner();
            p.showTitle(Title.title(
                    Component.text(won ? "VICTORY" : "DEFEAT", won ? NamedTextColor.GOLD : NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Component.empty(),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(Math.max(3, postgameSecs - 1)), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.8f, won ? 1f : 1.4f);
        }
    }

    private static Component separator() {
        return Component.text(" ".repeat(CHAT_WIDTH / 4 - 1), NamedTextColor.AQUA).decorate(TextDecoration.STRIKETHROUGH);
    }

    /** {@code [shield] Attackers  (WINNER)} with the stat column legend on the right. */
    private Component teamHeader(GameMatch match, TeamId team, boolean winner) {
        TextComponent.Builder b = Component.text();
        String name = TeamStyle.roleName(match.flow().sideOf(team));
        b.append(Component.text("[", NamedTextColor.WHITE))
                .append(ChatStyle.icon(team == TeamId.A ? HudGlyphs.CHAT_SHIELD_RED : HudGlyphs.CHAT_SHIELD_BLUE))
                .append(Component.text("] ", NamedTextColor.WHITE))
                .append(Component.text(name, TeamStyle.color(team)).decorate(TextDecoration.BOLD));
        int x = HudText.vanillaWidth("[") + 9 + HudText.vanillaWidth("] ") + HudText.vanillaWidth(name) + name.length(); // bold = +1 px/char
        if (winner) {
            b.append(Component.text("  WINNER", NamedTextColor.GOLD));
            x += HudText.vanillaWidth("  WINNER");
        }
        char[] icons = {HudGlyphs.STAT_KILLS, HudGlyphs.STAT_DEATHS, HudGlyphs.STAT_ASSISTS, HudGlyphs.STAT_KDR, HudGlyphs.STAT_DAMAGE};
        for (int i = 0; i < icons.length; i++) {
            int target = STAT_RIGHT[i] + 1; // icon sits right after the numbers
            b.append(TextOffsets.space(Math.max(1, target - x))).append(ChatStyle.icon(icons[i]));
            x = Math.max(x + 1, target) + 9;
        }
        return b.build();
    }

    /** {@code -> 1. <head> Name ........  5 <k>  2 <d>  1 <a>  2.50 <kd>  596 <dmg>} */
    private Component playerRow(GameMatch match, MatchMember m, int rank, MatchStats s, boolean mvp) {
        HudConfig h = config.hud();
        TextColor color = TeamStyle.color(m.team());
        TextComponent.Builder b = Component.text();
        int x = 0;
        b.append(TextOffsets.space(6)).append(ChatStyle.icon(HudGlyphs.CHAT_ARROW));
        x += 6 + 9;
        String r = " " + rank + ". ";
        b.append(Component.text(r, NamedTextColor.GRAY));
        x += HudText.vanillaWidth(r);
        if (m.virtual()) {
            b.append(Component.text("\u25CF", color));
            x += 6;
        } else {
            b.append(Heads.text(m.uuid(), m.name()));
            x += h.headAdvance();
        }
        b.append(Component.text(" " + m.name(), color));
        x += HudText.vanillaWidth(" " + m.name());
        if (mvp) {
            b.append(Component.text(" ")).append(ChatStyle.icon(HudGlyphs.CHAT_MVP));
            x += 4 + 9;
        }
        // dotted leader up to the stat columns
        b.append(Component.text(" ", NamedTextColor.DARK_GRAY));
        x += 4;
        int dots = Math.max(0, (DOTS_END - x) / 2);
        if (dots > 0) b.append(Component.text(".".repeat(dots), NamedTextColor.DARK_GRAY));
        x += dots * 2;
        String kd = String.format(Locale.ROOT, "%.2f", s.kills / (double) Math.max(1, s.deaths));
        String[] values = {String.valueOf(s.kills), String.valueOf(s.deaths), String.valueOf(s.assists), kd, String.valueOf(Math.round(s.damage))};
        char[] icons = {HudGlyphs.STAT_KILLS, HudGlyphs.STAT_DEATHS, HudGlyphs.STAT_ASSISTS, HudGlyphs.STAT_KDR, HudGlyphs.STAT_DAMAGE};
        for (int i = 0; i < values.length; i++) {
            int w = HudText.vanillaWidth(values[i]);
            int pad = Math.max(2, STAT_RIGHT[i] - w - x);
            b.append(TextOffsets.space(pad)).append(Component.text(values[i], NamedTextColor.WHITE));
            b.append(TextOffsets.space(1)).append(ChatStyle.icon(icons[i]));
            x += pad + w + 1 + 9;
        }
        return b.build();
    }
}
