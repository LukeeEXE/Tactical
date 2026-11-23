package dev.tactical.engine.record;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.mode.SkipVoteRule;
import dev.tactical.engine.mode.SuddenDeathSides;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.TeamId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RecorderTest {

    @Test
    void fullMatchProducesCompleteConsistentRecording(@TempDir Path dir) throws Exception {
        MatchId id = MatchId.of(LocalDate.of(2026, 11, 23), 1, true);
        Path file = dir.resolve(id.value() + ".jsonl");
        List<Throwable> errors = new ArrayList<>();
        AtomicReference<MatchFlow> flowRef = new AtomicReference<>();
        try (JsonlFileWriter writer = new JsonlFileWriter(10_000, 50, (k, t) -> errors.add(t))) {
            MatchRecorder rec = new MatchRecorder(id, writer.open(id.value(), file), System::currentTimeMillis,
                    () -> flowRef.get() == null ? 0 : flowRef.get().currentTick(),
                    () -> flowRef.get() == null ? 0 : flowRef.get().round());
            FlowEventRecorder bridge = new FlowEventRecorder(rec);
            ModeRules rules = new ModeRules("t", "T", 5, 5, 4, 4, 2, 2, 4, 3, 1, 2, 1, 1, 2, 2, 1,
                    SuddenDeathSides.RANDOM, SkipVoteRule.MAJORITY, false);
            List<Participant> ps = List.of(new Participant(new UUID(1, 1), "a", TeamId.A),
                    new Participant(new UUID(2, 1), "b", TeamId.B));
            MatchFlow flow = new MatchFlow(id, rules, ps, List.of("k1"), new SplittableRandom(1), bridge::accept);
            flowRef.set(flow);
            rec.record(EventTypes.MATCH_HEADER, java.util.Map.of("mode", "t", "map", "none"));
            flow.beginLoading();
            flow.loaded();
            while (flow.state() != MatchState.CLEANUP) flow.tick();
            rec.close("COMPLETED");
        }
        assertTrue(errors.isEmpty(), errors.toString());
        List<String> lines = Files.readAllLines(file);
        Set<String> ids = new HashSet<>();
        long lastElapsed = -1;
        for (String l : lines) {
            JsonObject o = JsonParser.parseString(l).getAsJsonObject();
            assertEquals(1, o.get("v").getAsInt());
            assertEquals(id.value(), o.get("match_id").getAsString());
            assertTrue(ids.add(o.get("event_id").getAsString()), "event ids unique");
            long el = o.get("elapsed_ms").getAsLong();
            assertTrue(el >= lastElapsed, "time is monotonic");
            lastElapsed = el;
        }
        JsonObject last = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        assertEquals("MATCH_FOOTER", last.get("type").getAsString());
        assertTrue(last.getAsJsonObject("data").get("complete").getAsBoolean());
        assertEquals(9, lines.stream().filter(l -> l.contains("\"type\":\"ROUND_END\"")).count());
    }

    @Test
    void neverOverwritesAnExistingRecording(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("MATCH-20261123-000002.jsonl");
        Files.writeString(file, "precious\n");
        List<Throwable> errors = new ArrayList<>();
        try (JsonlFileWriter writer = new JsonlFileWriter(100, 50, (k, t) -> errors.add(t))) {
            RecordingSink sink = writer.open("k", file);
            sink.write("{}", RecordingSink.Priority.CRITICAL);
            sink.close();
        }
        assertEquals("precious\n", Files.readString(file));
        assertEquals(1, errors.size(), "the collision is reported");
    }
}
