package dev.tactical.paper.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.core.Tasks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage (WAL mode). All writes go through ONE dedicated thread, so SQLite never sees concurrent writers
 * and the main thread never blocks on I/O. Schema changes are versioned migrations applied in a transaction;
 * an already-applied migration whose contents changed is refused (prevents silent schema drift). Before applying
 * migrations to an existing database a backup is taken with {@code VACUUM INTO}.
 */
public final class Database implements Service {

    /** Ordered list of bundled migrations ({@code db/migration/<name>}). Never edit or reorder applied ones. */
    static final List<String> MIGRATIONS = List.of("V1__init.sql");

    private final ConfigService config;
    private final Tasks tasks;
    private final Logger log;
    private HikariDataSource dataSource;
    private ExecutorService executor;

    public Database(ConfigService config, Tasks tasks, Logger log) {
        this.config = config;
        this.tasks = tasks;
        this.log = log;
    }

    @Override
    public void enable() throws Exception {
        Path file = config.root().resolve(config.general().sqliteFile());
        Files.createDirectories(file.getParent());
        boolean existed = Files.exists(file);
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("Tactical-SQLite");
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        hc.setMaximumPoolSize(3);
        hc.setConnectionInitSql("PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
        dataSource = new HikariDataSource(hc);
        executor = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("Tactical-DB").daemon(true).unstarted(r));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
        }
        migrate(existed);
    }

    @Override
    public void disable() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.severe("Database writer did not finish within 30s; some writes may be lost");
            }
        }
        if (dataSource != null) dataSource.close();
    }

    /** Runs {@code work} on the database thread. Never call {@code join()} on the result from the main thread. */
    public <T> CompletableFuture<T> async(SqlWork<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        executor.execute(() -> {
            try (Connection c = dataSource.getConnection()) {
                f.complete(work.run(c));
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Database operation failed", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Runs {@code work} inside a transaction on the database thread; rolls back on any failure (all-or-nothing). */
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return async(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** Blocking variant for code that already runs off the main thread (e.g. AsyncPlayerPreLoginEvent). */
    public <T> T blocking(SqlWork<T> work, long timeoutSeconds) throws Exception {
        tasks.requireAsync("Database#blocking");
        return async(work).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /** Online backup of the whole database to {@code target} (consistent snapshot). */
    public CompletableFuture<Path> backupTo(Path target) {
        return async(c -> {
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                throw new SQLException("cannot create backup folder", e);
            }
            try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, target.toAbsolutePath().toString());
                ps.execute();
            }
            return target;
        });
    }

    private void migrate(boolean existed) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)");
            }
            boolean backedUp = false;
            for (String name : MIGRATIONS) {
                String sql = readMigration(name);
                String checksum = sha256(sql);
                String applied = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT checksum FROM schema_migrations WHERE name = ?")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) applied = rs.getString(1);
                    }
                }
                if (applied != null) {
                    if (!applied.equals(checksum)) {
                        throw new IllegalStateException("Migration " + name + " was modified after being applied. "
                                + "Refusing to start to protect your data.");
                    }
                    continue;
                }
                if (existed && !backedUp) {
                    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                    Path target = config.root().resolve("backups/database/pre-migration-" + ts + ".db");
                    Files.createDirectories(target.getParent());
                    try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                        ps.setString(1, target.toAbsolutePath().toString());
                        ps.execute();
                    }
                    log.info("Backed up database to " + target.getFileName() + " before migrating");
                    backedUp = true;
                }
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    for (String stmt : splitStatements(sql)) s.execute(stmt);
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO schema_migrations VALUES (?, ?, ?)")) {
                        ps.setString(1, name);
                        ps.setString(2, checksum);
                        ps.setLong(3, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                    c.commit();
                    log.info("Applied database migration " + name);
                } catch (SQLException e) {
                    c.rollback();
                    throw new IllegalStateException("Database migration " + name + " failed and was rolled back", e);
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
    }

    private String readMigration(String name) throws IOException {
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
            if (in == null) throw new IOException("Missing bundled migration " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> splitStatements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\n")) {
            String t = line.strip();
            if (t.startsWith("--")) continue;
            cleaned.append(line).append('\n');
        }
        return java.util.Arrays.stream(cleaned.toString().split(";"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String sha256(String s) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
