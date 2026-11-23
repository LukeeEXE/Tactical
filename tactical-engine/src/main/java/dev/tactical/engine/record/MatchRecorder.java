package dev.tactical.engine.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.team.TeamId;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Builds versioned JSONL recording lines for ONE match (schema in docs/recording-schema.md). Every line has:
 * {@code v, event_id, match_id, ts, elapsed_ms, tick, round, type, actors, team, data, result}.
 *
 * <p>Event ids are {@code <matchId>-E<6+ digit sequence>}, globally unique because match ids are.
 * Must be called from the main thread; serialization is cheap and the sink does the I/O off-thread.
 */
public final class MatchRecorder {

    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MatchId matchId;
    private final RecordingSink sink;
    private final LongSupplier clockMillis;
    private final LongSupplier tick;
    private final IntSupplier round;
    private final long startedAt;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong lowWritten = new AtomicLong();
    private volatile boolean closed;

    public MatchRecorder(MatchId matchId, RecordingSink sink, LongSupplier clockMillis, LongSupplier tick, IntSupplier round) {
        this.matchId = Objects.requireNonNull(matchId);
        this.sink = Objects.requireNonNull(sink);
        this.clockMillis = Objects.requireNonNull(clockMillis);
        this.tick = Objects.requireNonNull(tick);
        this.round = Objects.requireNonNull(round);
        this.startedAt = clockMillis.getAsLong();
    }

    public MatchId matchId() {
        return matchId;
    }

    public long eventCount() {
        return sequence.get();
    }

    /** The id the NEXT event will receive; include it in technical error messages for traceability. */
    public String lastEventId() {
        return eventId(sequence.get());
    }

    public String record(String type, Map<String, ?> data) {
        return record(type, List.of(), null, data, "OK", RecordingSink.Priority.NORMAL);
    }

    public String record(String type, Collection<UUID> actors, TeamId team, Map<String, ?> data) {
        return record(type, actors, team, data, "OK", RecordingSink.Priority.NORMAL);
    }

    /** @return the event id assigned, or null if the recording is already closed */
    public synchronized String record(String type, Collection<UUID> actors, TeamId team, Map<String, ?> data,
                                      String result, RecordingSink.Priority priority) {
        if (closed) return null;
        long seq = sequence.incrementAndGet();
        String id = eventId(seq);
        long now = clockMillis.getAsLong();
        JsonObject o = new JsonObject();
        o.addProperty("v", SCHEMA_VERSION);
        o.addProperty("event_id", id);
        o.addProperty("match_id", matchId.value());
        o.addProperty("ts", Instant.ofEpochMilli(now).toString());
        o.addProperty("elapsed_ms", now - startedAt);
        o.addProperty("tick", tick.getAsLong());
        o.addProperty("round", round.getAsInt());
        o.addProperty("type", type);
        JsonArray a = new JsonArray();
        for (UUID u : actors) a.add(u.toString());
        o.add("actors", a);
        if (team != null) o.addProperty("team", team.name());
        o.add("data", GSON.toJsonTree(data == null ? Map.of() : normalize(data)));
        o.addProperty("result", result);
        if (priority == RecordingSink.Priority.LOW) lowWritten.incrementAndGet();
        sink.write(GSON.toJson(o), priority);
        return id;
    }

    /** Writes the footer (completeness marker) and closes the sink. Idempotent. */
    public synchronized void close(String reason) {
        if (closed) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("events", sequence.get() + 1);
        data.put("low_priority_events", lowWritten.get());
        data.put("complete", true);
        record(EventTypes.MATCH_FOOTER, List.of(), null, data, "OK", RecordingSink.Priority.CRITICAL);
        closed = true;
        sink.close();
    }

    public boolean isClosed() {
        return closed;
    }

    private String eventId(long seq) {
        return matchId.value() + "-E" + String.format("%06d", seq);
    }

    /** Converts UUIDs/enums/records into JSON-friendly values without reflection surprises. */
    private static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof UUID u) return u.toString();
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(normalize(e.getKey())), normalize(e.getValue()));
            return out;
        }
        if (v instanceof Collection<?> c) return c.stream().map(MatchRecorder::normalize).toList();
        if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Record || v instanceof MatchId) return v.toString();
        return v.toString();
    }
}
