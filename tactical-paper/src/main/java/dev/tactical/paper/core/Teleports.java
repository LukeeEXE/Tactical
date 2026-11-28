package dev.tactical.paper.core;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Player teleports. Name tags and the flash effect ride on players as passengers, and Paper refuses to move a player
 * with passengers to ANOTHER world; they are dropped first (their owners re-create or re-mount them).
 */
public final class Teleports {

    private Teleports() {
    }

    public static boolean to(Player p, Location target) {
        if (target.getWorld() != null && !target.getWorld().equals(p.getWorld()) && !p.getPassengers().isEmpty()) {
            p.eject();
        }
        return p.teleport(target);
    }
}
