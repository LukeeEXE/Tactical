package dev.tactical.paper.item;

import dev.tactical.engine.match.Participant;
import dev.tactical.engine.state.MatchState;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Timed throwable potions ({@code type: throwable}).
 * <ul>
 *   <li>Right-click throws it like a snowball (with the elytra whoosh following it). On the first block or player it
 *       hits it becomes a resting item there and its fuse ({@code fuse-ticks}, 2 s) starts.</li>
 *   <li>Left-click lights the fuse in your hand ({@code prime-ticks}, 5 s); the fuse keeps running when you throw it,
 *       so a primed potion can burst in the air, or in your hand if you hold it too long.</li>
 *   <li>While the fuse burns the item shows fuse frames 1-5 (custom_model_data float 0) over the fuse's length.</li>
 *   <li>The burst: one of three explosion sounds, a dust cloud in the item's colour, and the effects on every player
 *       in {@code radius} blocks in line of sight: opponents of the thrower for a negative item, teammates (and the
 *       thrower) for a positive one. The thrower hears one "pling" if it hit anyone.</li>
 * </ul>
 */
public final class ThrowableService implements Listener {

    private static final String FUSE_SHORT = "tactical:throwable.fuse_2s", FUSE_LONG = "tactical:throwable.fuse_5s";
    private static final String EXPLODE = "tactical:throwable.explode";
    private static final double THROW_SPEED = 1.5;
    private static final String ELYTRA = "minecraft:item.elytra.flying";

    private final Plugin plugin;
    private final MatchService matches;
    private final ItemService items;
    private BukkitTask task;
    private long now;

    /** A lit potion still in a player's inventory (left-click). */
    private record Primed(ItemDefinition def, int slot, long lit) {
    }

    /** A thrown potion: flying (snowball) or resting (item); {@code fuseStart}/{@code fuseEnd} = -1 while not lit. */
    private static final class Live {
        final ItemDefinition def;
        final UUID thrower;
        final ItemStack look;
        Snowball flying;
        Item resting;
        long fuseStart = -1, fuseEnd = -1;

        Live(ItemDefinition def, UUID thrower, ItemStack look) {
            this.def = def;
            this.thrower = thrower;
            this.look = look;
        }

        Location location() {
            return flying != null && flying.isValid() ? flying.getLocation() : resting != null && resting.isValid() ? resting.getLocation() : null;
        }

        void remove() {
            if (flying != null && flying.isValid()) flying.remove();
            if (resting != null && resting.isValid()) resting.remove();
        }
    }

    private final Map<UUID, Primed> primed = new HashMap<>();
    private final List<Live> live = new ArrayList<>();

