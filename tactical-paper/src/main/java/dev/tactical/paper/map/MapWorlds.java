package dev.tactical.paper.map;

import dev.tactical.engine.id.MatchId;
import dev.tactical.paper.core.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * World handling for maps. On 26.x every world is a dimension keyed by a {@link NamespacedKey} and stored under
 * {@code <level>/dimensions/<namespace>/<key>/}.
 * <ul>
 *   <li><b>Template</b> {@code tactical:map_<id>}: the world admins build and edit.</li>
 *   <li><b>Snapshot</b> {@code plugins/Tactical/maps/snapshots/<id>/}: a frozen copy taken on validate/publish.
 *       Matches copy from the snapshot, so editing a template never affects matches.</li>
 *   <li><b>Instance</b> {@code tactical:inst_<matchid>}: a throw-away copy per match, deleted at cleanup.</li>
 * </ul>
 * File copying runs on a background thread; world loading/unloading runs on the main thread.
 */
public final class MapWorlds {

    public static final String NAMESPACE = "tactical";
    /**
     * Never copied: lock files and world identity. Paper refuses to load a world whose metadata (UUID) duplicates
     * an already-known world, so every copy must get fresh metadata.
     */
    private static final Set<String> SKIP_FILES = Set.of("session.lock", "uid.dat", "metadata.dat");

