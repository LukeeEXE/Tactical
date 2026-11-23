package dev.tactical.engine.config;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a chain of {@link ConfigMigration}s to bring a file to {@link #currentVersion()}.
 * The caller backs up the original file BEFORE writing the migrated result, so nothing is ever lost.
 */
public final class ConfigMigrator {

    public static final String VERSION_KEY = "schema-version";

    private final String kind;
    private final int currentVersion;
    private final List<ConfigMigration> migrations;

    public ConfigMigrator(String kind, int currentVersion, List<ConfigMigration> migrations) {
        this.kind = Objects.requireNonNull(kind);
        this.currentVersion = currentVersion;
        this.migrations = migrations.stream().sorted(Comparator.comparingInt(ConfigMigration::fromVersion)).toList();
        for (int v = 1; v < currentVersion; v++) {
            final int from = v;
            if (this.migrations.stream().noneMatch(m -> m.fromVersion() == from)) {
                throw new IllegalArgumentException(kind + ": missing migration from version " + from);
            }
        }
    }

    public int currentVersion() {
        return currentVersion;
    }

    public enum Outcome { UP_TO_DATE, MIGRATED, TOO_NEW, INVALID_VERSION }

    public record Result(Outcome outcome, int fromVersion, String message) {}

    /** Mutates {@code root} in place. */
    public Result migrate(Map<String, Object> root) {
        Object raw = root.get(VERSION_KEY);
        int version;
        if (raw == null) {
            version = 1; // files written before versioning existed are treated as v1
        } else if (raw instanceof Number n) {
            version = n.intValue();
        } else {
            return new Result(Outcome.INVALID_VERSION, -1, kind + ": " + VERSION_KEY + " must be a whole number");
        }
        if (version > currentVersion) {
            return new Result(Outcome.TOO_NEW, version, kind + ": file is schema-version " + version
                    + " but this plugin only understands up to " + currentVersion + ". It was NOT loaded or changed.");
        }
        if (version < 1) {
            return new Result(Outcome.INVALID_VERSION, version, kind + ": " + VERSION_KEY + " must be >= 1");
        }
        if (version == currentVersion) {
            root.put(VERSION_KEY, currentVersion);
            return new Result(Outcome.UP_TO_DATE, version, "");
        }
        int from = version;
        for (ConfigMigration m : migrations) {
            if (m.fromVersion() >= version && m.fromVersion() < currentVersion) {
                m.migrate(root);
            }
        }
        root.put(VERSION_KEY, currentVersion);
        return new Result(Outcome.MIGRATED, from, kind + ": migrated schema-version " + from + " → " + currentVersion);
    }
}
