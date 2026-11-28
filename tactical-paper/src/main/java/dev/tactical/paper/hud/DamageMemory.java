package dev.tactical.paper.hud;

import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who damaged which opponent this round. An opponent's health (bar above their head, line on the boss bar) only
 * shows to players who hurt them in the current round; teammates always see each other's.
 */
public final class DamageMemory implements Listener {

    private static DamageMemory instance;

    private final MatchService matches;
    /** attacker -> (victim -> round number in which they were last hurt). */
    private final Map<UUID, Map<UUID, Integer>> hurt = new HashMap<>();

    public DamageMemory(MatchService matches) {
        this.matches = matches;
        instance = this;
    }

    public static DamageMemory get() {
        return instance;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || e.getFinalDamage() <= 0) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || attacker.equals(victim)) return;
        GameMatch match = matches.matchOf(attacker.getUniqueId()).orElse(null);
        if (match == null) return;
        MatchMember a = match.member(attacker.getUniqueId()).orElse(null);
        MatchMember v = match.member(victim.getUniqueId()).orElse(null);
        if (a == null || v == null || a.team() == v.team()) return;
        hurt.computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>()).put(victim.getUniqueId(), match.flow().round());
    }

    /** Whether {@code viewer} may see {@code target}'s health: same team, or hurt them this round. */
    public boolean canSeeHealth(GameMatch match, UUID viewer, UUID target) {
        if (viewer.equals(target)) return true;
        MatchMember a = match.member(viewer).orElse(null);
        MatchMember b = match.member(target).orElse(null);
        if (a == null || b == null) return false;
        if (a.team() == b.team()) return true;
        Integer round = hurt.getOrDefault(viewer, Map.of()).get(target);
        return round != null && round == match.flow().round();
    }

    public void forget(UUID player) {
        hurt.remove(player);
    }
}
