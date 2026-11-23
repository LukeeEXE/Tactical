package dev.tactical.paper.player;

import dev.tactical.paper.core.Service;
import dev.tactical.paper.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player records and online sessions. UUID is the authoritative identity; names are kept as history so
 * offline players can be found (reports, moderation). The player row is written during async pre-login,
 * so the main thread never waits on the database when someone joins.
 */
public final class PlayerService implements Service, Listener {

    private final Plugin plugin;
    private final Database db;
    private final Logger log;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public PlayerService(Plugin plugin, Database db, Logger log) {
        this.plugin = plugin;
        this.db = db;
        this.log = log;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) { // /reload safety
            sessions.put(p.getUniqueId(), new PlayerSession(p.getUniqueId(), p.getName()));
            db.async(c -> upsert(c, p.getUniqueId(), p.getName()));
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        sessions.clear();
    }

    public Optional<PlayerSession> session(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    public PlayerSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), u -> new PlayerSession(u, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            db.blocking(c -> upsert(c, event.getUniqueId(), event.getName()), 10);
        } catch (Exception e) {
            // Never block logins because of storage trouble; the row will be written on the next login.
            log.log(Level.WARNING, "Could not store player record for " + event.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        sessions.put(p.getUniqueId(), new PlayerSession(p.getUniqueId(), p.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Run after MatchService has handled the disconnect (it listens at a lower priority).
        sessions.remove(id);
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET last_seen = ? WHERE uuid = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, id.toString());
                return ps.executeUpdate();
            }
        });
    }

    /** Resolves a UUID from a current or past name (most recently seen first). Database thread only. */
    public java.util.concurrent.CompletableFuture<Optional<UUID>> findByName(String name) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM player_names WHERE name = ? COLLATE NOCASE ORDER BY seen_at DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
                }
            }
        });
    }

    private static int upsert(java.sql.Connection c, UUID uuid, String name) throws java.sql.SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO players (uuid, last_name, first_seen, last_seen) VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, last_seen = excluded.last_seen""")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO player_names (uuid, name, seen_at) VALUES (?, ?, ?)
                ON CONFLICT(uuid, name) DO UPDATE SET seen_at = excluded.seen_at""")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, now);
            return ps.executeUpdate();
        }
    }
}
