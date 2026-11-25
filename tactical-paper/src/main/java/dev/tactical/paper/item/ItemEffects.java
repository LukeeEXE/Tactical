package dev.tactical.paper.item;

import dev.tactical.engine.config.ConfigNode;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of effect types usable in item YAML ({@code effects: [ { type: potion, ... } ]}). */
public final class ItemEffects {

    /** Reads one effect entry; reports problems on {@code node} and returns empty if it is invalid. */
    @FunctionalInterface
    public interface Parser {
        Optional<ItemEffect> parse(ConfigNode node);
    }

    private static final Map<String, Parser> TYPES = new ConcurrentHashMap<>();

    static {
        register("potion", PotionItemEffect::parse);
    }

    private ItemEffects() {
    }

    /** Adds a new effect type (custom effects). Items reload to pick it up. */
    public static void register(String type, Parser parser) {
        TYPES.put(type.toLowerCase(Locale.ROOT), parser);
    }

    public static Set<String> types() {
        return new TreeSet<>(TYPES.keySet());
    }

    public static Optional<ItemEffect> parse(ConfigNode node) {
        String type = node.requireString("type");
        if (type == null) return Optional.empty();
        Parser p = TYPES.get(type.toLowerCase(Locale.ROOT));
        if (p == null) {
            node.child("type").error("unknown effect type '" + type + "' (known: " + String.join(", ", types()) + ")");
            return Optional.empty();
        }
        return p.parse(node);
    }
}
