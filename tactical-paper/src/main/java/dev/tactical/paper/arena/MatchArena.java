package dev.tactical.paper.arena;

import dev.tactical.engine.geo.SpawnPoint;
import dev.tactical.engine.map.Barrier;
import dev.tactical.engine.map.MapDefinition;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.record.RecordingSink;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.lobby.LobbyService;
import dev.tactical.paper.map.BlockJournal;
import dev.tactical.paper.map.MapEditorService;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The physical side of one match: its private world instance, spawns, barriers, round reset, boundaries,
 * deaths → spectating, the bomb, and position sampling for recordings. Subscribes to the match's event bus and
 * is ticked by the engine (so a paused match freezes it automatically).
 */
public final class MatchArena {

    private final GameMatch match;
    private final MatchFlow flow;
    private final MapDefinition map;
    private final World world;
    private final BlockJournal journal;
    private final BombController bomb;
    private final SpectatorController spectators;
    private final LobbyService lobby;
    /** Gives a player their kit's base kit (set by the item service). */
    private final java.util.function.BiConsumer<Player, String> equipper;
    private final int positionInterval;
    private final Logger log;
    private long ticks;

    public MatchArena(GameMatch match, MapDefinition map, World world, LobbyService lobby, NamespacedKey itemKey,
                      java.util.function.BiConsumer<Player, String> equipper, int positionInterval, Logger log) {
        this.match = match;
        this.flow = match.flow();
        this.map = map;
        this.world = world;
        this.journal = new BlockJournal(world);
        this.bomb = new BombController(match, world, map.sites(), itemKey);
        this.spectators = new SpectatorController(match);
        this.lobby = lobby;
        this.equipper = equipper;
        this.positionInterval = positionInterval;
        this.log = log;
        match.bus().subscribe(MatchFlowEvent.class, this::onEvent);
        flow.addTickable(f -> tick());
    }

    public World world() {
        return world;
    }

    public MapDefinition map() {
        return map;
    }

    public BombController bomb() {
        return bomb;
    }

    public SpectatorController spectators() {
        return spectators;
    }

    /** Moves every online participant into the instance at their spawn. Called once the world is loaded. */
    public void start() {
        for (Player p : match.onlinePlayers()) placeAtSpawn(p);
    }

    // ------------------------------------------------------------------ events

    private void onEvent(MatchFlowEvent event) {
        switch (event) {
            case MatchFlowEvent.RoundStarted e -> startRound();
            case MatchFlowEvent.KitSelectionOpened e -> respawnAll();
            case MatchFlowEvent.RoundLive e -> {
                int removed = removeBarriers();
                world.playSound(world.getSpawnLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0f, 1f);
                for (Player p : match.onlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
                if (removed > 0) log.fine("[match=" + match.id() + "] removed " + removed + " barrier blocks");
            }
            case MatchFlowEvent.RoundEnded e -> bomb.onRoundEnd();
            case MatchFlowEvent.PlayerEliminated e -> onEliminated(e.victim());
            case MatchFlowEvent.BombDetonated e -> bomb.onDetonated();
            case MatchFlowEvent.PlayerReconnected e -> {
                Player p = Bukkit.getPlayer(e.player());
                if (p != null) {
                    Location anchor = spawnFor(e.player(), 0);
                    p.teleport(anchor);
                    spectators.onEliminated(p, anchor);
                }
            }
            case MatchFlowEvent.PlayerDisconnected e -> {
                if (bomb.carrier().filter(e.player()::equals).isPresent()) bomb.dropFromCarrier("carrier_disconnected");
            }
            default -> { }
        }
    }

    private void startRound() {
        int restored = journal.restoreAll();
        if (restored > 0) {
            match.recorder().record(EventTypes.BLOCK_CHANGE, List.of(), null, Map.of("action", "round_reset", "blocks", restored));
        }
        spectators.reset();
        for (Player p : match.onlinePlayers()) {
            if (flow.participant(p.getUniqueId()).map(Participant::active).orElse(false)) placeAtSpawn(p);
        }
        bomb.onRoundStart();
        spawnPickups();
    }

    /**
     * Kit selection (start, halftime, sudden death): everyone - including players who died last round - is brought
     * back to their spawn, alive, so they can right-click to (re)open the kit menu.
     */
    private void respawnAll() {
        spectators.reset();
        for (Player p : match.onlinePlayers()) {
            if (match.isMember(p.getUniqueId())) placeAtSpawn(p);
        }
    }

    // ------------------------------------------------------------------ pickups

    private final List<org.bukkit.entity.Item> pickups = new ArrayList<>();
    private final NamespacedKey pickupKey = new NamespacedKey("tactical", "pickup");

    /** (Re)spawns the map's pickup items: never despawn or merge, pick-up straight away, only by alive players. */
    private void spawnPickups() {
        removePickups();
        for (var k : map.pickups()) {
            ItemStack item = parseItem(k.item() + ":" + k.amount());
            if (item == null) {
                log.warning("[match=" + match.id() + "] map " + map.id() + ": unknown pickup item '" + k.item() + "'");
                continue;
            }
            ItemStack prepared = dev.tactical.paper.shop.MatchShop.prepare(item);
            Location at = new Location(world, k.x(), k.y(), k.z());
            pickups.add(world.dropItem(at, prepared, i -> {
                i.setVelocity(new org.bukkit.util.Vector());
                i.setPickupDelay(0);
                i.setUnlimitedLifetime(true);
                i.setCanMobPickup(false);
                i.setPersistent(false);
                i.getPersistentDataContainer().set(pickupKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            }));
        }
    }

    private void removePickups() {
        for (org.bukkit.entity.Item i : pickups) if (i.isValid()) i.remove();
        pickups.clear();
    }

    /** True if the item entity is one of this match's map pickups. */
    public boolean isPickup(org.bukkit.entity.Item item) {
        return item.getPersistentDataContainer().has(pickupKey);
    }

    private int removeBarriers() {
        int n = 0;
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Barrier b : map.barriers()) {
            int before = n;
            for (var pos : b.region()) {
                Block blk = world.getBlockAt(pos.x(), pos.y(), pos.z());
                if (MapEditorService.removableBarrierBlock(blk, b) && journal.set(pos, Material.AIR.createBlockData())) n++;
            }
            changed.add(Map.of("barrier", b.name(), "blocks", n - before));
        }
        if (n > 0) {
            match.recorder().record(EventTypes.BLOCK_CHANGE, List.of(), null, Map.of("action", "barriers_removed", "barriers", changed));
        }
        return n;
    }

    private void onEliminated(UUID victim) {
        if (bomb.carrier().filter(victim::equals).isPresent()) bomb.dropFromCarrier("carrier_eliminated");
        Player p = Bukkit.getPlayer(victim);
        if (p == null || !p.getWorld().equals(world)) return;
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.6f);
        Location anchor = p.getLocation();
        if (p.getLocation().getY() < map.voidY() + 2 || (map.bounds() != null && !map.bounds().contains(p.getX(), p.getY(), p.getZ()))) {
            anchor = spawnFor(victim, 0); // fell out: don't leave them hovering in the void
        }
        spectators.onEliminated(p, anchor);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        ticks++;
        bomb.tick();
        if (ticks % 10 == 0 && flow.state().isRoundActive()) checkBounds();
        if (ticks % positionInterval == 0) recordPositions();
    }

