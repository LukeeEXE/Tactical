package dev.tactical.paper.match;

import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.match.MatchEndReason;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.record.RecordingSink;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.audit.AuditService;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.core.Tasks;
import dev.tactical.paper.message.Messages;
import dev.tactical.paper.player.PlayerService;
import dev.tactical.paper.player.SessionContext;
import dev.tactical.paper.record.RecorderService;
import dev.tactical.paper.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates, ticks, isolates and cleans up every match on the server.
 *
 * <ul>
 *   <li>One global tick task drives all matches; there are no per-match repeating tasks to leak.</li>
 *   <li>A player can belong to at most one match (checked and reserved before any async work).</li>
 *   <li>Disconnected participants keep their membership for the configured rejoin window, then lose it.
 *       Memberships of ended matches are always cleared, so nobody can rejoin an ended match.</li>
 *   <li>On cleanup every match resource is released and the match object is dropped (no leaks).</li>
 * </ul>
 */
public final class MatchService implements Service, Listener {

    private final Plugin plugin;
    private final ConfigService config;
    private final Database db;
    private final RecorderService recorders;
    private final PlayerService players;
    private final AuditService audit;
    private final dev.tactical.paper.lobby.LobbyService lobby;
    private final Tasks tasks;
    private final Logger log;
    private final MatchAnnouncer announcer;

    private final Map<MatchId, GameMatch> matches = new LinkedHashMap<>();
    private final Map<UUID, MatchId> membership = new LinkedHashMap<>();
    private final Set<UUID> reserved = new HashSet<>();
    private final List<GameMatch> pendingCleanup = new ArrayList<>();
    private BukkitTask ticker;
    private long serverTicks;