    private final Tasks tasks;
    private final Path snapshotRoot;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            r -> Thread.ofPlatform().name("Tactical-MapIO").daemon(true).unstarted(r));

    public MapWorlds(Tasks tasks, Path snapshotRoot, Logger log) {
        this.tasks = tasks;
        this.snapshotRoot = snapshotRoot;
        this.log = log;
    }

    public void shutdown() {
        io.shutdown();
    }

    public static NamespacedKey templateKey(String mapId) {
        return new NamespacedKey(NAMESPACE, "map_" + mapId);
    }

    public static NamespacedKey instanceKey(MatchId id) {
        return new NamespacedKey(NAMESPACE, "inst_" + id.value().toLowerCase(Locale.ROOT).replace('-', '_'));
    }

    public static boolean isInstance(World w) {
        return w.getKey().getNamespace().equals(NAMESPACE) && w.getKey().getKey().startsWith("inst_");
    }

    public static boolean isTemplate(World w) {
        return w.getKey().getNamespace().equals(NAMESPACE) && w.getKey().getKey().startsWith("map_");
    }

    /** {@code <level>/dimensions}. */
    public static Path dimensionsRoot() {
        return Bukkit.getWorlds().getFirst().getWorldFolder().toPath().getParent().getParent();
    }

    public static Path folderOf(NamespacedKey key) {
        return dimensionsRoot().resolve(key.getNamespace()).resolve(key.getKey());
    }

    public Path snapshotFolder(String mapId) {
        return snapshotRoot.resolve(mapId);
    }

    public boolean hasSnapshot(String mapId) {
        return Files.isDirectory(snapshotFolder(mapId).resolve("region"));
    }

    // ------------------------------------------------------------------ templates

    /** Loads (creating if needed) the editable template world. Main thread. */
    public World loadTemplate(String mapId) {
        tasks.requireMainThread("loadTemplate");
        World existing = Bukkit.getWorld(templateKey(mapId));
        if (existing != null) return existing;
        World w = WorldCreator.ofKey(templateKey(mapId)).generator(new VoidGenerator()).generateStructures(false).createWorld();
        if (w == null) throw new IllegalStateException("Could not create template world for " + mapId);
        configure(w, true);
        return w;
    }

    public Optional<World> loadedTemplate(String mapId) {
        return Optional.ofNullable(Bukkit.getWorld(templateKey(mapId)));
    }

    public boolean templateExists(String mapId) {
        return Bukkit.getWorld(templateKey(mapId)) != null || Files.isDirectory(folderOf(templateKey(mapId)));
    }

    /** Saves the template (main thread) then copies it to the snapshot folder (background). */
    public CompletableFuture<Void> snapshot(String mapId) {
        tasks.requireMainThread("snapshot");
        World w = loadTemplate(mapId);
        w.save(true);
        Path from = w.getWorldFolder().toPath();
        Path to = snapshotFolder(mapId);
        return CompletableFuture.runAsync(() -> {
            try {
                Path tmp = to.resolveSibling(mapId + ".tmp");
                deleteRecursively(tmp);
                copyRecursively(from, tmp);
                deleteRecursively(to);
                Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new RuntimeException("Snapshot of map " + mapId + " failed: " + e.getMessage(), e);
            }
        }, io);
    }

    /** Unloads and deletes the template and snapshot of a map. Players in it are moved by the caller first. */
    public CompletableFuture<Void> deleteTemplate(String mapId) {
        tasks.requireMainThread("deleteTemplate");
        World w = Bukkit.getWorld(templateKey(mapId));
        if (w != null && !Bukkit.unloadWorld(w, false)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Template world is still in use"));
        }
        Path folder = folderOf(templateKey(mapId));
        Path snap = snapshotFolder(mapId);
        return CompletableFuture.runAsync(() -> {
            try {
                deleteRecursively(folder);
                deleteRecursively(snap);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, io);
    }

    // ------------------------------------------------------------------ instances

    /** Copies the map snapshot into a fresh instance folder (background), then loads it (main thread). */
    public CompletableFuture<World> createInstance(String mapId, MatchId matchId) {
        NamespacedKey key = instanceKey(matchId);
        Path target = folderOf(key);
        Path snapshot = snapshotFolder(mapId);
        return CompletableFuture.runAsync(() -> {
            try {
                if (!Files.isDirectory(snapshot)) throw new IOException("map " + mapId + " has no snapshot (validate it first)");
                deleteRecursively(target);
                copyRecursively(snapshot, target);
            } catch (IOException e) {
                throw new RuntimeException("Could not prepare map instance: " + e.getMessage(), e);
            }
        }, io).thenApplyAsync(v -> {
            World w = WorldCreator.ofKey(key).generator(new VoidGenerator()).generateStructures(false).createWorld();
            if (w == null) {
                io.execute(() -> {
                    try {
                        deleteRecursively(target);
                    } catch (IOException ignored) {
                        // removed by the startup cleanup instead
                    }
                });
                throw new IllegalStateException("World creation returned null for " + key);
            }
            configure(w, false);
            return w;
        }, tasks.main());
    }

    /** Unloads (no save) and deletes an instance. Caller must have moved every player out. */
    public CompletableFuture<Void> destroyInstance(World world) {
        tasks.requireMainThread("destroyInstance");
        if (!isInstance(world)) return CompletableFuture.failedFuture(new IllegalArgumentException("not an instance: " + world.getKey()));
        Path folder = world.getWorldFolder().toPath();
        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (!unloaded) {
            log.warning("Could not unload instance " + world.getKey() + "; it will be deleted on next startup");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                deleteRecursively(folder);
            } catch (IOException e) {
                log.warning("Could not delete instance folder " + folder + ": " + e.getMessage());
            }
        }, io);
    }

    /** Deletes instance folders left behind by a crash. Called once at startup. */
    public void cleanupStaleInstances() {
        Path dir = dimensionsRoot().resolve(NAMESPACE);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("inst_")).toList()) {
                NamespacedKey key = new NamespacedKey(NAMESPACE, p.getFileName().toString());
                if (Bukkit.getWorld(key) != null) continue;
                deleteRecursively(p);
                log.info("Removed stale match instance " + p.getFileName());
            }
        } catch (IOException e) {
            log.warning("Stale instance cleanup failed: " + e.getMessage());
        }
    }

    private static void configure(World w, boolean template) {
        w.setAutoSave(template);
        w.setDifficulty(Difficulty.NORMAL);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        w.setGameRule(GameRules.SPAWN_PATROLS, false);
        w.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        w.setGameRule(GameRules.MOB_GRIEFING, false);
        w.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        // The locator bar would show every player's direction on the HUD: a free wallhack in a tactical shooter.
        w.setGameRule(GameRules.LOCATOR_BAR, false);
        w.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, false);
        w.setGameRule(GameRules.BLOCK_DROPS, false);
        w.setGameRule(GameRules.ENTITY_DROPS, false);
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        w.setGameRule(GameRules.TNT_EXPLODES, false);
        w.setGameRule(GameRules.PROJECTILES_CAN_BREAK_BLOCKS, false);
        w.setGameRule(GameRules.SPREAD_VINES, false);
        // Health regeneration is simulated by the plugin (food never changes); see RegenService.
        w.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        w.setTime(6000);
        w.setStorm(false);
    }

    static void copyRecursively(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!SKIP_FILES.contains(file.getFileName().toString())) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
