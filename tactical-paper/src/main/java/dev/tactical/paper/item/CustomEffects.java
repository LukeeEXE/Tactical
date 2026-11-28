package dev.tactical.paper.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * State behind the custom item effects that last over time, plus the mining fatigue change.
 * <ul>
 *   <li><b>Flash</b>: the screen goes white while the player can still move. Drawn in the world, not on the HUD (F1
 *       cannot hide it): two huge white text-display backgrounds ride on the player, are visible only to them, face
 *       the camera (billboard) and render "see-through" (on top of every block), half a block in front of the camera
 *       in first and third person. They fade out over the last second.</li>
 *   <li><b>Mining fatigue</b>: while a player has it (from anything), mining is disabled completely (block break speed
 *       0) and the attack-speed penalty of the vanilla effect is cancelled out.</li>
 * </ul>
 */
public final class CustomEffects implements Listener {

    private static CustomEffects instance;

    private final Plugin plugin;
    private final Map<UUID, Flash> flashes = new HashMap<>();
    private final NamespacedKey noMiningKey;
    private final NamespacedKey attackSpeedKey;

    /** Fade-out at the end of a flash. */
    static final int FADE_TICKS = 20;

    private static final class Flash {
        final TextDisplay[] screens;
        int ticksLeft;

        Flash(TextDisplay[] screens, int ticks) {
            this.screens = screens;
            this.ticksLeft = ticks;
        }
    }