    public MatchService(Plugin plugin, ConfigService config, Database db, RecorderService recorders,
                        PlayerService players, Messages messages, AuditService audit,
                        dev.tactical.paper.lobby.LobbyService lobby, Tasks tasks, Logger log) {
        this.lobby = lobby;
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.recorders = recorders;
        this.players = players;
        this.audit = audit;
        this.tasks = tasks;
        this.log = log;
        this.announcer = new MatchAnnouncer(messages, log, () -> config.general().logStateChanges());
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        // Server stopping: matches cannot continue. End them as INTERRUPTED and finalize their recordings.
        // (Phase 6 adds checkpoint-based recovery so eligible matches can resume after a restart.)
        for (GameMatch m : List.copyOf(matches.values())) {
            m.flow().abort(MatchEndReason.INTERRUPTED, "server stopping");
            m.flow().tick(); // ABORTED -> CLEANUP
        }
        processCleanup();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------ creation

    /** Request for a new match. {@code virtualPerTeam} engine-only players are added to each team (test matches). */
    public record MatchRequest(String modeId, String mapId, List<Player> teamA, List<Player> teamB, int virtualPerTeam, boolean test) {
    }

    private final List<MatchLifecycleHook> hooks = new ArrayList<>();

    /** Registers a lifecycle participant (arenas, later voice/HUD). */
    public void addHook(MatchLifecycleHook hook) {
        hooks.add(hook);
    }

    /** Validates and creates a match. Completes on the main thread. */
    public CompletableFuture<GameMatch> create(MatchRequest req, CommandSender requestedBy) {
        tasks.requireMainThread("MatchService#create");
        ModeRules rules = config.mode(req.modeId()).orElse(null);
        if (rules == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown mode '" + req.modeId() + "'"));
        if (!req.test() && rules.teamSize() * 2 != req.teamA().size() + req.teamB().size()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ranked matches need exactly "
                    + rules.teamSize() * 2 + " real players"));
        }
        for (MatchLifecycleHook h : hooks) {
            Optional<String> problem = h.validate(req);
            if (problem.isPresent()) return CompletableFuture.failedFuture(new IllegalArgumentException(problem.get()));
        }
        List<String> kitPool = config.enabledKitIds();
        for (MatchLifecycleHook h : hooks) kitPool = h.kits(req, kitPool);
        if (kitPool.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("No enabled kits for this match"));
        final List<String> kits = kitPool;
        int sizeA = req.teamA().size() + req.virtualPerTeam();
        int sizeB = req.teamB().size() + req.virtualPerTeam();
        if (sizeA > rules.teamSize() || sizeB > rules.teamSize()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A team would have more than "
                    + rules.teamSize() + " players"));
        }
        Set<UUID> realIds = new HashSet<>();
        for (Player p : concat(req.teamA(), req.teamB())) {
            if (!realIds.add(p.getUniqueId())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(p.getName() + " is listed twice"));
            }
            if (membership.containsKey(p.getUniqueId()) || reserved.contains(p.getUniqueId())) {
                return CompletableFuture.failedFuture(new IllegalStateException(p.getName() + " is already in a match"));
            }
        }
        reserved.addAll(realIds); // prevents double-booking while the id is allocated asynchronously

        return allocateId(req.test()).thenApplyAsync(id -> {
            reserved.removeAll(realIds);
            List<MatchMember> members = new ArrayList<>();
            for (Player p : req.teamA()) members.add(new MatchMember(p.getUniqueId(), p.getName(), TeamId.A, false));
            for (Player p : req.teamB()) members.add(new MatchMember(p.getUniqueId(), p.getName(), TeamId.B, false));
            for (TeamId t : TeamId.values()) {
                for (int i = 1; i <= req.virtualPerTeam(); i++) {
                    UUID vid = UUID.nameUUIDFromBytes((id.value() + "/virtual/" + t + i).getBytes());
                    members.add(new MatchMember(vid, "Bot-" + t + i, t, true));
                }
            }
            // A player may have joined another match or left while the id was being allocated.
            for (MatchMember m : members) {
                if (!m.virtual() && (membership.containsKey(m.uuid()) || Bukkit.getPlayer(m.uuid()) == null)) {
                    throw new IllegalStateException(m.name() + " is no longer available");
                }
            }
            GameMatch match = new GameMatch(id, rules, req.test(), members, kits, recorders::open, log, this::onFlowEvent);
            matches.put(id, match);
            for (MatchMember m : members) {
                if (m.virtual()) continue;
                membership.put(m.uuid(), id);
                Player p = Bukkit.getPlayer(m.uuid());
                if (p != null) players.session(p).enterMatch(id, SessionContext.MATCH);
            }
            recordHeader(match, kits, req);
            persistStart(match, req.mapId());
            audit.record(requestedBy, "MATCH_CREATE", id.value(), "mode=" + rules.id() + " test=" + req.test()
                    + " virtualPerTeam=" + req.virtualPerTeam());
            match.flow().beginLoading();
            CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);
            for (MatchLifecycleHook h : hooks) ready = ready.thenCompose(v -> h.prepare(match, req));
            ready.whenCompleteAsync((v, t) -> {
                if (t != null) {
                    match.fail("match preparation failed", t.getCause() != null ? t.getCause() : t);
                } else if (match.state() == MatchState.LOADING) {
                    match.flow().loaded();
                }
            }, tasks.main());
            return match;
        }, tasks.main()).whenComplete((m, t) -> {
            if (t != null) tasks.main().execute(() -> reserved.removeAll(realIds));
        });
    }

    /** Next id from the per-day sequence; skips any id whose recording file already exists (never overwrite). */
    private CompletableFuture<MatchId> allocateId(boolean test) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        return db.transaction(c -> {
            while (true) {
                long seq;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO match_seq (day, seq) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET seq = seq + 1 RETURNING seq")) {
                    ps.setString(1, day.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        seq = rs.getLong(1);
                    }
                }
                MatchId id = MatchId.of(day, seq, test);
                if (!recorders.exists(id)) return id;
                log.warning("Recording for " + id + " already exists (database reset?); skipping to the next id");
            }
        });
    }

    private void recordHeader(GameMatch match, List<String> kitPool, MatchRequest req) {
        ModeRules r = match.rules();
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("team_size", r.teamSize());
        rules.put("rounds_to_win", r.roundsToWin());
        rules.put("halftime_after_round", r.halftimeAfterRound());
        rules.put("sudden_death_at", r.suddenDeathAt());
        rules.put("sudden_death_sides", r.suddenDeathSides());
        rules.put("timers_ticks", Map.of("kit_select", r.kitSelectTicks(), "buy", r.buyPhaseTicks(), "round", r.roundTicks(),
                "bomb", r.bombTicks(), "plant", r.plantTicks(), "defuse", r.defuseTicks(),
                "defuse_checkpoint", r.defuseCheckpointTicks(), "halftime", r.halftimeTicks()));
        List<Map<String, Object>> teams = new ArrayList<>();
        for (MatchMember m : match.members()) {
            teams.add(Map.of("uuid", m.uuid(), "name", m.name(), "original_team", m.team(), "virtual", m.virtual()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema", "tactical-recording");
        data.put("mode", r.id());
        data.put("test", match.isTest());
        data.put("ranked", r.ranked() && !match.isTest());
        data.put("map", null);
        data.put("map_version", null);
        for (MatchLifecycleHook h : hooks) data.putAll(h.header(req));
        data.put("rules", rules);
        data.put("kit_pool", kitPool);
        data.put("participants", teams);
        data.put("plugin_version", plugin.getPluginMeta().getVersion());
        data.put("server_version", Bukkit.getVersion());
        data.put("position_interval_ticks", config.general().positionIntervalTicks());
        match.recorder().record(EventTypes.MATCH_HEADER, List.of(), null, data, "OK", RecordingSink.Priority.CRITICAL);
    }

    // ------------------------------------------------------------------ ticking

    private void tickAll() {
        serverTicks++;
        for (GameMatch m : List.copyOf(matches.values())) {
            m.tick();
        }
        if (serverTicks % 20 == 0) {
            long now = System.currentTimeMillis();
            for (GameMatch m : matches.values()) {
                for (UUID gone : m.expireRejoinWindows(now)) {
                    membership.remove(gone, m.id());
                    m.recorder().record(EventTypes.PLAYER_LEAVE, List.of(gone), null, Map.of("reason", "rejoin_window_expired"));
                }
            }
        }
        processCleanup();
    }

    private void onFlowEvent(GameMatch match, MatchFlowEvent event) {
        try {
            announcer.announce(match, event);
        } catch (Throwable t) {
            match.reportError("announcement failed", t);
        }
        if (GameMatch.entersCleanup(event)) {
            pendingCleanup.add(match); // never clean up re-entrantly from inside the engine
        }
    }

    private void processCleanup() {
        while (!pendingCleanup.isEmpty()) {
            GameMatch m = pendingCleanup.removeFirst();
            try {
                cleanup(m);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "[match=" + m.id() + "] cleanup failed", t);
                matches.remove(m.id());
                membership.values().removeIf(id -> id.equals(m.id()));
            }
        }
    }

    private void cleanup(GameMatch m) {
        for (MatchLifecycleHook h : hooks) {
            try {
                h.teardown(m);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "[match=" + m.id() + "] teardown hook failed", t);
            }
        }
        persistEnd(m);
        for (MatchMember mm : m.members()) {
            if (mm.virtual()) continue;
            membership.remove(mm.uuid(), m.id());
            players.session(mm.uuid()).ifPresent(s -> {
                if (s.matchId().filter(m.id()::equals).isPresent()) s.returnToLobby();
            });
        }
        String reason = m.flow().endReason().map(Enum::name).orElse("UNKNOWN");
        m.recorder().close(reason);
        m.bus().clear();
        m.flow().markClosed();
        matches.remove(m.id());
        log.info("[match=" + m.id() + "] cleaned up (" + reason + ")");
    }

    private void persistStart(GameMatch m, String mapId) {
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO matches (match_id, mode, started_at, is_test, map_id) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, m.id().value());
                ps.setString(2, m.rules().id());
                ps.setLong(3, m.createdAt());
                ps.setInt(4, m.isTest() ? 1 : 0);
                ps.setString(5, mapId);
                return ps.executeUpdate();
            }
        });
    }

    private void persistEnd(GameMatch m) {
        String id = m.id().value();
        int a = m.flow().scoreOf(TeamId.A);
        int b = m.flow().scoreOf(TeamId.B);
        String winner = m.flow().winner().map(Enum::name).orElse(null);
        String reason = m.flow().endReason().map(Enum::name).orElse("UNKNOWN");
        List<Object[]> rows = new ArrayList<>();
        for (MatchMember mm : m.members()) {
            Participant p = m.flow().participant(mm.uuid()).orElseThrow();
            rows.add(new Object[]{mm.uuid().toString(), mm.name(), mm.team().name(), mm.virtual() ? 1 : 0,
                    p.kitFirstHalf(), p.kitSecondHalf(), p.kitSuddenDeath()});
        }
        long now = System.currentTimeMillis();
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE matches SET ended_at = ?, score_a = ?, score_b = ?, winner = ?, end_reason = ? WHERE match_id = ?")) {
                ps.setLong(1, now);
                ps.setInt(2, a);
                ps.setInt(3, b);
                ps.setString(4, winner);
                ps.setString(5, reason);
                ps.setString(6, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO match_players (match_id, uuid, name, original_team, virtual,
                        kit_first_half, kit_second_half, kit_sudden_death) VALUES (?,?,?,?,?,?,?,?)""")) {
                for (Object[] r : rows) {
                    ps.setString(1, id);
                    for (int i = 0; i < r.length; i++) ps.setObject(i + 2, r[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ connections

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        GameMatch m = matchOf(id).orElse(null);
        if (m == null || m.flow().isFinished()) return;
        m.flow().playerDisconnected(id);
        long window = TimeUnit.SECONDS.toMillis(config.general().rejoinWindowSeconds());
        m.startRejoinWindow(id, System.currentTimeMillis() + window);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        GameMatch m = matchOf(p.getUniqueId()).orElse(null);
        if (m == null || m.flow().isFinished() || m.rejoinDeadline(p.getUniqueId()).isEmpty()) {
            if (m != null) membership.remove(p.getUniqueId());
            // Logged out inside a (now deleted or foreign) match world: never leave them stranded there.
            if (dev.tactical.paper.map.MapWorlds.isInstance(p.getWorld())) lobby.sendToLobby(p);
            return;
        }
        m.cancelRejoinWindow(p.getUniqueId());
        m.flow().playerReconnected(p.getUniqueId());
        players.session(p).enterMatch(m.id(), SessionContext.MATCH);
    }

    // ------------------------------------------------------------------ admin operations (callers check permissions)

    public boolean pause(GameMatch m, CommandSender by, String reason) {
        UUID uuid = by instanceof Player p ? p.getUniqueId() : null;
        boolean ok = m.flow().pause(uuid, reason);
        if (ok) adminAction(m, by, "PAUSE", reason);
        return ok;
    }

    public boolean resume(GameMatch m, CommandSender by) {
        UUID uuid = by instanceof Player p ? p.getUniqueId() : null;
        int countdown = config.general().resumeCountdownSeconds() * ModeRules.TICKS_PER_SECOND;
        boolean ok = m.flow().resume(uuid, countdown);
        if (ok) adminAction(m, by, "RESUME", "countdown=" + countdown);
        return ok;
    }

    public void end(GameMatch m, CommandSender by, String reason) {
        adminAction(m, by, "END_MATCH", reason);
        m.flow().abort(MatchEndReason.ADMIN_ENDED, reason);
    }

    public void adminAction(GameMatch m, CommandSender by, String action, String details) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("actor_name", by.getName());
        data.put("details", details == null ? "" : details);
        List<UUID> actors = by instanceof Player p ? List.of(p.getUniqueId()) : List.of();
        m.recorder().record(EventTypes.ADMIN_ACTION, actors, null, data, "OK", RecordingSink.Priority.CRITICAL);
        audit.record(by, action, m.id().value(), details);
    }

    // ------------------------------------------------------------------ queries

    public Optional<GameMatch> match(MatchId id) {
        return Optional.ofNullable(matches.get(id));
    }

    public Optional<GameMatch> match(String id) {
        return MatchId.isValid(id) ? match(new MatchId(id)) : Optional.empty();
    }

    /** The match this player belongs to (including while disconnected within the rejoin window). */
    public Optional<GameMatch> matchOf(UUID player) {
        MatchId id = membership.get(player);
        return id == null ? Optional.empty() : Optional.ofNullable(matches.get(id));
    }

    public Collection<GameMatch> matches() {
        return Collections.unmodifiableCollection(matches.values());
    }

    public boolean isActive(GameMatch m) {
        return m.state() != MatchState.CLOSED && matches.containsKey(m.id());
    }

    private static List<Player> concat(List<Player> a, List<Player> b) {
        List<Player> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
