package dev.tactical.paper.arena;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.map.MapDefinition;
import dev.tactical.engine.map.MapStatus;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.lobby.LobbyService;
import dev.tactical.paper.map.MapService;
import dev.tactical.paper.map.MapWorlds;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchLifecycleHook;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Creates a {@link MatchArena} (private map instance) for every match that has a map, routes Bukkit events to the
 * right arena, and destroys the instance when the match is cleaned up.
 */
public final class ArenaService implements Service, Listener, MatchLifecycleHook {

    private final Plugin plugin;
    private final ConfigService config;
    private final MapService maps;
    private final MatchService matches;
    private final LobbyService lobby;
    private final Logger log;
    private final NamespacedKey itemKey;
    private final Map<MatchId, MatchArena> arenas = new HashMap<>();
    private final Map<UUID, Long> lastSpectatorInput = new HashMap<>();

    public ArenaService(Plugin plugin, ConfigService config, MapService maps, MatchService matches, LobbyService lobby, Logger log) {
        this.plugin = plugin;
        this.config = config;
        this.maps = maps;
        this.matches = matches;
        this.lobby = lobby;
        this.log = log;
        this.itemKey = new NamespacedKey(plugin, "item_id");
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

    public Optional<MatchArena> arena(MatchId id) {
        return Optional.ofNullable(arenas.get(id));
    }

    // ================================================================== lifecycle hook

    @Override
    public Optional<String> validate(MatchService.MatchRequest r) {
        if (r.mapId() == null) return Optional.empty();
        MapDefinition m = maps.map(r.mapId()).orElse(null);
        if (m == null) return Optional.of("Unknown map '" + r.mapId() + "'");
        if (!m.supportsMode(r.modeId())) return Optional.of("Map '" + m.id() + "' does not support mode " + r.modeId());
        boolean ok = m.status() == MapStatus.PUBLISHED || (r.test() && m.status() == MapStatus.VALIDATED);
        if (!ok) return Optional.of("Map '" + m.id() + "' is " + m.status() + "; validate"
                + (r.test() ? "" : " and publish") + " it first (/game map validate)");
        if (!r.test() && !m.enabled()) return Optional.of("Map '" + m.id() + "' is disabled");
        if (!maps.worlds().hasSnapshot(m.id())) return Optional.of("Map '" + m.id() + "' has no snapshot; run /game map validate");
        return Optional.empty();
    }

    @Override
    public List<String> kits(MatchService.MatchRequest r, List<String> pool) {
        return r.mapId() == null ? pool : maps.map(r.mapId()).map(m -> m.kits().filter(pool)).orElse(pool);
    }

    @Override
    public Map<String, Object> header(MatchService.MatchRequest r) {
        if (r.mapId() == null) return Map.of();
        MapDefinition m = maps.map(r.mapId()).orElseThrow();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("map", m.id());
        h.put("map_version", m.version());
        h.put("map_name", m.name());
        Map<String, Object> sites = new LinkedHashMap<>();
        m.sites().values().forEach(s -> sites.put(s.id(), Map.of("min", List.of(s.region().min().x(), s.region().min().y(), s.region().min().z()),
                "max", List.of(s.region().max().x(), s.region().max().y(), s.region().max().z()))));
        h.put("map_sites", sites);
        return h;
    }

    @Override
    public CompletableFuture<Void> prepare(GameMatch match, MatchService.MatchRequest r) {
        if (r.mapId() == null) return CompletableFuture.completedFuture(null);
        MapDefinition m = maps.map(r.mapId()).orElseThrow();
        long start = System.currentTimeMillis();
        return maps.worlds().createInstance(m.id(), match.id()).thenAccept(world -> {
            if (match.flow().isFinished()) { // aborted while loading
                maps.worlds().destroyInstance(world);
                return;
            }
            var g = config.general();
            MatchArena arena = new MatchArena(match, m, world, lobby, itemKey, g.placeholderLoadout(), g.positionIntervalTicks(), log);
            arenas.put(match.id(), arena);
            arena.start();
            log.info("[match=" + match.id() + "] map instance " + world.getKey() + " ready in " + (System.currentTimeMillis() - start) + " ms");
        });
    }

    @Override
    public void teardown(GameMatch match) {
        MatchArena arena = arenas.remove(match.id());
        if (arena == null) return;
        try {
            arena.evacuate();
        } finally {
            World w = arena.world();
            // Unload on the next tick so evacuation teleports have fully completed.
            Bukkit.getScheduler().runTask(plugin, () -> maps.worlds().destroyInstance(w));
        }
    }

    // ================================================================== event routing

    private Optional<MatchArena> arenaOf(Player p) {
        return matches.matchOf(p.getUniqueId()).map(GameMatch::id).map(arenas::get);
    }

    private Optional<MatchArena> arenaOfWorld(World w) {
        if (!MapWorlds.isInstance(w)) return Optional.empty();
        for (MatchArena a : arenas.values()) if (a.world().equals(w)) return Optional.of(a);
        return Optional.empty();
    }

    private boolean isActive(Player p) {
        return matches.matchOf(p.getUniqueId()).flatMap(m -> m.flow().participant(p.getUniqueId())).map(Participant::active).orElse(false);
    }

    /** Lethal damage never kills: the player is eliminated and becomes a spectator instantly (no death screen, no drops). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        GameMatch m = matches.matchOf(victim.getUniqueId()).orElse(null);
        MatchArena arena = m == null ? null : arenas.get(m.id());
        if (arena == null || !isActive(victim)) return;
        arena.bomb().markDamaged(victim.getUniqueId());
        Player attacker = KillMethods.attacker(e);
        String method = KillMethods.method(e);
        UUID attackerId = attacker != null && m.isMember(attacker.getUniqueId()) ? attacker.getUniqueId() : null;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("victim", victim.getUniqueId());
        if (attackerId != null) d.put("attacker", attackerId);
        d.put("cause", e.getCause().name());
        d.put("method", method);
        d.put("amount", Math.round(e.getFinalDamage() * 100) / 100.0);
        d.put("health_before", Math.round(victim.getHealth() * 100) / 100.0);
        boolean lethal = e.getFinalDamage() >= victim.getHealth();
        d.put("lethal", lethal);
        m.recorder().record(EventTypes.DAMAGE, attackerId == null ? List.of(victim.getUniqueId()) : List.of(attackerId, victim.getUniqueId()),
                m.member(victim.getUniqueId()).map(MatchMember::team).orElse(null), d);
        if (!lethal) return;
        e.setCancelled(true);
        EliminationCause cause = attackerId == null ? EliminationCause.ENVIRONMENT
                : attackerId.equals(victim.getUniqueId()) ? EliminationCause.SUICIDE : EliminationCause.KILLED;
        if (cause == EliminationCause.KILLED) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("killer", attackerId);
            k.put("victim", victim.getUniqueId());
            k.put("weapon", attacker.getInventory().getItemInMainHand().getType().getKey().asString());
            k.put("method", method);
            k.put("projectile", e instanceof EntityDamageByEntityEvent ed && ed.getDamager() instanceof Projectile);
            k.put("location", BombController.loc(victim.getLocation()));
            m.recorder().record(EventTypes.PLAYER_KILL, List.of(attackerId, victim.getUniqueId()),
                    m.member(attackerId).map(MatchMember::team).orElse(null), k);
            attacker.playSound(attacker.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
        m.setKillMethod(victim.getUniqueId(), method);
        m.flow().eliminate(victim.getUniqueId(), attackerId, cause);
    }

    /** Fallback if something kills a participant anyway (e.g. /kill): eliminate and respawn as spectator. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m == null || !arenas.containsKey(m.id())) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
        m.flow().eliminate(p.getUniqueId(), null, EliminationCause.ENVIRONMENT);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        arenaOf(e.getPlayer()).ifPresent(a -> {
            Location anchor = a.spectatorAnchor(e.getPlayer().getUniqueId()).orElse(a.spawnFor(e.getPlayer().getUniqueId(), 0));
            e.setRespawnLocation(anchor);
            Bukkit.getScheduler().runTask(plugin, () -> a.spectators().onEliminated(e.getPlayer(), anchor));
        });
    }

    /** Map pickups: only alive participants of the match pick them up; they never merge with other items. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        MatchArena a = arenaOfWorld(e.getItem().getWorld()).orElse(null);
        if (a == null || !a.isPickup(e.getItem())) return;
        if (!(e.getEntity() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        boolean alive = m != null && arenas.get(m.id()) == a
                && m.flow().participant(p.getUniqueId()).map(dev.tactical.engine.match.Participant::active).orElse(false);
        if (!alive) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerge(org.bukkit.event.entity.ItemMergeEvent e) {
        MatchArena a = arenaOfWorld(e.getEntity().getWorld()).orElse(null);
        if (a != null && (a.isPickup(e.getEntity()) || a.isPickup(e.getTarget()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null) return;
        e.setCancelled(true); // items never leave a participant (Phase 3 defines tradeable items)
        if (a.bomb().isBombItem(e.getItemDrop().getItemStack())) {
            // A cancelled drop puts the item back into the inventory AFTER this handler, so drop on the next tick.
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> a.bomb().onManualDrop(p));
        }
    }

    // ------------------------------------------------------------------ projectiles

    /** No shooting or throwing outside a live round. Bows and crossbows can still be drawn/charged. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(org.bukkit.event.entity.EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m != null && (!m.state().isRoundActive() || m.flow().isPaused())) {
            e.setCancelled(true);
            p.updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m != null && (!m.state().isRoundActive() || m.flow().isPaused())) {
            e.setCancelled(true);
            p.updateInventory();
        }
    }

    /** Projectiles pass straight through teammates (and dead players) instead of stopping on them. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getHitEntity() instanceof Player hit) || !(e.getEntity().getShooter() instanceof Player shooter)) return;
        if (hit == shooter || !config.general().projectilesPassTeammates()) return;
        GameMatch m = matches.matchOf(shooter.getUniqueId()).orElse(null);
        if (m == null || !m.isMember(hit.getUniqueId())) return;
        MatchMember a = m.member(shooter.getUniqueId()).orElse(null);
        MatchMember b = m.member(hit.getUniqueId()).orElse(null);
        boolean hitAlive = m.flow().participant(hit.getUniqueId()).map(Participant::active).orElse(false);
        if (a != null && b != null && (a.team() == b.team() || !hitAlive)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        MatchArena a = arenaOf(p).orElse(null);
        if (a == null) return;
        boolean foreignTop = e.getView().getTopInventory().getType() != InventoryType.CRAFTING;
        boolean touchesBomb = a.bomb().isBombItem(e.getCurrentItem()) || a.bomb().isBombItem(e.getCursor())
                || (e.getHotbarButton() >= 0 && a.bomb().isBombItem(p.getInventory().getItem(e.getHotbarButton())));
        if (touchesBomb && (foreignTop || e.getSlotType() == InventoryType.SlotType.CRAFTING)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        arenaOf(e.getPlayer()).ifPresent(a -> {
            if (a.bomb().isBombItem(e.getItem())) e.setCancelled(true);
        });
    }

    /**
     * Construction blocks are infested stone; vanilla spawns a silverfish when one breaks (or explodes).
     * Silverfish never belong in this game, so those spawns are cancelled everywhere on the server.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSilverfish(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (e.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK) e.setCancelled(true);
    }

    /** Items and armor never lose durability in matches. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(org.bukkit.event.player.PlayerItemDamageEvent e) {
        if (matches.matchOf(e.getPlayer().getUniqueId()).isPresent()) e.setCancelled(true);
    }

    /** Map geometry is protected in match instances (construction blocks come in Phase 3). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (MapWorlds.isInstance(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (MapWorlds.isInstance(e.getBlock().getWorld())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ spectating

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStartSpectating(PlayerStartSpectatingEntityEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null || !a.spectators().isSpectating(e.getPlayer().getUniqueId())) return;
        UUID target = e.getNewSpectatorTarget().getUniqueId();
        if (!a.spectators().mayWatch(e.getPlayer().getUniqueId(), target)) e.setCancelled(true); // never enemies
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null || !a.spectators().isSpectating(e.getPlayer().getUniqueId())) return;
        Entity current = e.getSpectatorTarget();
        if (current instanceof Player t && a.spectators().mayWatch(e.getPlayer().getUniqueId(), t.getUniqueId())) {
            e.setCancelled(true); // sneak pressed: keep watching, move to the next teammate instead
            logInput(e.getPlayer(), "STOP_SPECTATING(sneak)");
            Bukkit.getScheduler().runTask(plugin, () -> a.spectators().cycle(e.getPlayer(), 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpectatorInteract(PlayerInteractEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null || !a.spectators().isSpectating(e.getPlayer().getUniqueId())) return;
        logInput(e.getPlayer(), "INTERACT(" + e.getAction() + ")");
        if (debounce(e.getPlayer())) return;
        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        a.spectators().cycle(e.getPlayer(), left ? -1 : 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpectatorSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null || !a.spectators().isSpectating(e.getPlayer().getUniqueId())) return;
        logInput(e.getPlayer(), "SWING(" + e.getHand() + ")");
        if (debounce(e.getPlayer())) return;
        a.spectators().cycle(e.getPlayer(), -1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpectatorScroll(PlayerItemHeldEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null || !a.spectators().isSpectating(e.getPlayer().getUniqueId())) return;
        logInput(e.getPlayer(), "HOTBAR(" + e.getPreviousSlot() + "->" + e.getNewSlot() + ")");
    }

    /** Spectator-menu teleports would let dead players fly to enemies: blocked for participants. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (matches.matchOf(e.getPlayer().getUniqueId()).isPresent()) e.setCancelled(true);
    }

    /** Dead players without a teammate to watch are held at their anchor (they may look around). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        MatchArena a = arenaOf(e.getPlayer()).orElse(null);
        if (a == null) return;
        UUID id = e.getPlayer().getUniqueId();
        if (!a.spectators().isSpectating(id) || a.spectators().target(id).isPresent()) return;
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ()) {
            Location back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw());
            back.setPitch(e.getTo().getPitch());
            e.setTo(back);
        }
    }

    private boolean debounce(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastSpectatorInput.put(p.getUniqueId(), now);
        return last != null && now - last < 150;
    }

    private void logInput(Player p, String what) {
        if (config.general().logSpectatorInput()) log.info("[spectator-input] " + p.getName() + " " + what);
    }

}
