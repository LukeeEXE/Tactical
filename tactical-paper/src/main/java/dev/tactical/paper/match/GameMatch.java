package dev.tactical.paper.match;

import dev.tactical.engine.event.EventBus;
import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.match.MatchEndReason;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.record.FlowEventRecorder;
import dev.tactical.engine.record.MatchRecorder;
import dev.tactical.engine.record.RecordingSink;
import dev.tactical.engine.state.MatchState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One running match on this server: the engine {@link MatchFlow}, its recording, its event bus and its members.
 * Everything match-specific hangs off this object and is keyed by its {@link MatchId}, which is what keeps
 * simultaneous matches isolated. Main thread only.
 */
public final class GameMatch {

    /** How each player was last eliminated ({@link dev.tactical.paper.arena.KillMethods} id), for kill messages. */
    private final java.util.Map<java.util.UUID, String> killMethods = new java.util.concurrent.ConcurrentHashMap<>();

    public void setKillMethod(java.util.UUID victim, String method) {
        killMethods.put(victim, method);
    }

    /** Consumes the stored method (so a later elimination without one does not reuse it). */
    public java.util.Optional<String> takeKillMethod(java.util.UUID victim) {
        return java.util.Optional.ofNullable(killMethods.remove(victim));
    }

    private final MatchId id;
    private final ModeRules rules;
    private final boolean test;
    private final long createdAt = System.currentTimeMillis();
    private final Map<UUID, MatchMember> members;
    private final MatchFlow flow;
    private final MatchRecorder recorder;
    private final EventBus bus;
    private final Logger log;
    private final Map<UUID, Long> rejoinDeadlines = new LinkedHashMap<>();
    private boolean failed;

    GameMatch(MatchId id, ModeRules rules, boolean test, List<MatchMember> members, List<String> kitPool,
              RecorderFactory recorders, Logger log, BiConsumer<GameMatch, MatchFlowEvent> onEvent) {
        this.id = id;
        this.rules = rules;
        this.test = test;
        this.log = log;
        Map<UUID, MatchMember> m = new LinkedHashMap<>();
        members.forEach(mm -> m.put(mm.uuid(), mm));
        this.members = Collections.unmodifiableMap(m);
        this.bus = new EventBus((event, t) -> reportError("event handler failed for " + event.getClass().getSimpleName(), t));
        MatchFlow[] holder = new MatchFlow[1];
        this.recorder = recorders.open(id, () -> holder[0] == null ? 0 : holder[0].currentTick(),
                () -> holder[0] == null ? 0 : holder[0].round());
        FlowEventRecorder bridge = new FlowEventRecorder(recorder);
        List<Participant> participants = new ArrayList<>();
        for (MatchMember mm : members) participants.add(new Participant(mm.uuid(), mm.name(), mm.team()));
        this.flow = new MatchFlow(id, rules, participants, kitPool, new SplittableRandom(), event -> {
            bridge.accept(event);
            bus.publish(event);
            onEvent.accept(this, event);
        });
        holder[0] = flow;
    }

    @FunctionalInterface
    interface RecorderFactory {
        MatchRecorder open(MatchId id, java.util.function.LongSupplier tick, java.util.function.IntSupplier round);
    }

    // ------------------------------------------------------------------ ticking & failure isolation

    /** Ticks the engine. Any exception aborts THIS match only (logged with ids); other matches keep running. */
    void tick() {
        if (failed) {
            // A failed match must still reach CLEANUP so its resources are released and its recording finalised.
            try {
                if (flow.state() == MatchState.ABORTED) flow.tick();
            } catch (Throwable t) {
                log.log(Level.SEVERE, "[match=" + id + "] could not advance failed match to cleanup", t);
            }
            return;
        }
        try {
            flow.tick();
        } catch (Throwable t) {
            fail("tick failed", t);
        }
    }

    void fail(String what, Throwable t) {
        if (failed) return;
        failed = true;
        reportError(what, t);
        try {
            flow.abort(MatchEndReason.ERROR, what + ": " + t);
        } catch (Throwable t2) {
            log.log(Level.SEVERE, "[match=" + id + "] could not abort cleanly", t2);
        }
    }

    /** Logs to console AND to the recording, always including the match id and the last event id. */
    public void reportError(String message, Throwable t) {
        String eventId = recorder.lastEventId();
        log.log(Level.SEVERE, "[match=" + id + " event=" + eventId + "] " + message, t);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        data.put("state", flow == null ? "?" : flow.state().name());
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            data.put("exception", t.toString());
            data.put("stacktrace", sw.toString());
        }
        recorder.record(EventTypes.ERROR, List.of(), null, data, "FAILED", RecordingSink.Priority.CRITICAL);
    }

    // ------------------------------------------------------------------ rejoin window

    void startRejoinWindow(UUID player, long deadlineMillis) {
        rejoinDeadlines.put(player, deadlineMillis);
    }

    void cancelRejoinWindow(UUID player) {
        rejoinDeadlines.remove(player);
    }

    /** Returns players whose rejoin window just expired (and forgets them). */
    List<UUID> expireRejoinWindows(long now) {
        List<UUID> expired = new ArrayList<>();
        rejoinDeadlines.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                expired.add(e.getKey());
                return true;
            }
            return false;
        });
        return expired;
    }

    public Optional<Long> rejoinDeadline(UUID player) {
        return Optional.ofNullable(rejoinDeadlines.get(player));
    }

    // ------------------------------------------------------------------ queries

    public MatchId id() {
        return id;
    }

    public ModeRules rules() {
        return rules;
    }

    public boolean isTest() {
        return test;
    }

    public long createdAt() {
        return createdAt;
    }

    public MatchFlow flow() {
        return flow;
    }

    public MatchState state() {
        return flow.state();
    }

    public EventBus bus() {
        return bus;
    }

    public MatchRecorder recorder() {
        return recorder;
    }

    public Collection<MatchMember> members() {
        return members.values();
    }

    public Optional<MatchMember> member(UUID uuid) {
        return Optional.ofNullable(members.get(uuid));
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    /** Online, real (non-virtual) members. */
    public List<Player> onlinePlayers() {
        List<Player> out = new ArrayList<>();
        for (MatchMember m : members.values()) {
            if (m.virtual()) continue;
            Player p = Bukkit.getPlayer(m.uuid());
            if (p != null) out.add(p);
        }
        return out;
    }

    public long realMemberCount() {
        return members.values().stream().filter(m -> !m.virtual()).count();
    }

    public boolean hasFailed() {
        return failed;
    }

    /** Whether the given event is the one that moves this match into CLEANUP. */
    static boolean entersCleanup(MatchFlowEvent e) {
        return e instanceof MatchFlowEvent.StateChanged sc && sc.to() == MatchState.CLEANUP;
    }
}
