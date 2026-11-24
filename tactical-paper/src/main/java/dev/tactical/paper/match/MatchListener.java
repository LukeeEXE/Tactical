package dev.tactical.paper.match;

import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.Participant;
import dev.tactical.paper.core.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;

/**
 * Server-side isolation and combat rules, evaluated on every interaction:
 * <ul>
 *   <li>No damage or entity interaction between different matches, or between a match and the lobby.</li>
 *   <li>No PvP in the lobby.</li>
 *   <li>No friendly fire (same ORIGINAL team), no damage while paused or outside a live round.</li>
 *   <li>Dead/eliminated participants cannot deal damage.</li>
 * </ul>
 * Map geometry protection, spectator rules and item handling arrive with Phases 2-4.
 */
public final class MatchListener implements Service, Listener {

    private final Plugin plugin;
    private final MatchService matches;

    public MatchListener(Plugin plugin, MatchService matches) {
        this.plugin = plugin;
        this.matches = matches;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Optional<GameMatch> vm = matches.matchOf(victim.getUniqueId());
        if (vm.isEmpty()) return;
        GameMatch m = vm.get();
        // Participants can only be hurt during a live, un-paused round, and only while alive.
        boolean alive = m.flow().participant(victim.getUniqueId()).map(Participant::active).orElse(false);
        if (m.flow().isPaused() || !m.state().isRoundActive() || !alive) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = dev.tactical.paper.arena.KillMethods.attacker(event.getDamager());
        if (attacker == null || attacker == event.getEntity()) return; // own TNT/utility may hurt yourself
        if (!allowedToHurt(attacker, event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Optional<GameMatch> a = matches.matchOf(event.getPlayer().getUniqueId());
        if (!(event.getRightClicked() instanceof Player target)) return;
        Optional<GameMatch> b = matches.matchOf(target.getUniqueId());
        if (!sameMatch(a, b)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        // Matches without a map (console tests) still need deaths reported; arenas handle the rest.
        matches.matchOf(event.getPlayer().getUniqueId())
                .ifPresent(m -> m.flow().eliminate(event.getPlayer().getUniqueId(), null, EliminationCause.ENVIRONMENT));
    }

    private boolean allowedToHurt(Player attacker, Entity victimEntity) {
        Optional<GameMatch> am = matches.matchOf(attacker.getUniqueId());
        if (!(victimEntity instanceof Player victim)) {
            // Participants may not damage non-player entities outside their own match context (NPCs, lobby displays).
            return am.isEmpty() || victimEntity.getWorld().equals(attacker.getWorld());
        }
        Optional<GameMatch> vm = matches.matchOf(victim.getUniqueId());
        if (am.isEmpty() && vm.isEmpty()) return false;          // lobby: no PvP
        if (!sameMatch(am, vm)) return false;                    // cross-match / lobby↔match
        GameMatch m = am.get();
        if (m.flow().isPaused() || !m.state().isRoundActive()) return false;
        Participant a = m.flow().participant(attacker.getUniqueId()).orElse(null);
        Participant v = m.flow().participant(victim.getUniqueId()).orElse(null);
        if (a == null || v == null || !a.active() || !v.active()) return false;
        return a.team() != v.team();                              // friendly fire disabled
    }

    private static boolean sameMatch(Optional<GameMatch> a, Optional<GameMatch> b) {
        if (a.isEmpty() && b.isEmpty()) return true;
        return a.isPresent() && b.isPresent() && a.get().id().equals(b.get().id());
    }

}
