package dev.tactical.paper.arena;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Who dealt damage and HOW (the "method" shown in kill messages: {@code chat.kill.<method>}).
 * <p>
 * Built-in methods: {@code melee}, {@code bow}, {@code trident}, {@code projectile}, {@code explosion}, {@code fire},
 * {@code magic}, {@code fall}, {@code void}, {@code thorns}, {@code generic}.
 * <p>
 * Custom utility (future items) has two ways to name its own method, e.g. {@code flashbang}:
 * <ul>
 *   <li>tag the entity that deals the damage (projectile, TNT, area cloud, display...) with {@link #tag}: the method
 *       and the owning player are stored in its persistent data;</li>
 *   <li>or, for direct {@code player.damage(...)} calls, call {@link #pending} right before dealing the damage.</li>
 * </ul>
 * A message {@code chat.kill.<method>} is used if it exists, else the generic {@code chat.kill}.
 */
public final class KillMethods {

    public static final NamespacedKey METHOD = new NamespacedKey("tactical", "kill_method");
    public static final NamespacedKey OWNER = new NamespacedKey("tactical", "owner");
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private record Pending(String method, UUID attacker, long until) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private KillMethods() {
    }

    /** Marks an entity (projectile, TNT, cloud...) so damage it deals counts as {@code method} by {@code owner}. */
    public static void tag(Entity entity, String method, Player owner) {
        entity.getPersistentDataContainer().set(METHOD, PersistentDataType.STRING, sanitize(method));
        if (owner != null) entity.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    /** The next damage {@code victim} takes within 2 ticks counts as {@code method} by {@code attacker}. */
    public static void pending(Player victim, String method, Player attacker) {
        PENDING.put(victim.getUniqueId(), new Pending(sanitize(method), attacker == null ? null : attacker.getUniqueId(),
                System.currentTimeMillis() + 100));
    }

    /** The player responsible for damage from {@code damager}: shooters, TNT igniters, cloud/fang owners, tagged owners. */
    public static Player attacker(Entity damager) {
        for (int depth = 0; damager != null && depth < 4; depth++) {
            if (damager instanceof Player p) return p;
            String owner = damager.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
            if (owner != null) {
                try {
                    return Bukkit.getPlayer(UUID.fromString(owner));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            if (damager instanceof Projectile proj) {
                damager = proj.getShooter() instanceof Entity s ? s : null;
            } else if (damager instanceof TNTPrimed tnt) {
                damager = tnt.getSource();
            } else if (damager instanceof AreaEffectCloud cloud) {
                damager = cloud.getSource() instanceof Entity s ? s : null;
            } else if (damager instanceof EvokerFangs fangs) {
                damager = fangs.getOwner();
            } else {
                return null;
            }
        }
        return null;
    }

    /** Attacker for this damage event, including a {@link #pending} attacker. */
    public static Player attacker(EntityDamageEvent e) {
        Player a = e instanceof EntityDamageByEntityEvent ed ? attacker(ed.getDamager()) : null;
        if (a != null) return a;
        Pending p = livePending(e.getEntity().getUniqueId());
        return p == null || p.attacker() == null ? null : Bukkit.getPlayer(p.attacker());
    }

    /** The method id for this damage event. */
    public static String method(EntityDamageEvent e) {
        Pending pending = livePending(e.getEntity().getUniqueId());
        if (pending != null) return pending.method();
        if (e instanceof EntityDamageByEntityEvent ed) {
            Entity d = ed.getDamager();
            for (int depth = 0; d != null && depth < 3; depth++) {
                String tagged = d.getPersistentDataContainer().get(METHOD, PersistentDataType.STRING);
                if (tagged != null) return tagged;
                d = d instanceof TNTPrimed t ? t.getSource() : null; // e.g. tagged utility that spawned the TNT
            }
            if (ed.getDamager() instanceof Trident) return "trident";
            if (ed.getDamager() instanceof AbstractArrow) return "bow";
            if (ed.getDamager() instanceof Projectile) return "projectile";
            if (ed.getDamager() instanceof TNTPrimed) return "explosion";
        }
        return switch (e.getCause()) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> "melee";
            case PROJECTILE -> "projectile";
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "explosion";
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> "fire";
            case MAGIC, POISON, WITHER, DRAGON_BREATH -> "magic";
            case FALL, FLY_INTO_WALL -> "fall";
            case VOID -> "void";
            case THORNS -> "thorns";
            default -> "generic";
        };
    }

    private static Pending livePending(UUID victim) {
        Pending p = PENDING.get(victim);
        if (p == null) return null;
        if (p.until() < System.currentTimeMillis()) {
            PENDING.remove(victim, p);
            return null;
        }
        return p;
    }

    private static String sanitize(String method) {
        String m = method == null ? "generic" : method.toLowerCase(Locale.ROOT);
        return ID.matcher(m).matches() ? m : "generic";
    }
}
