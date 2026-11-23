package dev.tactical.paper.map;

import dev.tactical.engine.config.ConfigIssue;
import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.engine.map.MapCodec;
import dev.tactical.engine.map.MapDefinition;
import dev.tactical.engine.map.MapPool;
import dev.tactical.engine.map.MapSelector;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.core.Tasks;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Map definitions ({@code maps/*.yml}) and pools ({@code map-pools/*.yml}). Unlike other config these are
 * also WRITTEN by the in-game editor: writes are atomic (temp file + move) and the previous version of a map file
 * is kept in {@code backups/maps/<id>/v<version>.yml}, so no edit can destroy a map.
 */
public final class MapService implements Service {

    private final ConfigService config;
    private final Tasks tasks;
    private final Logger log;
    private final MapWorlds worlds;
    private volatile Map<String, MapDefinition> maps = Map.of();
    private volatile Map<String, MapPool> pools = Map.of();

    public MapService(ConfigService config, Tasks tasks, Logger log) {
        this.config = config;
        this.tasks = tasks;
        this.log = log;
        this.worlds = new MapWorlds(tasks, config.root().resolve("maps/snapshots"), log);
    }

    @Override
    public void enable() throws IOException {
        Files.createDirectories(config.root().resolve("maps/snapshots"));
        for (String mode : List.of("quickplay", "competitive")) {
            Path p = config.root().resolve("map-pools/" + mode + ".yml");
            if (!Files.exists(p)) {
                writeYaml(p, new MapPool(mode, mode, true, Map.of()).toYaml(),
                        "# Map pool for " + mode + ". Manage with /game pool ... (maps: <id>: true|false)\n");
            }
        }
        worlds.cleanupStaleInstances();
        reload();
    }

    @Override
    public void disable() {
        worlds.shutdown();
    }

    public MapWorlds worlds() {
        return worlds;
    }

    // ------------------------------------------------------------------ loading

    public synchronized ConfigReport reload() {
        ConfigReport report = new ConfigReport();
        Map<String, MapDefinition> previous = maps;
        Map<String, MapDefinition> loaded = new TreeMap<>(loadDir("maps", (node, id) -> MapCodec.parse(node, id), maps, report));
        // A layout edited by hand (not through the editor) must be re-validated before matches may use it.
        for (Map.Entry<String, MapDefinition> e : List.copyOf(loaded.entrySet())) {
            MapDefinition old = previous.get(e.getKey());
            MapDefinition now = e.getValue();
            if (old != null && now.status() != dev.tactical.engine.map.MapStatus.DRAFT && !sameLayout(old, now)) {
                loaded.put(e.getKey(), now.withStatus(dev.tactical.engine.map.MapStatus.DRAFT, System.currentTimeMillis()));
                report.warning("maps/" + e.getKey() + ".yml", 0, "", "layout changed outside the editor; the map is DRAFT until "
                        + "validated again (/game map validate " + e.getKey() + ")");
            }
        }
        maps = Collections.unmodifiableMap(loaded);
        pools = loadDir("map-pools", (node, id) -> MapPool.parse(node, id), pools, report);
        for (MapPool pool : pools.values()) {
            for (String mapId : pool.maps().keySet()) {
                if (!maps.containsKey(mapId)) report.warning("map-pools/" + pool.id() + ".yml", 0, "maps." + mapId, "unknown map");
            }
            if (pool.enabled() && MapSelector.eligible(pool, maps).isEmpty()) {
                report.warning("map-pools/" + pool.id() + ".yml", 0, "", "pool '" + pool.id()
                        + "' has NO valid, published, enabled maps; matchmaking for " + pool.mode() + " cannot start");
            }
        }
        for (ConfigIssue i : report.issues()) {
            if (i.severity() == ConfigIssue.Severity.ERROR) log.severe(i.toString());
            else log.warning(i.toString());
        }
        log.info("Loaded " + maps.size() + " map(s) and " + pools.size() + " pool(s)");
        return report;
    }

    private static boolean sameLayout(MapDefinition a, MapDefinition b) {
        return a.version() == b.version() && java.util.Objects.equals(a.bounds(), b.bounds())
                && a.attackerSpawns().equals(b.attackerSpawns()) && a.defenderSpawns().equals(b.defenderSpawns())
                && a.sites().equals(b.sites()) && a.barriers().equals(b.barriers())
                && a.constructionRegions().equals(b.constructionRegions()) && a.voidY() == b.voidY();
    }

    private <T> Map<String, T> loadDir(String folder, BiFunction<dev.tactical.engine.config.ConfigNode, String, Optional<T>> parser,
                                       Map<String, T> previous, ConfigReport report) {
        Map<String, T> out = new TreeMap<>();
        Path dir = config.root().resolve(folder);
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                String id = f.getFileName().toString().replace(".yml", "");
                ConfigReport fr = new ConfigReport();
                Optional<T> parsed = YamlLoader.load(f, folder + "/" + f.getFileName(), fr).flatMap(n -> parser.apply(n, id));
                report.addAll(fr);
                if (parsed.isPresent() && !fr.hasErrors()) out.put(id, parsed.get());
                else if (previous.containsKey(id)) out.put(id, previous.get(id));
            }
        } catch (IOException e) {
            report.error(folder, 0, "", e.getMessage());
            return previous;
        }
        return Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------ maps

    public Optional<MapDefinition> map(String id) {
        return Optional.ofNullable(maps.get(id));
    }

    public Map<String, MapDefinition> maps() {
        return maps;
    }

    /** Persists a map (atomic) and swaps it into the live registry. Main thread. */
    public synchronized void save(MapDefinition def) throws IOException {
        tasks.requireMainThread("MapService#save");
        Path file = config.root().resolve("maps/" + def.id() + ".yml");
        MapDefinition old = maps.get(def.id());
        if (old != null && Files.exists(file)) {
            Path backup = config.root().resolve("backups/maps/" + def.id() + "/v" + old.version() + ".yml");
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        writeYaml(file, MapCodec.toYaml(def), """
                # Map definition. Edited in-game with /game map ... (manual edits are fine too; run /game reload maps).
                # Coordinates are in the map's template world. Every layout change increments 'version'.
                """);
        Map<String, MapDefinition> m = new TreeMap<>(maps);
        m.put(def.id(), def);
        maps = Collections.unmodifiableMap(m);
    }

    public synchronized void deleteDefinition(String id) throws IOException {
        Path file = config.root().resolve("maps/" + id + ".yml");
        if (Files.exists(file)) {
            Path backup = config.root().resolve("backups/maps/" + id + "/deleted-" + System.currentTimeMillis() + ".yml");
            Files.createDirectories(backup.getParent());
            Files.move(file, backup);
        }
        Map<String, MapDefinition> m = new TreeMap<>(maps);
        m.remove(id);
        maps = Collections.unmodifiableMap(m);
        for (MapPool p : pools.values()) {
            if (p.maps().containsKey(id)) savePool(p.withoutMap(id));
        }
    }

    // ------------------------------------------------------------------ pools

    public Optional<MapPool> pool(String id) {
        return Optional.ofNullable(pools.get(id));
    }

    public Map<String, MapPool> pools() {
        return pools;
    }

    /** The pool used by matchmaking for a mode: the enabled pool whose id equals the mode, else the first enabled one. */
    public Optional<MapPool> poolForMode(String mode) {
        MapPool exact = pools.get(mode);
        if (exact != null && exact.enabled() && exact.mode().equals(mode)) return Optional.of(exact);
        return pools.values().stream().filter(p -> p.enabled() && p.mode().equals(mode)).findFirst();
    }

    public synchronized void savePool(MapPool pool) throws IOException {
        writeYaml(config.root().resolve("map-pools/" + pool.id() + ".yml"), pool.toYaml(),
                "# Map pool. Manage with /game pool ... (maps: <id>: true|false)\n");
        Map<String, MapPool> m = new TreeMap<>(pools);
        m.put(pool.id(), pool);
        pools = Collections.unmodifiableMap(m);
    }

    public synchronized void deletePool(String id) throws IOException {
        Path file = config.root().resolve("map-pools/" + id + ".yml");
        if (Files.exists(file)) {
            Path backup = config.root().resolve("backups/map-pools/" + id + "-" + System.currentTimeMillis() + ".yml");
            Files.createDirectories(backup.getParent());
            Files.move(file, backup);
        }
        Map<String, MapPool> m = new TreeMap<>(pools);
        m.remove(id);
        pools = Collections.unmodifiableMap(m);
    }

    private static void writeYaml(Path file, Map<String, Object> data, String header) throws IOException {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.AUTO); // short lists/points stay on one line
        o.setIndent(2);
        o.setWidth(160);
        String text = header + new Yaml(o).dump(data);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
