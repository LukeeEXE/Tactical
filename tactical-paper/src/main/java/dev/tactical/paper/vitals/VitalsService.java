package dev.tactical.paper.vitals;

import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Hunger is not a mechanic in this game (architecture §5.9):
 * <ul>
 *   <li>Every player has a permanent, hidden Hunger effect so the client draws the Hunger-variant food sprites,
 *       which the resource pack makes transparent (the bar disappears; normal food textures are untouched).</li>
 *   <li>Food is locked (never drains, so sprint is never lost) and eating is disabled.</li>
 *   <li>Vanilla regeneration is off in match worlds; health regenerates through this service instead.</li>
 * </ul>
 * Exception: creative-mode players with {@code tactical.bypass.hunger} (builders keep a normal HUD).
 */
public final class VitalsService implements Service, Listener {

    public static final String BYPASS = "tactical.bypass.hunger";
    private static final PotionEffect HUNGER = new PotionEffect(PotionEffectType.HUNGER, PotionEffect.INFINITE_DURATION, 0,
            false, false, false);

    private final Plugin plugin;
    private final ConfigService config;
    private final Predicate<Player> mayRegenerate;
    private final Map<UUID, Long> lastDamage = new HashMap<>();
    private BukkitTask task;
    private long ticks;
    private boolean applying;

    /** @param mayRegenerate false for players who must not heal right now (dead participants, paused matches) */
    public VitalsService(Plugin plugin, ConfigService config, Predicate<Player> mayRegenerate) {
        this.plugin = plugin;
        this.config = config;
        this.mayRegenerate = mayRegenerate;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getOnlinePlayers().forEach(this::apply);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
    }

    private boolean bypass(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission(BYPASS);
    }

    /** Ensures the hidden Hunger effect and locked food. Cheap; safe to call often. */
    public void apply(Player p) {
        if (bypass(p)) {
            if (p.hasPotionEffect(PotionEffectType.HUNGER)) remove(p);
        } else if (!p.hasPotionEffect(PotionEffectType.HUNGER)) {
            applying = true;
            try {
                p.addPotionEffect(HUNGER);
            } finally {
                applying = false;
            }
        }
        if (p.getFoodLevel() != config.general().lockedFoodLevel()) p.setFoodLevel(config.general().lockedFoodLevel());
        if (p.getSaturation() != 0f) p.setSaturation(0f);
    }

    private void remove(Player p) {
        applying = true;
        try {
            p.removePotionEffect(PotionEffectType.HUNGER);
        } finally {
            applying = false;
        }
    }

    private void tick() {
        ticks++;
        var g = config.general();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setExhaustion(0f);
            if (ticks % 100 == 0) apply(p); // safety net for anything that slipped past the events
            if (!g.regenEnabled() || ticks % g.regenIntervalTicks() != 0) continue;
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || !mayRegenerate.test(p)) continue;
            Long last = lastDamage.get(p.getUniqueId());
            if (last != null && ticks - last < g.regenCombatDelayTicks()) continue;
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            double cap = max != null ? max.getValue() : 20.0;
            if (p.getHealth() >= cap) continue;
            EntityRegainHealthEvent ev = new EntityRegainHealthEvent(p, g.regenAmount(), EntityRegainHealthEvent.RegainReason.CUSTOM);
            Bukkit.getPluginManager().callEvent(ev);
            if (!ev.isCancelled()) p.setHealth(Math.min(cap, p.getHealth() + ev.getAmount()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && !bypass(p)) {
            e.setCancelled(true);
            p.setFoodLevel(config.general().lockedFoodLevel());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (!bypass(e.getPlayer()) && e.getItem().getType().isEdible()) e.setCancelled(true); // players get no food
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (applying || !(e.getEntity() instanceof Player p) || bypass(p)) return;
        if (e.getModifiedType().equals(PotionEffectType.HUNGER)
                && (e.getAction() == EntityPotionEffectEvent.Action.REMOVED || e.getAction() == EntityPotionEffectEvent.Action.CLEARED
                || e.getAction() == EntityPotionEffectEvent.Action.CHANGED)) {
            e.setCancelled(true); // milk, /effect clear, death, other plugins
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) lastDamage.put(p.getUniqueId(), ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> apply(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> apply(e.getPlayer()));
    }
}
