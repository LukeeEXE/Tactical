package dev.tactical.paper.config;

import dev.tactical.engine.config.ConfigNode;

import java.util.List;

/** Immutable snapshot of config.yml. */
public record GeneralConfig(
        String storageType,
        String sqliteFile,
        String recordingFolder,
        int positionIntervalTicks,
        int recordingQueueSoftLimit,
        int recordingFlushIntervalMs,
        int rejoinWindowSeconds,
        int resumeCountdownSeconds,
        boolean logStateChanges
) {
    public static final int SCHEMA_VERSION = 1;

    public static GeneralConfig defaults() {
        return new GeneralConfig("sqlite", "data/tactical.db", "logs/matches", 4, 50_000, 1000, 180, 3, true);
    }

    public static GeneralConfig parse(ConfigNode root) {
        GeneralConfig d = defaults();
        root.warnUnknownKeys(List.of("schema-version", "storage", "recording", "matches", "debug"));
        ConfigNode storage = root.child("storage");
        storage.warnUnknownKeys(List.of("type", "sqlite-file"));
        String type = storage.string("type", d.storageType());
        if (!type.equals("sqlite")) {
            storage.child("type").error("only 'sqlite' is supported in this version (mysql/mariadb planned)");
            type = "sqlite";
        }
        ConfigNode rec = root.child("recording");
        rec.warnUnknownKeys(List.of("folder", "position-interval-ticks", "queue-soft-limit", "flush-interval-ms"));
        ConfigNode m = root.child("matches");
        m.warnUnknownKeys(List.of("rejoin-window-seconds", "resume-countdown-seconds"));
        ConfigNode dbg = root.child("debug");
        dbg.warnUnknownKeys(List.of("log-state-changes"));
        return new GeneralConfig(
                type,
                storage.string("sqlite-file", d.sqliteFile()),
                rec.string("folder", d.recordingFolder()),
                rec.integer("position-interval-ticks", d.positionIntervalTicks(), 1, 200),
                rec.integer("queue-soft-limit", d.recordingQueueSoftLimit(), 1000, 10_000_000),
                rec.integer("flush-interval-ms", d.recordingFlushIntervalMs(), 50, 60_000),
                m.integer("rejoin-window-seconds", d.rejoinWindowSeconds(), 0, 3600),
                m.integer("resume-countdown-seconds", d.resumeCountdownSeconds(), 0, 60),
                dbg.bool("log-state-changes", d.logStateChanges()));
    }
}
