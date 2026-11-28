package dev.tactical.paper.config;

import dev.tactical.engine.config.ConfigIssue;
import dev.tactical.engine.config.ConfigMigrator;
import dev.tactical.engine.config.ConfigNode;
import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.ModeRulesParser;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.paper.core.Service;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads, validates, migrates and hot-reloads configuration files.
 *
 * <ul>
 *   <li>Bundled defaults are copied ONLY if the target file does not exist; admin edits are never overwritten.</li>
 *   <li>Each file is validated independently. An invalid file is rejected and the previously loaded version
 *       (if any) stays active. Every issue is logged with file:line and key path.</li>
 *   <li>Old {@code schema-version}s are migrated after backing the original up to {@code backups/config/<ts>/}.</li>
 *   <li>Registries are immutable snapshots swapped atomically; matches keep the snapshot they started with.</li>
 * </ul>
 */
public final class ConfigService implements Service {

    /** Every bundled default file (relative to the data folder). */
    static final List<String> DEFAULT_FILES = List.of(
            "config.yml",
            "modes/quickplay.yml", "modes/competitive.yml", "modes/test.yml",
            "kits/bruiser.yml", "kits/tactician.yml", "kits/initiator.yml", "kits/marksman.yml",
            "kits/recon.yml", "kits/medic.yml", "kits/specialist.yml",
            "messages/en_US.yml", "items/shots.yml", "items/healing.yml", "items/utility.yml", "items/throwables.yml", "economy/economy.yml", "gui/icons.yml", "hud/hud.yml");

    /** Folders created on first start, even if empty, so admins see the layout. */
    static final List<String> FOLDERS = List.of("maps", "map-pools", "kits", "items", "economy",
            "sounds", "npc", "modes", "messages", "logs/matches", "backups", "data", "resourcepack", "lobby", "blocks", "gui", "hud");

    public enum Target {
        CONFIG, MODES, KITS, MESSAGES, MAPS, ITEMS, ECONOMY, HUD, ALL;

