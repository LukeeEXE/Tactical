package dev.tactical.engine.config;

import java.util.Map;

/**
 * Upgrades one file kind from {@link #fromVersion()} to {@code fromVersion() + 1}.
 * Implementations mutate the raw map (rename keys, convert units, add new required keys with defaults).
 */
public interface ConfigMigration {

    int fromVersion();

    void migrate(Map<String, Object> root);
}