    public CustomEffects(Plugin plugin) {
        this.plugin = plugin;
        this.noMiningKey = new NamespacedKey(plugin, "mining_fatigue_no_mining");
        this.attackSpeedKey = new NamespacedKey(plugin, "mining_fatigue_attack_speed");
        instance = this;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlashes, 1L, 1L);
    }

    static CustomEffects get() {
        return instance;
    }

    // ------------------------------------------------------------------ flash

    /** White screen for {@code ticks} (a new flash on a flashed player keeps whichever lasts longer). */
    public void flash(Player p, int ticks) {
        Flash f = flashes.get(p.getUniqueId());
        if (f != null && allValid(f)) {
            f.ticksLeft = Math.max(f.ticksLeft, ticks);
            return;
        }
        if (f != null) remove(p.getUniqueId(), f);
        // The billboard's local z axis is the camera's: one screen 0.5 in front, one 0.5 behind (whichever ends up in
        // front of the camera covers it; the other is behind the camera and not drawn).
        TextDisplay[] screens = {screen(p, -0.5f), screen(p, 0.5f)};
        flashes.put(p.getUniqueId(), new Flash(screens, ticks));
    }

    private TextDisplay screen(Player p, float z) {
        TextDisplay d = p.getWorld().spawn(p.getEyeLocation(), TextDisplay.class, t -> {
            t.text(Component.text(" "));
            t.setBackgroundColor(Color.fromARGB(255, 255, 255, 255));
            t.setDefaultBackground(false);
            t.setSeeThrough(true);
            t.setShadowed(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setViewRange(4f);
            // A " " background is about 0.1 x 0.25 blocks; x60 covers any field of view at half a block. Shifted down
            // by half its height so it is centred on the camera (text grows upwards from its origin).
            t.setTransformation(new Transformation(new Vector3f(0f, -7.5f, z), new AxisAngle4f(),
                    new Vector3f(60f, 60f, 1f), new AxisAngle4f()));
        });
        p.showEntity(plugin, d);
        p.addPassenger(d);
        return d;
    }

    public boolean isFlashed(Player p) {
        return flashes.containsKey(p.getUniqueId());
    }

    private void tickFlashes() {
        Iterator<Map.Entry<UUID, Flash>> it = flashes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Flash> e = it.next();
            Flash f = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            f.ticksLeft--;
            if (p == null || f.ticksLeft <= 0 || !allValid(f)) {
                for (TextDisplay d : f.screens) if (d.isValid()) d.remove();
                it.remove();
                continue;
            }
            for (TextDisplay d : f.screens) {
                if (!p.getPassengers().contains(d)) p.addPassenger(d); // e.g. after a teleport ejected it
            }
            if (f.ticksLeft <= FADE_TICKS) {
                int alpha = Math.max(0, Math.min(255, 255 * f.ticksLeft / FADE_TICKS));
                for (TextDisplay d : f.screens) d.setBackgroundColor(Color.fromARGB(alpha, 255, 255, 255));
            }
        }
    }

    private static boolean allValid(Flash f) {
        for (TextDisplay d : f.screens) if (!d.isValid()) return false;
        return true;
    }

    private void remove(UUID id, Flash f) {
        for (TextDisplay d : f.screens) if (d.isValid()) d.remove();
        flashes.remove(id);
    }

    public void clearFlash(Player p) {
        Flash f = flashes.get(p.getUniqueId());
        if (f != null) remove(p.getUniqueId(), f);
    }

    // ------------------------------------------------------------------ cleanse

    /** Removes a block; in a match arena this goes through its block journal (restored at the next map reset). */
    private static java.util.function.Consumer<org.bukkit.block.Block> blockClearer =
            b -> b.setType(org.bukkit.Material.AIR);

    public static void setBlockClearer(java.util.function.Consumer<org.bukkit.block.Block> clearer) {
        blockClearer = clearer;
    }

    /** Removes every potion effect and every custom effect still running, and frees the player from cobwebs. */
    public void cleanse(Player p) {
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        clearFlash(p);
        p.setFireTicks(0);
        p.setFreezeTicks(0);
        clearCobwebs(p);
    }

    /** Every cobweb the player's hitbox touches. */
    private static void clearCobwebs(Player p) {
        org.bukkit.util.BoundingBox box = p.getBoundingBox().expand(0.01);
        org.bukkit.World w = p.getWorld();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != org.bukkit.Material.COBWEB) continue;
                    org.bukkit.block.data.BlockData data = b.getBlockData();
                    blockClearer.accept(b);
                    w.spawnParticle(org.bukkit.Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, data);
                    w.playSound(b.getLocation().add(0.5, 0.5, 0.5), org.bukkit.Sound.BLOCK_WOOL_BREAK, 1f, 1f);
                }
            }
        }
    }

    // ------------------------------------------------------------------ effect sounds

    /** Effects with their own sound in the resource pack ({@code tactical:effect.<name>}). */
    private static final java.util.Set<String> SOUNDS = java.util.Set.copyOf(dev.tactical.engine.pack.PackBuilder.EFFECT_SOUNDS);
    /** Last sound per player and the tick it played: the same sound twice in one tick plays once. */
    private final Map<UUID, String> lastSound = new HashMap<>();

    /**
     * Plays the effect's sound to that player only (the files are stereo: no distance falloff). {@code effect} is a
     * vanilla effect id (instant_damage plays "harming") or a custom effect sound name. Unknown names are ignored.
     */
    public static void playEffectSound(Player p, String effect) {
        CustomEffects fx = instance;
        if (fx == null || p == null) return;
        String name = effect.equals("instant_damage") ? "harming" : effect;
        if (!SOUNDS.contains(name)) return;
        String stamp = name + "@" + Bukkit.getCurrentTick();
        if (stamp.equals(fx.lastSound.put(p.getUniqueId(), stamp))) return;
        p.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("tactical", "effect." + name),
                net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f));
    }

    /** Any vanilla effect a player gets (shots, kits, anything else) plays its sound. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffectSound(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        if (e.getNewEffect() == null) return;
        playEffectSound(p, e.getNewEffect().getType().getKey().getKey());
    }

    // ------------------------------------------------------------------ mining fatigue

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PotionEffectType t = e.getModifiedType();
        if (!PotionEffectType.MINING_FATIGUE.equals(t)) return;
        Bukkit.getScheduler().runTask(plugin, () -> syncMiningFatigue(p)); // after the change is applied
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        syncMiningFatigue(e.getPlayer());
    }

    /** Mining fatigue: no mining at all, and no attack-speed penalty (counter-modifier for the vanilla one). */
    void syncMiningFatigue(Player p) {
        if (!p.isOnline()) return;
        AttributeInstance mine = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        AttributeInstance speed = p.getAttribute(Attribute.ATTACK_SPEED);
        if (mine != null) mine.removeModifier(noMiningKey);
        if (speed != null) speed.removeModifier(attackSpeedKey);
        PotionEffect fatigue = p.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        if (fatigue == null) return;
        if (mine != null) {
            mine.addTransientModifier(new AttributeModifier(noMiningKey, -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
        if (speed != null) {
            // vanilla: attack speed x (1 - 0.1 * level); multiply back by 1 / that
            double factor = 1.0 - 0.1 * (fatigue.getAmplifier() + 1);
            if (factor > 0.01) {
                speed.addTransientModifier(new AttributeModifier(attackSpeedKey, 1.0 / factor - 1.0,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
        }
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clearFlash(e.getPlayer());
        lastSound.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        clearFlash(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        clearFlash(e.getPlayer());
    }

    /** Plugin shutdown: remove every flash screen. */
    public void shutdown() {
        for (Flash f : flashes.values()) for (TextDisplay d : f.screens) if (d.isValid()) d.remove();
        flashes.clear();
    }
}
