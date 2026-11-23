package dev.tactical.engine.map;

import java.util.List;
import java.util.Set;

/** Which kits may be picked on a map. */
public record KitRestriction(Mode mode, Set<String> kits) {

    public enum Mode { ALL, WHITELIST, BLACKLIST }

    public static final KitRestriction ALL = new KitRestriction(Mode.ALL, Set.of());

    public List<String> filter(List<String> available) {
        return switch (mode) {
            case ALL -> available;
            case WHITELIST -> available.stream().filter(kits::contains).toList();
            case BLACKLIST -> available.stream().filter(k -> !kits.contains(k)).toList();
        };
    }
}