        public static Optional<Target> parse(String s) {
            try {
                return Optional.of(valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private final Plugin plugin;
    private final Path root;
    private final Logger log;

    private volatile GeneralConfig general = GeneralConfig.defaults();
    private volatile Map<String, ModeRules> modes = Map.of();
    private volatile Map<String, KitDefinition> kits = Map.of();
    private volatile Map<String, dev.tactical.paper.item.ItemDefinition> items = Map.of();
    private volatile Map<String, String> messages = Map.of();
    private volatile dev.tactical.engine.economy.EconomyRules economy = dev.tactical.engine.economy.EconomyRules.defaults();
    private volatile dev.tactical.paper.hud.HudConfig hud = dev.tactical.paper.hud.HudConfig.defaults();

    public ConfigService(Plugin plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath();
        this.log = plugin.getLogger();
    }

    @Override
    public void enable() throws IOException {
        installDefaults();
        ConfigReport report = reload(Target.ALL);
        if (report.hasErrors()) {
            log.warning("Configuration loaded with errors; invalid files were skipped (see above).");
        }
    }

    @Override
    public void disable() {
    }

    // ------------------------------------------------------------------ queries (immutable snapshots)

    public Path root() {
        return root;
    }

    public GeneralConfig general() {
        return general;
    }

    public Optional<ModeRules> mode(String id) {
        return Optional.ofNullable(modes.get(id));
    }

    public Map<String, ModeRules> modes() {
        return modes;
    }

    /** Custom items from items/*.yml, by item id. */
    public Map<String, dev.tactical.paper.item.ItemDefinition> items() {
        return items;
    }

    public Map<String, KitDefinition> kits() {
        return kits;
    }

    /** Enabled kit ids in stable (alphabetical) order. */
    public List<String> enabledKitIds() {
        return kits.values().stream().filter(KitDefinition::enabled).map(KitDefinition::id).sorted().toList();
    }

    public Map<String, String> messages() {
        return messages;
    }

    public dev.tactical.engine.economy.EconomyRules economy() {
        return economy;
    }

    public dev.tactical.paper.hud.HudConfig hud() {
        return hud;
    }

    // ------------------------------------------------------------------ loading

    public synchronized ConfigReport reload(Target target) {
        ConfigReport report = new ConfigReport();
        if (target == Target.CONFIG || target == Target.ALL) {
            loadFile(root.resolve("config.yml"), "config", GeneralConfig.SCHEMA_VERSION, report)
                    .map(GeneralConfig::parse).ifPresent(g -> {
                        if (!report.hasErrors()) general = g;
                    });
        }
        if (target == Target.MODES || target == Target.ALL) {
            modes = loadDirectory("modes", "mode", ModeRulesParser.SCHEMA_VERSION, modes,
                    (node) -> ModeRulesParser.parse(node, baseName(node.file())), report);
        }
        if (target == Target.KITS || target == Target.ALL) {
            kits = loadDirectory("kits", "kit", KitDefinition.SCHEMA_VERSION, kits,
                    (node) -> KitDefinition.parse(node, baseName(node.file())), report);
        }
        if (target == Target.MESSAGES || target == Target.ALL) {
            // Built-in English first, then the admin's file on top: keys added in plugin updates always have text.
            Map<String, String> bundled = new LinkedHashMap<>();
            try (InputStream in = plugin.getResource("defaults/messages/en_US.yml")) {
                if (in != null) {
                    YamlLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "(built-in messages)", new ConfigReport())
                            .ifPresent(n -> n.keys().forEach(k -> n.child(k).scalar().ifPresent(v -> bundled.put(k, v))));
                }
            } catch (IOException ignored) {
                // file-only messages
            }
            messages = Collections.unmodifiableMap(bundled);
            loadFile(root.resolve("messages/en_US.yml"), "messages", 1, report).ifPresent(node -> {
                Map<String, String> m = new LinkedHashMap<>(bundled);
                for (String k : node.keys()) {
                    if (!k.equals("schema-version")) node.child(k).scalar().ifPresent(v -> m.put(k, v));
                }
                messages = Collections.unmodifiableMap(m);
            });
        }
        if (target == Target.ITEMS || target == Target.ALL) {
            Map<String, Map<String, dev.tactical.paper.item.ItemDefinition>> files = loadDirectory("items", "items", 1, Map.of(),
                    dev.tactical.paper.item.ItemDefinition::parseFile, report);
            Map<String, dev.tactical.paper.item.ItemDefinition> all = new TreeMap<>();
            for (Map.Entry<String, Map<String, dev.tactical.paper.item.ItemDefinition>> f : files.entrySet()) {
                for (var d : f.getValue().values()) {
                    if (all.putIfAbsent(d.id(), d) != null) report.error("items/" + f.getKey() + ".yml", 0, "items." + d.id(),
                            "item id '" + d.id() + "' is defined in more than one file");
                }
            }
            items = Collections.unmodifiableMap(all);
        }
        if (target == Target.KITS || target == Target.ITEMS || target == Target.ALL) {
            for (KitDefinition kit : kits.values()) {
                for (var e : kit.shop().entries().values()) {
                    if (e.customItem() != null && !items.containsKey(e.customItem())) {
                        report.error("kits/" + kit.id() + ".yml", 0, "shop." + e.category() + "." + e.id(), "unknown custom item '" + e.customItem() + "' (see items/*.yml)");
                    }
                }
                for (var b : kit.baseKit()) {
                    if (b.item() != null && !items.containsKey(b.item())) {
                        report.error("kits/" + kit.id() + ".yml", 0, "base-kit", "unknown custom item '" + b.item() + "' (see items/*.yml)");
                    }
                }
            }
        }
        if (target == Target.ECONOMY || target == Target.ALL) {
            ConfigReport er = new ConfigReport();
            loadFile(root.resolve("economy/economy.yml"), "economy", dev.tactical.engine.economy.EconomyRules.SCHEMA_VERSION, er)
                    .flatMap(dev.tactical.engine.economy.EconomyRules::parse).ifPresent(e -> {
                        if (!er.hasErrors()) economy = e;
                    });
            report.addAll(er);
        }
        if (target == Target.HUD || target == Target.ALL) {
            ConfigReport hr = new ConfigReport();
            loadFile(root.resolve("hud/hud.yml"), "hud", dev.tactical.paper.hud.HudConfig.SCHEMA_VERSION, hr)
                    .map(dev.tactical.paper.hud.HudConfig::parse).ifPresent(h -> {
                        if (!hr.hasErrors()) hud = h;
                    });
            report.addAll(hr);
        }
        for (ConfigIssue issue : report.issues()) {
            if (issue.severity() == ConfigIssue.Severity.ERROR) log.severe(issue.toString());
            else log.warning(issue.toString());
        }
        log.info("Loaded configuration (" + target + "): " + modes.size() + " modes, " + kits.size() + " kits, " + items.size() + " items"
                + (report.hasErrors() ? ", " + report.errors().size() + " error(s)" : ""));
        return report;
    }

    private <T> Map<String, T> loadDirectory(String folder, String kind, int version, Map<String, T> previous,
                                             Function<ConfigNode, Optional<T>> parser, ConfigReport report) {
        Map<String, T> out = new TreeMap<>();
        Path dir = root.resolve(folder);
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                String id = baseName(file.getFileName().toString());
                ConfigReport fileReport = new ConfigReport();
                Optional<T> parsed = loadFile(file, kind, version, fileReport).flatMap(parser);
                report.addAll(fileReport);
                if (parsed.isPresent() && !fileReport.hasErrors()) {
                    out.put(id, parsed.get());
                } else if (previous.containsKey(id)) {
                    out.put(id, previous.get(id));
                    report.warning(rel(file), 0, "", "invalid, so the previously loaded version stays active");
                }
            }
        } catch (IOException e) {
            report.error(folder, 0, "", "cannot list folder: " + e.getMessage());
            return previous;
        }
        return Collections.unmodifiableMap(out);
    }

