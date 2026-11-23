package dev.tactical.paper.config;

import dev.tactical.engine.config.ConfigNode;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable kit definition. PHASE 1 PLACEHOLDER: only identity fields. Equipment, abilities, shop,
 * icon and map restrictions are added in Phase 3 (unknown keys are therefore not warned about yet).
 */
public record KitDefinition(String id, String name, String description, boolean enabled) {

    public static final int SCHEMA_VERSION = 1;
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    public static Optional<KitDefinition> parse(ConfigNode root, String expectedId) {
        int before = root.report().errors().size();
        String id = root.string("id", expectedId);
        if (!id.equals(expectedId)) root.child("id").error("must match the file name '" + expectedId + "'");
        if (!ID.matcher(id).matches()) root.child("id").error("must be 1-32 chars of a-z, 0-9, _");
        String name = root.string("name", id);
        String description = root.string("description", "");
        boolean enabled = root.bool("enabled", true);
        if (root.report().errors().size() > before) return Optional.empty();
        return Optional.of(new KitDefinition(id, name, description, enabled));
    }
}
