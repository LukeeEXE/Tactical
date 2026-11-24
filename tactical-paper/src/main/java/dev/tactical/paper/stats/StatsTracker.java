package dev.tactical.paper.stats;

import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchEvents;
import dev.tactical.paper.match.MatchLifecycleHook;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Kills, deaths, assists and damage per match. Assists: a teammate of the killer damaged the victim in the last
 * 10 seconds; published as {@link MatchEvents.AssistAwarded} so the economy can pay for it.
 */
public final class StatsTracker implements Service, Listener, MatchLifecycleHook {

    private static final long ASSIST_WINDOW_TICKS = 200;

    private final Plugin plugin;
    private final MatchService matches;
    private final Map<MatchId, Map<UUID, MatchStats>> stats = new HashMap<>();
    /** victim -> attacker -> match tick of last damage, reset each round. */
    private final Map<UUID, Map<UUID, Long>> damagers = new HashMap<>();

    public StatsTracker(Plugin plugin, MatchService matches) {
        this.plugin = plugin;
        this.matches = matches;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        matches.addHook(this);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    public Map<UUID, MatchStats> stats(MatchId id) {
        return stats.getOrDefault(id, Map.of());
    }

    public MatchStats of(MatchId id, UUID player) {
        return stats.computeIfAbsent(id, k -> new LinkedHashMap<>()).computeIfAbsent(player, k -> new MatchStats());
    }

    @Override
    public CompletableFuture<Void> prepare(GameMatch match, MatchService.MatchRequest request) {
        Map<UUID, MatchStats> m = new LinkedHashMap<>();
        for (MatchMember mm : match.members()) m.put(mm.uuid(), new MatchStats());
        stats.put(match.id(), m);
        match.bus().subscribe(MatchFlowEvent.class, e -> onEvent(match, e));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void teardown(GameMatch match) {
        // Kept until the NEXT match with the same id could exist (never), but drop it to avoid leaks.
        Bukkit.getScheduler().runTaskLater(plugin, () -> stats.remove(match.id()), 20L);
        for (MatchMember mm : match.members()) damagers.remove(mm.uuid());
    }

    private void onEvent(GameMatch match, MatchFlowEvent e) {
        switch (e) {
            case MatchFlowEvent.RoundStarted rs -> damagers.keySet().removeIf(match::isMember);
            case MatchFlowEvent.PlayerEliminated pe -> {
                of(match.id(), pe.victim()).deaths++;
                if (pe.killer() != null && pe.cause() == EliminationCause.KILLED) {
                    of(match.id(), pe.killer()).kills++;
                    TeamId killerTeam = match.member(pe.killer()).map(MatchMember::team).orElse(null);
                    for (Map.Entry<UUID, Long> h : damagers.getOrDefault(pe.victim(), Map.of()).entrySet()) {
                        if (h.getKey().equals(pe.killer()) || match.flow().currentTick() - h.getValue() > ASSIST_WINDOW_TICKS) continue;
                        if (match.member(h.getKey()).map(MatchMember::team).orElse(null) != killerTeam) continue;
                        of(match.id(), h.getKey()).assists++;
                        match.recorder().record(EventTypes.ASSIST, List.of(h.getKey(), pe.victim()), killerTeam,
                                Map.of("assister", h.getKey(), "victim", pe.victim(), "killer", pe.killer()));
                        match.bus().publish(new MatchEvents.AssistAwarded(h.getKey(), pe.victim(), pe.killer()));
                    }
                }
                damagers.remove(pe.victim());
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolve(e.getDamager());
        if (attacker == null || attacker == victim) return;
        Optional<GameMatch> m = matches.matchOf(victim.getUniqueId());
        if (m.isEmpty() || !m.get().isMember(attacker.getUniqueId())) return;
        damagers.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>()).put(attacker.getUniqueId(), m.get().flow().currentTick());
        of(m.get().id(), attacker.getUniqueId()).damage += Math.min(e.getFinalDamage(), victim.getHealth());
    }

    private static Player resolve(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }
}
