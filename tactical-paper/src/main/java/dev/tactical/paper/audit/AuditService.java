package dev.tactical.paper.audit;

import dev.tactical.paper.core.Service;
import dev.tactical.paper.storage.Database;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.util.logging.Logger;

/** Persists every administrative action (who, what, target, when). Written asynchronously; also echoed to the console. */
public final class AuditService implements Service {

    private final Database db;
    private final Logger log;

    public AuditService(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    @Override
    public void enable() {
    }

    @Override
    public void disable() {
    }

    public void record(CommandSender actor, String action, String target, String details) {
        String uuid = actor instanceof Player p ? p.getUniqueId().toString() : null;
        String name = actor.getName();
        long now = System.currentTimeMillis();
        log.info("[AUDIT] " + name + " " + action + (target == null ? "" : " " + target)
                + (details == null || details.isEmpty() ? "" : " (" + details + ")"));
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_log (actor_uuid, actor_name, action, target, details, created_at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, action);
                ps.setString(4, target);
                ps.setString(5, details);
                ps.setLong(6, now);
                return ps.executeUpdate();
            }
        });
    }
}
