package dev.tactical.engine.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Parses YAML into {@link ConfigNode}s with line information. Syntax errors become report entries, never exceptions. */
public final class YamlLoader {

    /** Upper bound on aliases to defeat "billion laughs" style files. */
    private static final int MAX_ALIASES = 50;

    private YamlLoader() {
    }

    public static Optional<ConfigNode> load(Path path, String displayName, ConfigReport report) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return compose(reader, displayName, report);
        } catch (IOException e) {
            report.error(displayName, 0, "", "cannot read file: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<ConfigNode> parse(String text, String displayName, ConfigReport report) {
        return compose(new StringReader(text), displayName, report);
    }

    private static Optional<ConfigNode> compose(Reader reader, String name, ConfigReport report) {
        try {
            Node root = newYaml().compose(reader);
            if (root == null) {
                report.error(name, 0, "", "file is empty");
                return Optional.empty();
            }
            int before = report.errors().size();
            checkDuplicateKeys(root, name, "", report);
            if (report.errors().size() > before) {
                return Optional.empty();
            }
            ConfigNode node = new ConfigNode(name, "", root, report);
            if (!node.isMap()) {
                report.error(name, node.line(), "", "top level must be a mapping (key: value)");
                return Optional.empty();
            }
            return Optional.of(node);
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : 0;
            report.error(name, line, "", "YAML syntax error: " + e.getProblem());
            return Optional.empty();
        } catch (YAMLException e) {
            report.error(name, 0, "", "YAML error: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** compose() does not enforce duplicate keys, so we do: a silently overridden key is a classic YAML trap. */
    private static void checkDuplicateKeys(Node node, String file, String path, ConfigReport report) {
        if (node instanceof org.yaml.snakeyaml.nodes.MappingNode m) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (org.yaml.snakeyaml.nodes.NodeTuple t : m.getValue()) {
                String key = t.getKeyNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode s ? s.getValue() : "?";
                String child = path.isEmpty() ? key : path + "." + key;
                if (!seen.add(key)) {
                    report.error(file, t.getKeyNode().getStartMark().getLine() + 1, child, "duplicate key");
                }
                checkDuplicateKeys(t.getValueNode(), file, child, report);
            }
        } else if (node instanceof org.yaml.snakeyaml.nodes.SequenceNode s) {
            int i = 0;
            for (Node n : s.getValue()) checkDuplicateKeys(n, file, path + "[" + (i++) + "]", report);
        }
    }

    /** Plain map view for migrations. */
    public static Map<String, Object> loadMap(String text) {
        Object o = newYaml().load(text);
        if (!(o instanceof Map<?, ?> m)) throw new YAMLException("top level must be a mapping");
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) m;
        return map;
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setAllowDuplicateKeys(false);
        return new Yaml(options);
    }
}