    ThrowableService(Plugin plugin, MatchService matches, ItemService items) {
        this.plugin = plugin;
        this.matches = matches;
        this.items = items;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Live l : live) l.remove();
        live.clear();
        primed.clear();
    }

    // ------------------------------------------------------------------ input

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        ItemDefinition d = items.definitionOf(item).orElse(null);
        if (d == null || d.throwable() == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (liveMatchOf(p) == null) return;
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            if (primed.containsKey(p.getUniqueId())) return;
            primed.put(p.getUniqueId(), new Primed(d, p.getInventory().getHeldItemSlot(), now));
            p.getWorld().playSound(p, FUSE_LONG, SoundCategory.PLAYERS, 1f, 1f);
            setFrame(item, 1);
        } else if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            throwFrom(p, d, item);
        }
    }

    private void throwFrom(Player p, ItemDefinition d, ItemStack item) {
        Primed pr = primed.remove(p.getUniqueId());
        if (pr != null) stopSound(p.getWorld(), FUSE_LONG); // the lit fuse now burns on the thrown potion
        ItemStack look = item.clone();
        look.setAmount(1);
        Snowball ball = p.launchProjectile(Snowball.class, p.getLocation().getDirection().multiply(THROW_SPEED));
        if (!ball.isValid()) { // launch cancelled (e.g. outside a live round)
            if (pr != null) primed.put(p.getUniqueId(), pr);
            return;
        }
        ball.setItem(look);
        item.setAmount(item.getAmount() - 1);
        if (item.getAmount() > 0) setFrame(item, 0);
        Live l = new Live(d, p.getUniqueId(), look);
        l.flying = ball;
        if (pr != null) {
            l.fuseStart = pr.lit();
            l.fuseEnd = pr.lit() + d.throwable().primeTicks();
        }
        live.add(l);
        p.getWorld().playSound(ball, Sound.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS, 0.6f, 1.4f); // follows it
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    /** First block or (non-teammate) player hit: the potion drops there and, if not lit yet, its fuse starts. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball ball)) return;
        Live l = null;
        for (Live x : live) if (x.flying == ball) l = x;
        if (l == null) return;
        e.setCancelled(true); // no snowball knockback
        Location at = ball.getLocation();
        Vector v = ball.getVelocity();
        ball.remove();
        l.flying = null;
        stopSound(at.getWorld(), ELYTRA);
        Vector bounce = e.getHitBlockFace() != null
                ? reflect(v, e.getHitBlockFace().getDirection()).multiply(0.25)
                : v.clone().multiply(-0.1).setY(0.1);
        Location drop = at.clone().subtract(v.clone().normalize().multiply(0.15)); // a little back from the surface
        l.resting = at.getWorld().dropItem(drop, l.look.clone(), i -> {
            i.setPickupDelay(Integer.MAX_VALUE);
            i.setCanMobPickup(false);
            i.setInvulnerable(true);
            i.setPersistent(false);
            i.setUnlimitedLifetime(true);
            i.setVelocity(bounce);
        });
        if (l.fuseEnd < 0) {
            l.fuseStart = now;
            l.fuseEnd = now + l.def.throwable().fuseTicks();
            at.getWorld().playSound(l.resting, FUSE_SHORT, SoundCategory.PLAYERS, 1f, 1f);
        }
    }

    private static Vector reflect(Vector v, Vector normal) {
        Vector n = normal.clone().normalize();
        return v.clone().subtract(n.multiply(2 * v.dot(n)));
    }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        now++;
        Iterator<Map.Entry<UUID, Primed>> it = primed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Primed> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Primed pr = e.getValue();
            if (p == null || liveMatchOf(p) == null) {
                if (p != null) resetFrame(p, pr);
                it.remove();
                continue;
            }
            long total = pr.def().throwable().primeTicks();
            ItemStack held = p.getInventory().getItem(pr.slot());
            if (now - pr.lit() >= total) { // held too long: it bursts in the hand
                it.remove();
                if (held != null && pr.def().equals(items.definitionOf(held).orElse(null))) {
                    held.setAmount(held.getAmount() - 1);
                    if (held.getAmount() > 0) setFrame(held, 0);
                }
                explode(p.getLocation().add(0, 1, 0), pr.def(), p.getUniqueId());
                continue;
            }
            if (held != null && pr.def().equals(items.definitionOf(held).orElse(null))) {
                setFrame(held, frame(now - pr.lit(), total));
            }
        }
        Iterator<Live> li = live.iterator();
        while (li.hasNext()) {
            Live l = li.next();
            Location at = l.location();
            if (at == null || matches.matchOf(l.thrower).isEmpty()) { // gone, or its match ended
                l.remove();
                li.remove();
                continue;
            }
            if (l.fuseEnd < 0) continue;
            if (now >= l.fuseEnd) {
                li.remove();
                l.remove();
                if (l.flying != null) stopSound(at.getWorld(), ELYTRA);
                explode(at, l.def, l.thrower);
                continue;
            }
            int f = frame(now - l.fuseStart, l.fuseEnd - l.fuseStart);
            if (frameOf(l.look) != f) {
                setFrame(l.look, f);
                if (l.flying != null) l.flying.setItem(l.look.clone());
                if (l.resting != null) l.resting.setItemStack(l.look.clone());
            }
        }
    }

    /** Fuse frame 1-5 spread over the fuse's whole length. */
    private static int frame(long elapsed, long total) {
        return (int) Math.max(1, Math.min(5, 1 + elapsed * 5 / Math.max(1, total)));
    }

    private static int frameOf(ItemStack item) {
        CustomModelData d = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        return d == null || d.floats().isEmpty() ? 0 : Math.round(d.floats().get(0));
    }

    private static void setFrame(ItemStack item, int frame) {
        if (frameOf(item) == frame) return;
        if (frame == 0) item.unsetData(DataComponentTypes.CUSTOM_MODEL_DATA);
        else item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(frame).build());
    }

    private void resetFrame(Player p, Primed pr) {
        ItemStack held = p.getInventory().getItem(pr.slot());
        if (held != null && pr.def().equals(items.definitionOf(held).orElse(null))) setFrame(held, 0);
    }

    // ------------------------------------------------------------------ burst

    private void explode(Location at, ItemDefinition d, UUID thrower) {
        World w = at.getWorld();
        double r = d.throwable().radius();
        w.playSound(at, EXPLODE, SoundCategory.PLAYERS, 2f, 1f); // the client picks one of the three variants
        Color c = Color.fromRGB(d.color().value());
        Particle.DustOptions big = new Particle.DustOptions(c, 2.0f);
        Particle.DustOptions small = new Particle.DustOptions(c, 1.2f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 220; i++) { // a cloud filling the radius, denser in the middle
            double u = Math.cbrt(rnd.nextDouble()) * r * (i < 70 ? 0.4 : 1.0);
            Vector dir = new Vector(rnd.nextGaussian(), rnd.nextGaussian() * 0.6, rnd.nextGaussian()).normalize().multiply(u);
            w.spawnParticle(Particle.DUST, at.clone().add(dir), 1, 0, 0, 0, 0, i < 70 ? big : small, true);
        }
        w.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0, null, true);

        GameMatch m = matches.matchOf(thrower).orElse(null);
        if (m == null) return;
        MatchMember by = m.member(thrower).orElse(null);
        if (by == null) return;
        boolean positive = d.polarity() == ItemEffect.Polarity.POSITIVE;
        Player source = Bukkit.getPlayer(thrower);
        int hit = 0;
        for (MatchMember mm : m.members()) {
            Player t = Bukkit.getPlayer(mm.uuid());
            if (t == null || !t.getWorld().equals(w)) continue;
            if (!m.flow().participant(mm.uuid()).map(Participant::active).orElse(false)) continue;
            boolean teammate = mm.team() == by.team();
            if (positive != teammate) continue;
            Location body = t.getLocation().add(0, 0.9, 0);
            if (body.distance(at) > r + 0.3 || !inSight(at, t)) continue;
            for (ItemEffect e : d.effects()) e.apply(t, source != null ? source : t);
            hit++;
        }
        if (hit > 0 && source != null) {
            // one "pling" for the thrower, however many players it hit
            source.playSound(source.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1f, 1f);
        }
    }

    /** Free line from the burst to the player's eyes or feet. */
    private static boolean inSight(Location from, Player t) {
        for (Location to : new Location[]{t.getEyeLocation(), t.getLocation().add(0, 0.3, 0)}) {
            Vector dir = to.toVector().subtract(from.toVector());
            double len = dir.length();
            if (len < 0.01) return true;
            RayTraceResult hit = from.getWorld().rayTraceBlocks(from, dir.normalize(), len,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit == null) return true;
        }
        return false;
    }

    private static void stopSound(World w, String key) {
        for (Player p : w.getPlayers()) p.stopSound(key);
    }

    private GameMatch liveMatchOf(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m == null || m.flow().isPaused()) return null;
        if (m.state() != MatchState.ROUND_LIVE && m.state() != MatchState.BOMB_PLANTED) return null;
        return m.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false) ? m : null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        primed.remove(e.getPlayer().getUniqueId());
    }
}
