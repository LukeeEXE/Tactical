package dev.tactical.paper.record;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.record.JsonlFileWriter;
import dev.tactical.engine.record.MatchRecorder;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Owns the recording writer thread and answers /game logs queries (file scans run off the main thread). */
public final class RecorderService implements Service {

    private final ConfigService config;
    private final Logger log;
    private JsonlFileWriter writer;
    private ExecutorService reader;

    public RecorderService(ConfigService config, Logger log) {
        this.config = config;
        this.log = log;
    }

    @Override
    public void enable() throws IOException {
        Files.createDirectories(folder());
        writer = new JsonlFileWriter(config.general().recordingQueueSoftLimit(), config.general().recordingFlushIntervalMs(),
                (key, t) -> log.log(Level.SEVERE, "[match=" + key + "] recording write failed", t));
        reader = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("Tactical-LogReader").daemon(true).unstarted(r));
    }

    @Override
    public void disable() {
        if (writer != null) writer.close(); // drains and fsyncs everything
        if (reader != null) reader.shutdownNow();
    }

    public Path folder() {
        return config.root().resolve(config.general().recordingFolder());
    }

    public Path fileFor(MatchId id) {
        return folder().resolve(id.value() + ".jsonl");
    }

    public boolean exists(MatchId id) {
        return Files.exists(fileFor(id)) || Files.exists(folder().resolve(id.value() + ".jsonl.gz"));
    }

    public MatchRecorder open(MatchId id, LongSupplier tick, IntSupplier round) {
        return new MatchRecorder(id, writer.open(id.value(), fileFor(id)), System::currentTimeMillis, tick, round);
    }

    public long droppedLines() {
        return writer.droppedLines();
    }

    public int pendingLines() {
        return writer.pending();
    }

    public CompletableFuture<List<RecordingInfo>> recent(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (Stream<Path> files = Files.list(folder())) {
                List<Path> sorted = files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .sorted(Comparator.comparingLong(RecorderService::modified).reversed()).limit(limit).toList();
                List<RecordingInfo> out = new ArrayList<>();
                for (Path p : sorted) out.add(read(p));
                return out;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, reader);
    }

    public CompletableFuture<Optional<RecordingInfo>> info(String matchId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!MatchId.isValid(matchId)) return Optional.empty();
            Path p = folder().resolve(matchId + ".jsonl");
            return Files.exists(p) ? Optional.of(read(p)) : Optional.empty();
        }, reader);
    }

    /** ERROR events from the most recent recordings, newest first. */
    public CompletableFuture<List<String>> recentErrors(int files, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> out = new ArrayList<>();
            try (Stream<Path> s = Files.list(folder())) {
                for (Path p : s.filter(f -> f.toString().endsWith(".jsonl"))
                        .sorted(Comparator.comparingLong(RecorderService::modified).reversed()).limit(files).toList()) {
                    try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = r.readLine()) != null && out.size() < limit) {
                            if (!line.contains("\"type\":\"ERROR\"") && !line.contains("\"type\":\"VALIDATION_ERROR\"")) continue;
                            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                            JsonObject d = o.getAsJsonObject("data");
                            out.add(o.get("event_id").getAsString() + " " + o.get("ts").getAsString() + " "
                                    + (d.has("message") ? d.get("message").getAsString() : d.toString()));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                out.add("(could not read recordings: " + e.getMessage() + ")");
            }
            return out;
        }, reader);
    }

    private static RecordingInfo read(Path p) {
        String id = p.getFileName().toString().replace(".jsonl", "");
        long lines = 0, errors = 0;
        boolean complete = false;
        String mode = "?", started = "?", endReason = "-", score = "-";
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            String last = null;
            while ((line = r.readLine()) != null) {
                lines++;
                if (line.isBlank()) continue;
                last = line;
                if (line.contains("\"type\":\"ERROR\"")) errors++;
                if (lines == 1 || line.contains("\"type\":\"MATCH_HEADER\"")) {
                    try {
                        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                        if (o.get("type").getAsString().equals("MATCH_HEADER")) {
                            started = o.get("ts").getAsString();
                            mode = o.getAsJsonObject("data").get("mode").getAsString();
                        }
                    } catch (RuntimeException ignored) {
                        // incomplete/corrupt line: reported via 'complete'
                    }
                }
                if (line.contains("\"type\":\"MATCH_END\"")) {
                    try {
                        JsonObject d = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("data");
                        endReason = d.get("reason").getAsString();
                        score = d.get("score_a").getAsInt() + "-" + d.get("score_b").getAsInt();
                    } catch (RuntimeException ignored) {
                        // keep defaults
                    }
                }
            }
            if (last != null) {
                try {
                    JsonObject o = JsonParser.parseString(last).getAsJsonObject();
                    complete = o.get("type").getAsString().equals("MATCH_FOOTER");
                } catch (RuntimeException e) {
                    complete = false; // truncated final line (crash mid-write)
                }
            }
        } catch (IOException e) {
            return new RecordingInfo(id, p, 0, 0, false, "?", "?", "unreadable: " + e.getMessage(), "-", 0, false);
        }
        long size;
        try {
            size = Files.size(p);
        } catch (IOException e) {
            size = -1;
        }
        return new RecordingInfo(id, p, size, lines, complete, mode, started, endReason, score, errors, false);
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
