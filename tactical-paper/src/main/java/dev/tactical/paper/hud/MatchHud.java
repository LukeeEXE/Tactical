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
import net.kyori.adventure.text.format.ShadowColor;
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
    /** Per team perspective: [0] timer text, [1] heads/scores/timer panel, [2] bomb icon (see update()). */
    private final Map<MatchId, EnumMap<TeamId, BossBar[]>> bars = new HashMap<>();
    private final Map<MatchId, Long> hudTicks = new HashMap<>();
    private final Map<MatchId, Long> bombFlashUntil = new HashMap<>();

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
            perTeam.put(t, new BossBar[]{ // WHITE = invisible bar sprite (resource pack): only the names show
                    BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS),
                    BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS),
                    BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)});
        }
        bars.put(match.id(), perTeam);
        for (Player p : match.onlinePlayers()) attach(match, p);
        int[] ticks = {0};
        match.flow().addTickable(f -> {
            hudTicks.put(match.id(), (long) ++ticks[0]);
            if (ticks[0] % 2 == 0) update(match);
        });
        match.bus().subscribe(dev.tactical.paper.match.MatchEvents.BombBeep.class, beep -> {
            bombFlashUntil.put(match.id(), hudTicks.getOrDefault(match.id(), 0L) + 3);
            update(match);
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
        hudTicks.remove(match.id());
        bombFlashUntil.remove(match.id());
        for (MatchMember m : match.members()) {
            Player p = Bukkit.getPlayer(m.uuid());
            if (p == null) continue;
            if (b != null) b.values().forEach(trio -> { for (BossBar bar : trio) p.hideBossBar(bar); });
            p.playerListName(null);
        }
    }

    private void attach(GameMatch match, Player p) {
        MatchMember m = match.member(p.getUniqueId()).orElse(null);
        EnumMap<TeamId, BossBar[]> b = bars.get(match.id());
        if (m == null || b == null) return;
        b.values().forEach(trio -> { for (BossBar bar : trio) p.hideBossBar(bar); });
        for (BossBar bar : b.get(m.team())) p.showBossBar(bar); // shown first = drawn on top
        p.playerListName(Component.text(" ").append(Component.text(p.getName(), TeamStyle.color(m.team()))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Heads.remember(e.getPlayer());
        matches.matchOf(e.getPlayer().getUniqueId()).ifPresent(m -> attach(m, e.getPlayer()));
    }

    // ------------------------------------------------------------------ boss bar
    /** Every label the timer can show (with a worst-case time), for the space reserved for the timer panel. */
    private static final List<String> TIMER_LABELS = List.of("SELECT YOUR KIT: 00:00", "STARTING IN: 00:00",
            "ROUND 88: 00:00", "SUDDEN DEATH: 00:00", "00:00",
            "ROUND 88 OVER", "HALFTIME: 00:00", "SUDDEN DEATH IN: 00:00", "MATCH PAUSED", "MATCH OVER", "MATCH ENDED");
    /** Same yellow as the timer bar segments. */
    static final TextColor TIME_YELLOW = TextColor.color(0xFFFF00);
    private static final TextColor BOMB_DIM = TextColor.color(0x707070);

    /**
     * Three boss bars per team perspective, all with the invisible WHITE bar sprite:
     * <ol>
     *   <li>the content row at the top of the screen: [team strip] [score] [timer panel + timer line] [score]
     *       [team strip]. Every section reserves a fixed width, so heads and scores never move; the panel fits the
     *       text (with the widest digits, so it does not twitch while counting) and is centred in its space;</li>
     *   <li>the timer text, moved UP one boss-bar row onto the panel; drawn after the panel, so on top of it, and
     *       centred on the screen by the client;</li>
     *   <li>the large bomb icon while the bomb is planted (flashes with every beep), empty otherwise.</li>
     * </ol>
     * Inline player heads cannot be moved vertically, so everything is centred on them (rows 0-7). No text shadows.
     */
    private void update(GameMatch match) {
        EnumMap<TeamId, BossBar[]> b = bars.get(match.id());
        if (b == null) return;
        MatchFlow f = match.flow();
        HudConfig h = config.hud();
        Timer timer = timer(f);
        Component textRow = HudText.ttf(h.bossbarFont(), h.bossbarTextOffsetY() - h.rowSpacing(), timer.text())
                .shadowColor(ShadowColor.none());
        float progress = f.stateDurationTicks() <= 0 ? 1f
                : Math.max(0f, Math.min(1f, f.stateTicksRemaining() / (float) f.stateDurationTicks()));
        int gap = h.sectionGap();
        Component timerSlot = timerSlot(timer, progress, f.stateDurationTicks() > 0 && !f.isPaused());
        Component bomb = bombRow(match);
        for (TeamId viewer : TeamId.values()) {
            Component row = Component.text()
                    .append(strip(match, viewer, true)).append(TextOffsets.space(gap))
                    .append(scoreBox(f, viewer)).append(TextOffsets.space(gap))
                    .append(timerSlot).append(TextOffsets.space(gap))
                    .append(scoreBox(f, viewer.other())).append(TextOffsets.space(gap))
                    .append(strip(match, viewer.other(), false))
                    .build()
                    .shadowColor(ShadowColor.none());
            BossBar[] trio = b.get(viewer);
            if (!row.equals(trio[0].name())) trio[0].name(row);
            if (!textRow.equals(trio[1].name())) trio[1].name(textRow);
            if (!bomb.equals(trio[2].name())) trio[2].name(bomb);
        }
    }

    /**
     * Fixed-width space for the timer: the panel and the 1 px timer line, both centred in it. Their width is that of
     * the text with every digit at its widest, so "00:11" and "00:12" get the same panel; the line is exactly as wide
     * as the text.
     */
    private Component timerSlot(Timer timer, float progress, boolean showBar) {
        HudConfig h = config.hud();
        int slot = h.timerSlotWidth() > 0 ? h.timerSlotWidth() : autoTimerSlot();
        int ink = Math.max(1, width(timer.plain().replaceAll("[0-9]", "0")) - 1); // Pinch advance = ink + 1
        int panelWidth = ink + 2 * h.panelPadding();
        TextComponent.Builder b = Component.text().append(TextOffsets.space(slot / 2))
                .append(HudShapes.centred(HudShapes.rect(HudGlyphs.BAR_PANEL, panelWidth, "hud_bossbar", NamedTextColor.BLACK), panelWidth));
        if (showBar) {
            int filled = Math.max(0, Math.min(ink, (int) Math.ceil(progress * ink)));
            TextComponent.Builder line = Component.text();
            if (filled > 0) line.append(HudShapes.rect(HudGlyphs.TIME_FILL, filled, "hud_bossbar", TIME_YELLOW));
            if (ink - filled > 0) line.append(HudShapes.rect(HudGlyphs.TIME_EMPTY, ink - filled, "hud_bossbar", NamedTextColor.BLACK));
            b.append(HudShapes.centred(line.build(), ink));
        }
        return b.append(TextOffsets.space(slot - slot / 2)).build();
    }

    private int autoTimerSlot() {
        int max = 0;
        for (String l : TIMER_LABELS) max = Math.max(max, width(l));
        return max - 1 + 2 * config.hud().panelPadding();
    }

    private int width(String s) {
        int w = text.ttfWidth(config.hud().bossbarFont(), s);
        return w == 0 ? HudText.vanillaWidth(s) : w;
    }

    /** Big white score (double-size font) centred in a square dark box. */
    private Component scoreBox(MatchFlow f, TeamId team) {
        HudConfig h = config.hud();
        int box = HudGlyphs.BAR_SCORE.height(); // square
        String value = String.valueOf(f.scoreOf(team));
        int advance = 2 * text.ttfWidth(h.bossbarFont(), value);
        if (advance == 0) advance = HudText.vanillaWidth(value);
        int ink = Math.max(1, advance - 2); // the x2 font's advance ends with 2 px of spacing
        int left = Math.round((box - ink) / 2f);
        Component score = HudText.ttf(h.bossbarFont() + "_x2", h.scoreOffsetY(), Component.text(value, NamedTextColor.WHITE));
        return Component.text()
                .append(HudShapes.rect(HudGlyphs.BAR_SCORE, box, "hud_bossbar", NamedTextColor.BLACK))
                .append(TextOffsets.space(-box + left)).append(score).append(TextOffsets.space(box - left - advance))
                .build();
    }

    /** Space reserved for one team's strip: a full team, so both sides are equally wide. */
    private int stripSlot(GameMatch match) {
        return stripWidth(match.rules().teamSize()) + HudGlyphs.STRIP_POINT;
    }

    private int stripWidth(int players) {
        HudConfig h = config.hud();
        return 2 * h.stripPadding() + (Math.max(1, players) - 1) * h.headSlot() + 8;
    }

    /**
     * One translucent team-coloured rectangle around the team's heads (a skull when dead), sized for the players the
     * team actually has and placed against the centre of the screen within the team's fixed space.
     */
    private Component strip(GameMatch match, TeamId team, boolean leftSide) {
        HudConfig h = config.hud();
        List<MatchMember> members = new ArrayList<>();
        for (MatchMember m : match.members()) if (m.team() == team && members.size() < match.rules().teamSize()) members.add(m);
        int slot = stripSlot(match);
        int point = HudGlyphs.STRIP_POINT;
        int width = stripWidth(members.size()) + point; // pointed on the outer end, where nothing is next to it
        int lead = leftSide ? slot - width : 0;
        int headsStart = (leftSide ? point : 0) + h.stripPadding();
        Key font = Key.key("tactical", "hud_bossbar");
        TextComponent.Builder row = Component.text().append(TextOffsets.space(lead))
                .append(HudShapes.rect(leftSide ? HudGlyphs.STRIP_POINT_LEFT : HudGlyphs.STRIP_POINT_RIGHT, width, "hud_bossbar", TeamStyle.color(team)))
                .append(TextOffsets.space(-width + headsStart));
        int used = headsStart;
        for (int i = 0; i < members.size(); i++) {
            MatchMember m = members.get(i);
            boolean active = match.flow().participant(m.uuid()).map(Participant::active).orElse(false);
            int iconAdvance;
            if (!active) {
                row.append(Component.text(String.valueOf(HudGlyphs.BAR_SKULL)).font(font).color(NamedTextColor.WHITE));
                iconAdvance = 9;
            } else if (m.virtual()) {
                // test players: a 5 px dot centred in the 8 px head cell
                row.append(TextOffsets.space(2)).append(Component.text("o", NamedTextColor.WHITE));
                iconAdvance = 2 + 6;
            } else {
                row.append(Heads.text(m.uuid(), m.name()));
                iconAdvance = h.headAdvance();
            }
            int step = i < members.size() - 1 ? h.headSlot() : 8;
            row.append(TextOffsets.space(step - iconAdvance));
            used += step;
        }
        row.append(TextOffsets.space(width - used + (slot - width - lead))); // ends exactly at the slot's edge
        return row.build();
    }

    /** Large bomb icon while planted: bright for a moment on every beep, dim in between. */
    private Component bombRow(GameMatch match) {
        if (match.state() != MatchState.BOMB_PLANTED) return Component.empty();
        long now = hudTicks.getOrDefault(match.id(), 0L);
        boolean flash = now <= bombFlashUntil.getOrDefault(match.id(), -1L);
        return Component.text(String.valueOf(HudGlyphs.BAR_BOMB)).font(Key.key("tactical", "hud_bossbar"))
                .color(flash ? NamedTextColor.WHITE : BOMB_DIM).shadowColor(ShadowColor.none());
    }

    private record Timer(Component text, String plain) {
    }

    /** {@code LABEL: 00:10} (white label, yellow time), drawn over the panel. Only the red time while planted. */
    private static Timer timer(MatchFlow f) {
        String label;
        String value = "";
        TextColor valueColor = TIME_YELLOW;
        String time = clock(f.stateTicksRemaining());
        if (f.isPaused()) {
            label = "MATCH PAUSED";
        } else {
            switch (f.state()) {
                case KIT_SELECT -> { label = "SELECT YOUR KIT:"; value = time; }
                case BUY_PHASE -> { label = "STARTING IN:"; value = time; }
                case ROUND_LIVE -> { label = f.isSuddenDeathRound() ? "SUDDEN DEATH:" : "ROUND " + f.round() + ":"; value = time; }
                case BOMB_PLANTED -> { label = ""; value = time; valueColor = NamedTextColor.RED; }
                case ROUND_END -> label = "ROUND " + f.round() + " OVER";
                case HALFTIME -> { label = "HALFTIME:"; value = time; }
                case SUDDEN_DEATH_KIT_SELECT -> { label = "SUDDEN DEATH IN:"; value = time; valueColor = NamedTextColor.RED; }
                case MATCH_END, POSTGAME, CLEANUP, CLOSED -> label = "MATCH OVER";
                case ABORTED -> label = "MATCH ENDED";
                default -> label = "LOADING";
            }
        }
        if (label.isEmpty()) return new Timer(Component.text(value, valueColor), value);
        Component words = Component.text(label, NamedTextColor.WHITE);
        if (!value.isEmpty()) words = words.append(Component.text(" " + value, valueColor));
        return new Timer(words, value.isEmpty() ? label : label + " " + value);
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
            headline = Component.text().append(ChatStyle.teamIcon(end.winner()))
                    .append(Component.text(words, TeamStyle.color(end.winner()))).build();
            headlineWidth = 9 + HudText.vanillaWidth(words);
        } else {
            String words = "The match ended without a winner";
            headline = Component.text(words, NamedTextColor.RED);
            headlineWidth = HudText.vanillaWidth(words);
        }
        lines.add(Component.text().append(TextOffsets.space(Math.max(0, (CHAT_WIDTH - headlineWidth) / 2))).append(headline).build());
        if (!f.roundWinners().isEmpty()) {
            lines.add(Component.empty());
            for (TeamId team : end.winner() == null ? List.of(TeamId.A, TeamId.B) : List.of(end.winner(), end.winner().other())) {
                lines.add(roundHistory(match, team));
            }
        }
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
        lines.add(ChatStyle.line(HudGlyphs.CHAT_WARNING, Component.text("Returning to the lobby in " + postgameSecs + "s.", NamedTextColor.GRAY)));

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

    /**
     * {@code [shield] [W] [L] [W] | [W] ... | [W]}: one box per round from this team's point of view, with a divider
     * after the first half and before the sudden-death round.
     */
    private static Component roundHistory(GameMatch match, TeamId team) {
        List<TeamId> winners = match.flow().roundWinners();
        int half = match.rules().halftimeAfterRound();
        int regular = 2 * match.rules().suddenDeathAt();
        TextComponent.Builder b = Component.text();
        b.append(Component.text("[", NamedTextColor.WHITE))
                .append(ChatStyle.icon(HudGlyphs.CHAT_TEAM).color(TeamStyle.color(team))) // grey icon tinted by team colour
                .append(Component.text("]", NamedTextColor.WHITE));
        for (int i = 0; i < winners.size(); i++) {
            int round = i + 1;
            if (round == half + 1 || (round == regular + 1 && round > half + 1)) {
                b.append(Component.text(" ")).append(ChatStyle.icon(HudGlyphs.ROUND_DIVIDER));
            }
            boolean won = winners.get(i) == team;
            b.append(Component.text(" ")).append(Component.text(won ? "[W]" : "[L]", won ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .font(Key.key("tactical", "pinch")));
        }
        return b.build();
    }

    private static Component separator() {
        return Component.text(" ".repeat(CHAT_WIDTH / 4 - 1), NamedTextColor.GRAY).decorate(TextDecoration.STRIKETHROUGH);
    }

    /** {@code [shield] Attackers  (WINNER)} with the stat column legend on the right. */
    private Component teamHeader(GameMatch match, TeamId team, boolean winner) {
        TextComponent.Builder b = Component.text();
        String name = TeamStyle.roleName(match.flow().sideOf(team));
        b.append(Component.text("[", NamedTextColor.WHITE))
                .append(ChatStyle.teamIcon(team))
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
