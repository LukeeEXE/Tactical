package dev.tactical.paper.arena;

import dev.tactical.engine.bomb.DefuseProgress;
import dev.tactical.engine.bomb.PlantProgress;
import dev.tactical.engine.map.BombSite;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.mode.BombRules;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.record.RecordingSink;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.Side;
import dev.tactical.paper.match.GameMatch;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative bomb: carrier, drop/pickup, plant (hold use inside a site), planted bomb with indicator ring,
 * and defuse (sneak + completely stationary within the radius, checkpoint owned by the bomb).
 *
 * <p>The bomb ITEM is only a visual/input device: who carries the bomb is stored here, never inferred from items.
 * Sounds are vanilla placeholders until the Phase 5 sound registry.
 */
public final class BombController {

    /** Squared per-tick movement (blocks²) still counted as "stationary" (absorbs float noise). */
    private static final double STATIONARY_EPSILON_SQ = 0.0004;
    private static final double SITE_VERTICAL_TOLERANCE = 2.5;
    /** Hotbar slot of the bomb (slot 9 = index 8 is the shop during the buy phase). */
    public static final int BOMB_SLOT = 7;

    enum DefuseStop { RELEASED_SNEAK, MOVED, LEFT_RADIUS, DIED, DISCONNECTED, DAMAGED, NOT_ON_GROUND }

    private final GameMatch match;
    private final MatchFlow flow;
    private final World world;
    private final Map<String, BombSite> sites;
    private final BombRules rules;
    private final ModeRules mode;
    private final NamespacedKey itemKey;

    private UUID carrier;
    private Location carrierLast;
    private Location dropped;
    private ItemDisplay droppedDisplay;
    /** Glowing copies, visible only to the attacking team (the plain ones are shown to everyone else). */
    private ItemDisplay droppedGlow;
    private ItemDisplay plantedGlow;
    private Location planted;
    private ItemDisplay plantedDisplay;
    private PlantProgress plant;
    private DefuseProgress defuse;
    private final Map<UUID, Location> lastPositions = new HashMap<>();
    private final Map<UUID, Long> lastDamaged = new HashMap<>();
    private long ticks;

    BombController(GameMatch match, World world, Map<String, BombSite> sites, NamespacedKey itemKey) {
        this.match = match;
        this.flow = match.flow();
        this.world = world;
        this.sites = sites;
        this.mode = match.rules();
        this.rules = match.rules().bomb();
        this.itemKey = itemKey;
    }