    private void checkBounds() {
        for (Player p : match.onlinePlayers()) {
            if (!flow.participant(p.getUniqueId()).map(Participant::active).orElse(false)) continue;
            if (!p.getWorld().equals(world)) {
                flow.eliminate(p.getUniqueId(), null, EliminationCause.OUT_OF_BOUNDS);
                continue;
            }
            boolean outside = p.getY() < map.voidY() || (map.bounds() != null && !map.bounds().contains(p.getX(), p.getY(), p.getZ()));
            if (outside) flow.eliminate(p.getUniqueId(), null, EliminationCause.OUT_OF_BOUNDS);
        }
    }

    private void recordPositions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (!match.isMember(p.getUniqueId())) continue;
            Location l = p.getLocation();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", p.getUniqueId());
            r.put("x", Math.round(l.getX() * 100) / 100.0);
            r.put("y", Math.round(l.getY() * 100) / 100.0);
            r.put("z", Math.round(l.getZ() * 100) / 100.0);
            r.put("yaw", Math.round(l.getYaw() * 10) / 10.0);
            r.put("pitch", Math.round(l.getPitch() * 10) / 10.0);
            r.put("hp", Math.round(p.getHealth() * 10) / 10.0);
            r.put("alive", flow.participant(p.getUniqueId()).map(Participant::active).orElse(false));
            if (p.isSneaking()) r.put("sneak", true);
            rows.add(r);
        }
        if (!rows.isEmpty()) {
            match.recorder().record(EventTypes.SNAPSHOT_POSITIONS, List.of(), null, Map.of("players", rows), "OK", RecordingSink.Priority.LOW);
        }
    }

    // ------------------------------------------------------------------ placement

    private void placeAtSpawn(Player p) {
        int index = indexOnSide(p.getUniqueId());
        p.teleport(spawnFor(p.getUniqueId(), index));
        LobbyService.resetPlayer(p, GameMode.ADVENTURE);
        p.getInventory().clear();
        equipper.accept(p, flow.participant(p.getUniqueId()).map(Participant::currentKit).orElse(null));
    }

    private int indexOnSide(UUID id) {
        TeamId team = match.member(id).map(MatchMember::team).orElse(TeamId.A);
        int i = 0;
        for (MatchMember m : match.members()) {
            if (m.uuid().equals(id)) return i;
            if (m.team() == team) i++;
        }
        return 0;
    }

    Location spawnFor(UUID id, int index) {
        TeamId team = match.member(id).map(MatchMember::team).orElse(TeamId.A);
        List<SpawnPoint> spawns = flow.sideOf(team) == Side.ATTACKERS ? map.attackerSpawns() : map.defenderSpawns();
        if (spawns.isEmpty()) return world.getSpawnLocation();
        SpawnPoint s = spawns.get(Math.floorMod(index, spawns.size()));
        return new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    }

    public static ItemStack parseItem(String spec) {
        String[] parts = spec.split(":");
        String id = parts[0].toLowerCase(Locale.ROOT);
        NamespacedKey key = id.contains("/") ? null : NamespacedKey.minecraft(id);
        var type = key == null ? null : Registry.ITEM.get(key);
        if (type == null) return null;
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(parts[1])));
            } catch (NumberFormatException ignored) {
                // keep 1
            }
        }
        return type.createItemStack(amount);
    }

    // ------------------------------------------------------------------ teardown

    /** Moves everyone out of the instance (participants and anyone else) and removes match entities. */
    public void evacuate() {
        bomb.removeEntities();
        removePickups();
        for (Player p : List.copyOf(world.getPlayers())) {
            try {
                // setSpectatorTarget throws for players who are not spectators (i.e. everyone still alive).
                if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
                lobby.sendToLobby(p);
            } catch (RuntimeException e) {
                log.log(java.util.logging.Level.SEVERE, "[match=" + match.id() + "] could not return " + p.getName() + " to the lobby", e);
                p.teleport(lobby.spawn()); // never leave anyone in a world that is about to be deleted
            }
        }
    }

    public Optional<Location> spectatorAnchor(UUID viewer) {
        return spectators.anchor(viewer);
    }
}
