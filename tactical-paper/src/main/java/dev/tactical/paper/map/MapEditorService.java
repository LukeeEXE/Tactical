package dev.tactical.paper.map;

import dev.tactical.engine.geo.BlockPos;
import dev.tactical.engine.geo.Region;
import dev.tactical.engine.geo.SpawnPoint;
import dev.tactical.engine.map.Barrier;
import dev.tactical.engine.map.BombSite;
import dev.tactical.engine.map.MapDefinition;
import dev.tactical.engine.map.MapValidator;
import dev.tactical.paper.core.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-game map editing: per-admin edit sessions, the selection wand, particle previews of every configured element,
 * and world-level validation (things only the actual blocks can tell us).
 */
public final class MapEditorService implements Service, Listener {

    public static final String PERMISSION = "tactical.admin.maps";

    public static final class Session {
        private String mapId;
        private BlockPos pos1;
        private BlockPos pos2;
        private boolean preview = true;

        public Optional<String> mapId() {
            return Optional.ofNullable(mapId);
        }

        public Optional<Region> selection() {
            return pos1 != null && pos2 != null ? Optional.of(Region.of(pos1, pos2)) : Optional.empty();
        }

        public boolean preview() {
            return preview;
        }

        public void togglePreview() {
            preview = !preview;
        }
    }

    private final Plugin plugin;
    private final MapService maps;
    private final NamespacedKey wandKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask previewTask;