    /** Reads, migrates (with backup) and parses one file into a node tree. */
    private Optional<ConfigNode> loadFile(Path file, String kind, int version, ConfigReport report) {
        String name = rel(file);
        if (!Files.exists(file)) {
            report.error(name, 0, "", "file not found");
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Optional<ConfigNode> parsed = YamlLoader.parse(text, name, report);
            if (parsed.isEmpty()) return Optional.empty();
            int fileVersion = parsed.get().integer(ConfigMigrator.VERSION_KEY, 1, 1, Integer.MAX_VALUE);
            if (fileVersion == version) return parsed;
            if ((kind.equals("hud") || kind.equals("kit")) && fileVersion < version) {
                // Layout tuning / placeholder kits whose format changed: start over from the bundled default, if any.
                String bundledPath = "defaults/" + root.relativize(file).toString().replace('\\', '/');
                try (var in = ConfigService.class.getClassLoader().getResourceAsStream(bundledPath)) {
                    if (in != null) {
                        backup(file);
                        String fresh = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        Files.writeString(file, fresh, StandardCharsets.UTF_8);
                        log.warning(name + ": format changed in this version; your old file was backed up to backups/config/ and replaced with the new default");
                        return YamlLoader.parse(fresh, name, report);
                    }
                }
                if (kind.equals("kit")) {
                    // A retired placeholder kit (no longer bundled): put it aside so it stops showing up.
                    backup(file);
                    Files.delete(file);
                    log.warning(name + ": retired placeholder kit removed (backed up to backups/config/)");
                    return Optional.empty();
                }
            }
            Map<String, Object> map = YamlLoader.loadMap(text);
            ConfigMigrator.Result result = new ConfigMigrator(kind, version, kind.equals("mode") ? dev.tactical.engine.config.ModeRulesParser.MIGRATIONS : List.of()).migrate(map);
            switch (result.outcome()) {
                case MIGRATED -> {
                    backup(file);
                    String migrated = dumper().dump(map);
                    Files.writeString(file, migrated, StandardCharsets.UTF_8);
                    log.info(name + ": " + result.message() + " (original backed up; comments are not preserved)");
                    return YamlLoader.parse(migrated, name, report);
                }
                case TOO_NEW, INVALID_VERSION -> {
                    report.error(name, 0, ConfigMigrator.VERSION_KEY, result.message());
                    return Optional.empty();
                }
                default -> {
                    return parsed;
                }
            }
        } catch (IOException | RuntimeException e) {
            report.error(name, 0, "", "cannot load: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void backup(Path file) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = root.resolve("backups/config/" + ts).resolve(root.relativize(file));
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void installDefaults() throws IOException {
        for (String folder : FOLDERS) {
            Files.createDirectories(root.resolve(folder));
        }
        for (String rel : DEFAULT_FILES) {
            Path target = root.resolve(rel);
            if (Files.exists(target)) continue; // never overwrite admin files
            try (InputStream in = plugin.getResource("defaults/" + rel)) {
                if (in == null) {
                    log.severe("Bundled default missing from jar: " + rel);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
                log.info("Created default " + rel);
            }
        }
    }

    private String rel(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String baseName(String fileName) {
        String n = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
        return n.endsWith(".yml") ? n.substring(0, n.length() - 4) : n;
    }

    private static Yaml dumper() {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setIndent(2);
        return new Yaml(o);
    }
}
