package dev.tactical.engine.map;

import dev.tactical.engine.config.ConfigNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A map pool ({@code map-pools/<id>.yml}) for one mode. The same map may appear in several pools with
 * different enabled states.
 *
 * @param maps map id → enabled in THIS pool
 */
public record MapPool(String id, String mode, boolean enabled, Map<String, Boolean> maps) {

    public static final int SCHEMA_VERSION = 1;

    public MapPool {
        maps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(maps));
    }

    public MapPool withMap(String mapId, boolean on) {
        Map<String, Boolean> m = new LinkedHashMap<>(maps);
        m.put(mapId, on);
        return new MapPool(id, mode, enabled, m);
    }

    public MapPool withoutMap(String mapId) {
        Map<String, Boolean> m = new LinkedHashMap<>(maps);
        m.remove(mapId);
        return new MapPool(id, mode, enabled, m);
    }

    public static Optional<MapPool> parse(ConfigNode root, String expectedId) {
        int before = root.report().errors().size();
        root.warnUnknownKeys(List.of("schema-version", "id", "mode", "enabled", "maps"));
        String id = root.string("id", expectedId);
        if (!id.equals(expectedId)) root.child("id").error("must match the file name '" + expectedId + "'");
        String mode = root.requireString("mode");
        boolean enabled = root.bool("enabled", true);
        Map<String, Boolean> maps = new LinkedHashMap<>();
        ConfigNode m = root.child("maps");
        for (String key : m.keys()) maps.put(key, m.bool(key, true));
        if (root.report().errors().size() > before) return Optional.empty();
        return Optional.of(new MapPool(id, mode, enabled, maps));
    }

    public Map<String, Object> toYaml() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schema-version", SCHEMA_VERSION);
        o.put("id", id);
        o.put("mode", mode);
        o.put("enabled", enabled);
        o.put("maps", new LinkedHashMap<>(maps));
        return o;
    }
}
