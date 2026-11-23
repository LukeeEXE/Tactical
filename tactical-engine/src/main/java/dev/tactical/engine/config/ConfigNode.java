package dev.tactical.engine.config;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only view over a YAML node that remembers its file, line and key path, and reports every problem
 * into a {@link ConfigReport} instead of throwing. Getters return the supplied default after reporting,
 * so parsing continues and all issues are collected in one pass.
 */
public final class ConfigNode {

    private final String file;
    private final String path;
    private final Node node;
    private final ConfigReport report;

    ConfigNode(String file, String path, Node node, ConfigReport report) {
        this.file = file;
        this.path = path;
        this.node = node;
        this.report = report;
    }

    public String file() {
        return file;
    }

    public String path() {
        return path;
    }

    public ConfigReport report() {
        return report;
    }

    /** 1-based line of this node, or 0 if missing. */
    public int line() {
        return node == null ? 0 : node.getStartMark().getLine() + 1;
    }

    public boolean isMissing() {
        return node == null;
    }

    public boolean isMap() {
        return node instanceof MappingNode;
    }

    public boolean isList() {
        return node instanceof SequenceNode;
    }

    public boolean has(String key) {
        return rawChild(key) != null;
    }

    public ConfigNode child(String key) {
        return new ConfigNode(file, join(key), rawChild(key), report);
    }

    /** Ordered keys of a mapping (empty for non-maps). */
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        if (node instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                if (t.getKeyNode() instanceof ScalarNode s) keys.add(s.getValue());
            }
        }
        return keys;
    }

    /** Children of a mapping, keyed by name (insertion order kept). */
    public Map<String, ConfigNode> children() {
        Map<String, ConfigNode> out = new LinkedHashMap<>();
        for (String k : keys()) out.put(k, child(k));
        return out;
    }

    /** Elements of a list. */
    public List<ConfigNode> list() {
        List<ConfigNode> out = new ArrayList<>();
        if (node instanceof SequenceNode s) {
            int i = 0;
            for (Node n : s.getValue()) out.add(new ConfigNode(file, path + "[" + (i++) + "]", n, report));
        } else if (node != null) {
            error("must be a list");
        }
        return out;
    }

    /** Warns about keys that are not in {@code allowed} (catches typos like {@code rounds-to-wn}). */
    public void warnUnknownKeys(Collection<String> allowed) {
        Set<String> set = Set.copyOf(allowed);
        if (node instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                if (t.getKeyNode() instanceof ScalarNode s && !set.contains(s.getValue())) {
                    report.warning(file, t.getKeyNode().getStartMark().getLine() + 1, join(s.getValue()),
                            "unknown key (ignored). Allowed: " + String.join(", ", allowed));
                }
            }
        }
    }

    public Optional<String> scalar() {
        return node instanceof ScalarNode s ? Optional.of(s.getValue()) : Optional.empty();
    }

    public String string(String key, String def) {
        ConfigNode c = child(key);
        if (c.isMissing()) return def;
        return c.scalar().orElseGet(() -> {
            c.error("must be a text value");
            return def;
        });
    }

    public String requireString(String key) {
        ConfigNode c = child(key);
        if (c.isMissing()) {
            error("missing required key '" + key + "'");
            return null;
        }
        String v = c.scalar().orElse(null);
        if (v == null || v.isBlank()) {
            c.error("must be a non-empty text value");
            return null;
        }
        return v;
    }

    public int integer(String key, int def, int min, int max) {
        ConfigNode c = child(key);
        if (c.isMissing()) return def;
        try {
            int v = Integer.parseInt(c.scalar().orElseThrow(NumberFormatException::new).trim());
            if (v < min || v > max) {
                c.error("must be between " + min + " and " + max + " (was " + v + ")");
                return def;
            }
            return v;
        } catch (NumberFormatException e) {
            c.error("must be a whole number");
            return def;
        }
    }

    public double number(String key, double def, double min, double max) {
        ConfigNode c = child(key);
        if (c.isMissing()) return def;
        try {
            double v = Double.parseDouble(c.scalar().orElseThrow(NumberFormatException::new).trim());
            if (Double.isNaN(v) || v < min || v > max) {
                c.error("must be between " + min + " and " + max + " (was " + v + ")");
                return def;
            }
            return v;
        } catch (NumberFormatException e) {
            c.error("must be a number");
            return def;
        }
    }

    public boolean bool(String key, boolean def) {
        ConfigNode c = child(key);
        if (c.isMissing()) return def;
        String v = c.scalar().orElse("").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> {
                c.error("must be true or false");
                yield def;
            }
        };
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        ConfigNode c = child(key);
        if (c.isMissing()) return def;
        String v = c.scalar().orElse("").trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(v)) return e;
        }
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) names.add(e.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        c.error("must be one of: " + String.join(", ", names));
        return def;
    }

    public List<String> stringList(String key) {
        ConfigNode c = child(key);
        if (c.isMissing()) return List.of();
        List<String> out = new ArrayList<>();
        for (ConfigNode e : c.list()) {
            e.scalar().ifPresentOrElse(out::add, () -> e.error("must be a text value"));
        }
        return out;
    }

    public void error(String message) {
        report.error(file, line(), path, message);
    }

    public void warning(String message) {
        report.warning(file, line(), path, message);
    }

    private Node rawChild(String key) {
        if (node instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                if (t.getKeyNode() instanceof ScalarNode s && s.getValue().equals(key)) return t.getValueNode();
            }
        }
        return null;
    }

    private String join(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