    public MapEditorService(Plugin plugin, MapService maps) {
        this.plugin = plugin;
        this.maps = maps;
        this.wandKey = new NamespacedKey(plugin, "map_wand");
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        previewTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drawPreviews, 10L, 10L);
    }

    @Override
    public void disable() {
        if (previewTask != null) previewTask.cancel();
        HandlerList.unregisterAll(this);
        sessions.clear();
    }

    public Session session(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), u -> new Session());
    }

    /** Opens the template world for editing and teleports the editor into it. */
    public World startEditing(Player p, MapDefinition def) {
        World w = maps.worlds().loadTemplate(def.id());
        Session s = session(p);
        s.mapId = def.id();
        if (!p.getWorld().equals(w)) {
            SpawnPoint sp = def.attackerSpawns().isEmpty() ? null : def.attackerSpawns().getFirst();
            if (sp != null) {
                p.teleport(new org.bukkit.Location(w, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch()));
            } else {
                if (w.getBlockAt(0, 63, 0).getType().isAir()) w.getBlockAt(0, 63, 0).setType(Material.STONE);
                p.teleport(new org.bukkit.Location(w, 0.5, 64, 0.5));
            }
        }
        p.setGameMode(org.bukkit.GameMode.CREATIVE);
        p.setFlying(true);
        return w;
    }

    public void stopEditing(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) s.mapId = null;
    }

    public ItemStack wand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        item.editMeta(m -> {
            m.displayName(Component.text("Map wand", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Left-click block: corner 1", NamedTextColor.GRAY),
                    Component.text("Right-click block: corner 2", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isWand(e.getItem()) || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!p.hasPermission(PERMISSION)) return;
        Block b = e.getClickedBlock();
        BlockPos pos = new BlockPos(b.getX(), b.getY(), b.getZ());
        Session s = session(p);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) s.pos1 = pos;
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) s.pos2 = pos;
        else return;
        String size = s.selection().map(r -> " (" + r.volume() + " blocks)").orElse("");
        p.sendActionBar(Component.text((e.getAction() == Action.LEFT_CLICK_BLOCK ? "Corner 1: " : "Corner 2: ") + pos + size,
                NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ world validation

    /** Checks that need the template's actual blocks. Template must be loaded. */
    public List<MapValidator.Problem> worldChecks(MapDefinition def, World w) {
        List<MapValidator.Problem> p = new ArrayList<>();
        for (SpawnPoint s : concat(def.attackerSpawns(), def.defenderSpawns())) {
            Block feet = w.getBlockAt(s.block().x(), s.block().y(), s.block().z());
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()) {
                p.add(new MapValidator.Problem(true, "Spawn " + s.block() + " is inside solid blocks."));
            } else if (!feet.getRelative(0, -1, 0).getType().isSolid()) {
                p.add(new MapValidator.Problem(false, "Spawn " + s.block() + " has no solid ground beneath it."));
            }
        }
        for (BombSite site : def.sites().values()) {
            if (!hasStandableSpot(w, site.region())) {
                p.add(new MapValidator.Problem(true, "Bomb site " + site.id() + " has no spot a player can stand on (accessibility)."));
            }
        }
        for (Barrier b : def.barriers()) {
            int removable = 0;
            for (BlockPos pos : b.region()) {
                if (removableBarrierBlock(w.getBlockAt(pos.x(), pos.y(), pos.z()), b)) removable++;
            }
            if (removable == 0) p.add(new MapValidator.Problem(false, "Barrier '" + b.name() + "' contains no removable blocks."));
        }
        // Reset round-trip: remove every barrier through a journal, restore it, and compare block by block.
        BlockJournal journal = new BlockJournal(w);
        Map<BlockPos, BlockData> before = new HashMap<>();
        for (Barrier b : def.barriers()) {
            for (BlockPos pos : b.region()) {
                Block blk = w.getBlockAt(pos.x(), pos.y(), pos.z());
                if (removableBarrierBlock(blk, b)) {
                    before.put(pos, blk.getBlockData().clone());
                    journal.set(pos, Material.AIR.createBlockData());
                }
            }
        }
        journal.restoreAll();
        for (Map.Entry<BlockPos, BlockData> e : before.entrySet()) {
            BlockPos pos = e.getKey();
            if (!w.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData().equals(e.getValue())) {
                p.add(new MapValidator.Problem(true, "Map reset test failed at " + pos + "."));
                break;
            }
        }
        return p;
    }

    public static boolean removableBarrierBlock(Block b, Barrier barrier) {
        if (b.getType().isAir()) return false;
        if (barrier.onlyMaterials().isEmpty()) return true;
        return barrier.onlyMaterials().contains(b.getType().getKey().asString())
                || barrier.onlyMaterials().contains(b.getType().getKey().getKey());
    }

    private static boolean hasStandableSpot(World w, Region r) {
        for (int x = r.min().x(); x <= r.max().x(); x++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int y = r.min().y(); y <= r.max().y(); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isPassable() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, -1, 0).getType().isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ previews

    private void drawPreviews() {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            Session s = e.getValue();
            if (p == null || s.mapId == null || !s.preview) continue;
            MapDefinition def = maps.map(s.mapId).orElse(null);
            if (def == null || !p.getWorld().getKey().equals(MapWorlds.templateKey(def.id()))) continue;
            int[] budget = {900};
            if (def.bounds() != null) outline(p, def.bounds(), Color.WHITE, 2.0, budget);
            for (BombSite site : def.sites().values()) outline(p, site.region(), Color.RED, 0.5, budget);
            for (Barrier b : def.barriers()) outline(p, b.region(), Color.AQUA, 0.5, budget);
            for (Region r : def.constructionRegions()) outline(p, r, Color.LIME, 1.0, budget);
            s.selection().ifPresent(r -> outline(p, r, Color.YELLOW, 0.5, budget));
            for (SpawnPoint sp : def.attackerSpawns()) column(p, sp, Color.fromRGB(255, 60, 60));
            for (SpawnPoint sp : def.defenderSpawns()) column(p, sp, Color.fromRGB(60, 120, 255));
        }
    }

    private static void column(Player p, SpawnPoint s, Color c) {
        Particle.DustOptions dust = new Particle.DustOptions(c, 1.2f);
        for (double y = 0; y < 2.0; y += 0.4) p.spawnParticle(Particle.DUST, s.x(), s.y() + y, s.z(), 1, dust);
    }

    /** Draws the 12 edges of a region, only near the viewer, within a particle budget. */
    private static void outline(Player p, Region r, Color c, double step, int[] budget) {
        double x1 = r.min().x(), y1 = r.min().y(), z1 = r.min().z();
        double x2 = r.max().x() + 1, y2 = r.max().y() + 1, z2 = r.max().z() + 1;
        Particle.DustOptions dust = new Particle.DustOptions(c, 0.8f);
        double[][] edges = {
                {x1, y1, z1, x2, y1, z1}, {x1, y2, z1, x2, y2, z1}, {x1, y1, z2, x2, y1, z2}, {x1, y2, z2, x2, y2, z2},
                {x1, y1, z1, x1, y2, z1}, {x2, y1, z1, x2, y2, z1}, {x1, y1, z2, x1, y2, z2}, {x2, y1, z2, x2, y2, z2},
                {x1, y1, z1, x1, y1, z2}, {x2, y1, z1, x2, y1, z2}, {x1, y2, z1, x1, y2, z2}, {x2, y2, z1, x2, y2, z2}};
        org.bukkit.Location eye = p.getLocation();
        for (double[] e : edges) {
            double len = Math.sqrt(sq(e[3] - e[0]) + sq(e[4] - e[1]) + sq(e[5] - e[2]));
            int n = Math.max(1, (int) (len / step));
            for (int i = 0; i <= n && budget[0] > 0; i++) {
                double t = (double) i / n;
                double x = e[0] + (e[3] - e[0]) * t, y = e[1] + (e[4] - e[1]) * t, z = e[2] + (e[5] - e[2]) * t;
                if (sq(x - eye.getX()) + sq(y - eye.getY()) + sq(z - eye.getZ()) > 48 * 48) continue;
                p.spawnParticle(Particle.DUST, x, y, z, 1, dust);
                budget[0]--;
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static List<SpawnPoint> concat(List<SpawnPoint> a, List<SpawnPoint> b) {
        List<SpawnPoint> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