    // ------------------------------------------------------------------ item

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.TNT);
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(3600f) // never completes: progress is counted server-side
                .animation(ItemUseAnimation.BLOCK)
                .hasConsumeParticles(false)
                .sound(Key.key("minecraft", "intentionally_empty"))
                .build());
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString("tactical:bomb").build());
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        item.editMeta(m -> {
            m.displayName(Component.text("Bomb", NamedTextColor.RED).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Hold right-click inside a bomb site to plant.", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "bomb");
        });
        return item;
    }

    public boolean isBombItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && "bomb".equals(item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    // ------------------------------------------------------------------ round lifecycle

    /** New round: remove everything and give the bomb to a random alive attacker (real players preferred). */
    void onRoundStart() {
        removeEntities();
        carrier = null;
        dropped = null;
        planted = null;
        plant = new PlantProgress(mode.plantTicks());
        defuse = null;
        lastPositions.clear();
        List<Participant> attackers = new ArrayList<>();
        List<Participant> realAttackers = new ArrayList<>();
        for (Participant p : flow.participants()) {
            if (p.active() && flow.sideOf(p.team()) == Side.ATTACKERS) {
                attackers.add(p);
                if (Bukkit.getPlayer(p.id()) != null) realAttackers.add(p);
            }
        }
        List<Participant> pool = realAttackers.isEmpty() ? attackers : realAttackers;
        if (pool.isEmpty()) return;
        Participant chosen = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        giveTo(chosen.id(), "assigned");
    }

    void onRoundEnd() {
        if (plant != null) plant.interrupt();
        Player c = carrier == null ? null : Bukkit.getPlayer(carrier);
        if (c != null) c.clearActiveItem();
    }

    void removeEntities() {
        removeDropped();
        removePlanted();
    }

    private void removeDropped() {
        if (droppedDisplay != null) droppedDisplay.remove();
        if (droppedGlow != null) droppedGlow.remove();
        droppedDisplay = null;
        droppedGlow = null;
    }

    private void removePlanted() {
        if (plantedDisplay != null) plantedDisplay.remove();
        if (plantedGlow != null) plantedGlow.remove();
        plantedDisplay = null;
        plantedGlow = null;
    }

    /** A bomb model; the glowing one is hidden from everyone by default and shown to attackers by {@link #syncGlow}. */
    private ItemDisplay bombDisplay(Location at, float scale, boolean glow) {
        return world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(createItem());
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            if (glow) {
                d.setGlowing(true);
                d.setGlowColorOverride(Color.RED);
            }
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale), new AxisAngle4f()));
        });
    }

    /** Attackers (alive or spectating) see the glowing bomb; defenders and anyone else see the plain one. */
    private void syncGlow() {
        if (droppedDisplay == null && plantedDisplay == null) return;
        org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(BombController.class);
        dev.tactical.engine.team.TeamId attackers = flow.teamOn(dev.tactical.engine.team.Side.ATTACKERS);
        for (Player p : world.getPlayers()) {
            boolean attacker = match.member(p.getUniqueId()).map(m -> m.team() == attackers).orElse(false);
            for (ItemDisplay[] pair : new ItemDisplay[][]{{droppedDisplay, droppedGlow}, {plantedDisplay, plantedGlow}}) {
                if (pair[0] == null || pair[1] == null) continue;
                ItemDisplay shown = attacker ? pair[1] : pair[0];
                ItemDisplay hidden = attacker ? pair[0] : pair[1];
                if (!p.canSee(shown)) p.showEntity(plugin, shown);
                if (p.canSee(hidden)) p.hideEntity(plugin, hidden);
            }
        }
    }

    private void giveTo(UUID id, String reason) {
        carrier = id;
        dropped = null;
        removeDropped();
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            removeBombItems(p);
            ItemStack existing = p.getInventory().getItem(BOMB_SLOT);
            if (existing != null && !existing.getType().isAir()) p.getInventory().addItem(existing);
            p.getInventory().setItem(BOMB_SLOT, createItem());
            carrierLast = p.getLocation();
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
        }
        match.recorder().record(reason.equals("assigned") ? EventTypes.BOMB_ASSIGN : EventTypes.BOMB_PICKUP, List.of(id),
                match.member(id).map(m -> m.team()).orElse(null), loc(p == null ? null : p.getLocation()));
    }

    /** Drops the bomb where the carrier stands (death, disconnect, Q). */
    void dropFromCarrier(String reason) {
        if (carrier == null) return;
        UUID id = carrier;
        Player p = Bukkit.getPlayer(id);
        Location at = p != null && p.getWorld().equals(world) ? p.getLocation() : carrierLast;
        if (p != null) {
            p.clearActiveItem();
            removeBombItems(p);
        }
        carrier = null;
        if (plant != null) plant.interrupt();
        if (at == null) return; // virtual carrier: the bomb simply leaves play with them
        if (reason.equals("dropped") && p != null) {
            // Thrown a short way forward, and the dropper cannot pick it straight back up.
            at = throwTarget(p);
            noPickupUntil.put(id, ticks + DROP_PICKUP_DELAY_TICKS);
        }
        dropAt(at, id, reason);
    }

    private static final long DROP_PICKUP_DELAY_TICKS = 40;
    private final Map<UUID, Long> noPickupUntil = new HashMap<>();

    /** Up to 1.5 blocks ahead of the player (horizontally), stopping before walls. */
    private Location throwTarget(Player p) {
        Location eye = p.getLocation();
        org.bukkit.util.Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) return eye;
        dir.normalize();
        Location best = eye.clone();
        for (double d = 0.25; d <= 1.5; d += 0.25) {
            Location t = eye.clone().add(dir.clone().multiply(d));
            if (!world.getBlockAt(t).isPassable() || !world.getBlockAt(t.clone().add(0, 1, 0)).isPassable()) break;
            best = t;
        }
        return best;
    }

    private void dropAt(Location at, UUID by, String reason) {
        Location ground = groundBelow(at);
        dropped = ground;
        removeDropped();
        droppedDisplay = bombDisplay(ground.clone().add(0, 0.25, 0), 0.5f, false);
        droppedGlow = bombDisplay(ground.clone().add(0, 0.25, 0), 0.5f, true);
        syncGlow();
        Map<String, Object> data = loc(ground);
        data.put("reason", reason);
        match.recorder().record(EventTypes.BOMB_DROP, List.of(by), match.member(by).map(m -> m.team()).orElse(null), data);
    }

    // ------------------------------------------------------------------ per tick

    void markDamaged(UUID id) {
        lastDamaged.put(id, ticks);
    }

    void tick() {
        ticks++;
        MatchState s = flow.state();
        if (s == MatchState.BUY_PHASE || s == MatchState.ROUND_LIVE) {
            tickCarrier(s);
            tickPickup();
        }
        if (s == MatchState.BOMB_PLANTED && planted != null) {
            tickDefuse();
            if (ticks % 10 == 0) drawRing();
            if (ticks % beepInterval() == 0) {
                world.playSound(planted, Sound.BLOCK_NOTE_BLOCK_HAT, 1.5f, 1.6f);
                match.bus().publish(new dev.tactical.paper.match.MatchEvents.BombBeep());
            }
        }
        for (Player p : world.getPlayers()) lastPositions.put(p.getUniqueId(), p.getLocation());
        if (ticks % 10 == 0) syncGlow(); // newcomers, reconnects, spectators
    }

    private void tickCarrier(MatchState s) {
        if (carrier == null) return;
        Player p = Bukkit.getPlayer(carrier);
        Participant part = flow.participant(carrier).orElse(null);
        if (part == null || !part.active()) {
            dropFromCarrier("carrier_eliminated");
            return;
        }
        if (p == null) return; // virtual carrier
        carrierLast = p.getLocation();
        if (!hasBombItem(p)) p.getInventory().addItem(createItem()); // restore if something removed it
        boolean holding = s == MatchState.ROUND_LIVE && p.isHandRaised() && isBombItem(p.getActiveItem());
        Optional<BombSite> site = siteAt(p.getLocation());
        if (holding && site.isPresent() && onGround(p)) {
            PlantProgress.Step step = plant.tick(carrier, site.get().id());
            switch (step) {
                case STARTED -> {
                    Map<String, Object> d = loc(p.getLocation());
                    d.put("site", site.get().id());
                    match.recorder().record(EventTypes.PLANT_START, List.of(carrier), part.team(), d);
                    world.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 1.2f);
                }
                case COMPLETED -> completePlant(p, site.get());
                default -> { }
            }
            if (step != PlantProgress.Step.COMPLETED) {
                dev.tactical.paper.hud.ActionBars.status(p, progressBar("Planting", plantFraction(), NamedTextColor.RED));
            }
        } else if (plant.planter().isPresent()) {
            String why = !holding ? "released" : site.isEmpty() ? "left_site" : "not_on_ground";
            plant.interrupt();
            match.recorder().record(EventTypes.PLANT_CANCEL, List.of(carrier), part.team(), Map.of("reason", why));
            dev.tactical.paper.hud.ActionBars.status(p, Component.text("Plant cancelled", NamedTextColor.GRAY));
        }
    }

    private double plantFraction() {
        return plant.fraction();
    }

    private void completePlant(Player p, BombSite site) {
        UUID planter = p.getUniqueId();
        Location at = groundBelow(p.getLocation());
        if (!flow.bombPlanted(planter, site.id())) return; // engine rejected (state changed this tick)
        p.clearActiveItem();
        removeBombItems(p);
        carrier = null;
        planted = at.clone().add(0, 0, 0);
        defuse = new DefuseProgress(mode.defuseTicks(), mode.defuseCheckpointTicks());
        removePlanted();
        plantedDisplay = bombDisplay(at.clone().add(0, 0.2, 0), 0.6f, false);
        plantedGlow = bombDisplay(at.clone().add(0, 0.2, 0), 0.6f, true);
        syncGlow();
        Map<String, Object> d = loc(at);
        d.put("site", site.id());
        match.recorder().record(EventTypes.BOMB_PLANT_LOCATION, List.of(planter), match.member(planter).map(m -> m.team()).orElse(null), d);
        world.playSound(at, Sound.ENTITY_TNT_PRIMED, 2f, 0.8f);
    }

    private void tickPickup() {
        if (dropped == null || carrier != null) return;
        Player best = null;
        double bestD = rules.pickupRadius() * rules.pickupRadius();
        for (Participant part : flow.participants()) {
            if (!part.active() || flow.sideOf(part.team()) != Side.ATTACKERS) continue;
            Player p = Bukkit.getPlayer(part.id());
            if (p == null || !p.getWorld().equals(world)) continue;
            Long blocked = noPickupUntil.get(p.getUniqueId());
            if (blocked != null && ticks < blocked) continue;
            double dx = p.getX() - dropped.getX(), dz = p.getZ() - dropped.getZ();
            double d = dx * dx + dz * dz;
            if (d <= bestD && Math.abs(p.getY() - dropped.getY()) <= 1.5) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) giveTo(best.getUniqueId(), "picked_up");
    }

    private void tickDefuse() {
        if (defuse == null || defuse.completed()) return;
        Optional<UUID> current = defuse.defuser();
        if (current.isPresent()) {
            Optional<DefuseStop> stop = defuseBlocker(current.get());
            if (stop.isEmpty()) {
                step(current.get(), defuse.tick(current.get()));
                return;
            }
            DefuseProgress.Step s = defuse.interrupt();
            if (s == DefuseProgress.Step.INTERRUPTED) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("reason", stop.get().name());
                d.put("progress_ticks", defuse.progressTicks());
                d.put("checkpoint_reached", defuse.checkpointReached());
                match.recorder().record(EventTypes.DEFUSE_CANCEL, List.of(current.get()), team(current.get()), d);
                Player p = Bukkit.getPlayer(current.get());
                if (p != null) dev.tactical.paper.hud.ActionBars.status(p, Component.text("Defuse interrupted (" + stop.get().name().toLowerCase().replace('_', ' ') + ")"
                        + (defuse.checkpointReached() ? " - checkpoint kept" : ""), NamedTextColor.GRAY));
            }
        }
        // Pick the nearest defender currently in a valid stance.
        List<Player> candidates = new ArrayList<>();
        for (Participant part : flow.participants()) {
            if (!part.active() || flow.sideOf(part.team()) != Side.DEFENDERS) continue;
            Player p = Bukkit.getPlayer(part.id());
            if (p != null && defuseBlocker(part.id()).isEmpty()) candidates.add(p);
        }
        candidates.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(planted)))
                .ifPresent(p -> step(p.getUniqueId(), defuse.tick(p.getUniqueId())));
    }

    private void step(UUID id, DefuseProgress.Step s) {
        Player p = Bukkit.getPlayer(id);
        switch (s) {
            case STARTED -> {
                Map<String, Object> d = loc(p == null ? null : p.getLocation());
                d.put("from_ticks", defuse.progressTicks() - 1);
                match.recorder().record(EventTypes.DEFUSE_START, List.of(id), team(id), d);
                world.playSound(planted, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 1.4f);
            }
            case CHECKPOINT -> {
                match.recorder().record(EventTypes.DEFUSE_CHECKPOINT, List.of(id), team(id),
                        Map.of("progress_ticks", defuse.progressTicks()), "OK", RecordingSink.Priority.CRITICAL);
                world.playSound(planted, Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 1.8f);
            }
            case COMPLETED -> {
                if (flow.bombDefused(id)) world.playSound(planted, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 1.2f);
                return;
            }
            default -> { }
        }
        if (defuse.progressTicks() % 20 == 0) {
            match.recorder().record(EventTypes.DEFUSE_PROGRESS, List.of(id), team(id), Map.of("progress_ticks", defuse.progressTicks()),
                    "OK", RecordingSink.Priority.LOW);
        }
        if (p != null) dev.tactical.paper.hud.ActionBars.status(p, progressBar("Defusing", defuse.fraction(), NamedTextColor.AQUA)
                .append(Component.text(defuse.checkpointReached() ? "  (checkpoint)" : "", NamedTextColor.YELLOW)));
    }

    /** Why this defender cannot defuse right now (empty = valid stance this tick). */
    private Optional<DefuseStop> defuseBlocker(UUID id) {
        Participant part = flow.participant(id).orElse(null);
        Player p = Bukkit.getPlayer(id);
        if (part == null || !part.connected() || p == null) return Optional.of(DefuseStop.DISCONNECTED);
        if (!part.alive()) return Optional.of(DefuseStop.DIED);
        if (!p.getWorld().equals(world)) return Optional.of(DefuseStop.LEFT_RADIUS);
        double dx = p.getX() - planted.getX(), dz = p.getZ() - planted.getZ();
        if (dx * dx + dz * dz > rules.defuseRadius() * rules.defuseRadius() || Math.abs(p.getY() - planted.getY()) > SITE_VERTICAL_TOLERANCE) {
            return Optional.of(DefuseStop.LEFT_RADIUS);
        }
        if (!p.isSneaking()) return Optional.of(DefuseStop.RELEASED_SNEAK);
        if (!onGround(p)) return Optional.of(DefuseStop.NOT_ON_GROUND);
        Location last = lastPositions.get(id);
        if (last == null || !last.getWorld().equals(p.getWorld()) || last.distanceSquared(p.getLocation()) > STATIONARY_EPSILON_SQ) {
            return Optional.of(DefuseStop.MOVED);
        }
        if (rules.interruptDefuseOnDamage()) {
            Long dmg = lastDamaged.get(id);
            if (dmg != null && ticks - dmg <= 1) return Optional.of(DefuseStop.DAMAGED);
        }
        return Optional.empty();
    }

    void onDetonated() {
        if (planted == null) return;
        world.spawnParticle(Particle.EXPLOSION_EMITTER, planted, 3);
        world.playSound(planted, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f);
        if (rules.detonationKillRadius() > 0) {
            double r2 = rules.detonationKillRadius() * rules.detonationKillRadius();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(planted) <= r2 && flow.participant(p.getUniqueId()).map(Participant::active).orElse(false)) {
                    flow.eliminate(p.getUniqueId(), null, dev.tactical.engine.match.EliminationCause.BOMB);
                }
            }
        }
        removePlanted();
    }

    // ------------------------------------------------------------------ helpers

    private void drawRing() {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 1.0f);
        int points = (int) Math.max(24, Math.round(rules.defuseRadius() * 12));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            world.spawnParticle(Particle.DUST, planted.getX() + Math.cos(a) * rules.defuseRadius(), planted.getY() + 0.1,
                    planted.getZ() + Math.sin(a) * rules.defuseRadius(), 1, dust);
        }
    }

    private int beepInterval() {
        double left = flow.stateTicksRemaining() / (double) Math.max(1, mode.bombTicks());
        return left > 0.5 ? 20 : left > 0.2 ? 10 : 5;
    }

    Optional<BombSite> siteAt(Location l) {
        for (BombSite s : sites.values()) {
            if (s.region().contains(l.getX(), l.getY(), l.getZ())) return Optional.of(s);
        }
        return Optional.empty();
    }

    private Location groundBelow(Location l) {
        Location g = l.clone();
        g.setX(l.getBlockX() + 0.5);
        g.setZ(l.getBlockZ() + 0.5);
        int y = l.getBlockY();
        for (int i = 0; i < 8 && world.getBlockAt(g.getBlockX(), y - 1, g.getBlockZ()).isPassable(); i++) y--;
        g.setY(y);
        g.setPitch(0);
        return g;
    }

    /**
     * Server-side ground check (Player#isOnGround is client-reported and spoofable): any block under the
     * player's hitbox corners within 0.1 blocks below the feet must be solid.
     */
    static boolean onGround(Player p) {
        Location l = p.getLocation();
        double y = l.getY() - 0.1;
        for (double dx : new double[]{-0.29, 0.29}) {
            for (double dz : new double[]{-0.29, 0.29}) {
                if (l.getWorld().getBlockAt((int) Math.floor(l.getX() + dx), (int) Math.floor(y), (int) Math.floor(l.getZ() + dz))
                        .isSolid()) return true;
            }
        }
        return false;
    }

    private boolean hasBombItem(Player p) {
        for (ItemStack i : p.getInventory().getContents()) if (isBombItem(i)) return true;
        return false;
    }

    void removeBombItems(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) if (isBombItem(contents[i])) p.getInventory().setItem(i, null);
        if (isBombItem(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    private dev.tactical.engine.team.TeamId team(UUID id) {
        return match.member(id).map(m -> m.team()).orElse(null);
    }

    static Map<String, Object> loc(Location l) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (l != null) {
            d.put("x", Math.round(l.getX() * 100) / 100.0);
            d.put("y", Math.round(l.getY() * 100) / 100.0);
            d.put("z", Math.round(l.getZ() * 100) / 100.0);
        }
        return d;
    }

    private static Component progressBar(String label, double fraction, NamedTextColor color) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 20);
        return Component.text(label + " ", color)
                .append(Component.text("|".repeat(filled), color))
                .append(Component.text("|".repeat(20 - filled), NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------------ queries

    public Optional<UUID> carrier() {
        return Optional.ofNullable(carrier);
    }

    public Optional<Location> planted() {
        return Optional.ofNullable(planted);
    }

    public Optional<DefuseProgress> defuse() {
        return Optional.ofNullable(defuse);
    }

    public boolean isDropped() {
        return dropped != null;
    }

    /** Player pressed Q with the bomb. */
    boolean onManualDrop(Player p) {
        if (carrier == null || !carrier.equals(p.getUniqueId())) return false;
        MatchState s = flow.state();
        if (s != MatchState.BUY_PHASE && s != MatchState.ROUND_LIVE) return false;
        dropFromCarrier("dropped");
        return true;
    }
}
