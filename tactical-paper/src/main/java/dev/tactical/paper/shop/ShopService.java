package dev.tactical.paper.shop;

import dev.tactical.engine.economy.EconomyChange;
import dev.tactical.engine.economy.MatchEconomy;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.round.BombStatus;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.gui.Icons;
import dev.tactical.paper.gui.MenuService;
import dev.tactical.paper.kit.HotbarItems;
import dev.tactical.paper.map.MapService;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchLifecycleHook;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import dev.tactical.engine.id.MatchId;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Per-match economy and shop: payouts (round result, kills, assists, plant, defuse, halftime, sudden death),
 * the shop hotbar item during the buy phase, the shop menu, and purchase retention/loss. The shop and economy
 * rules are snapshotted when the match starts, so config edits only affect later matches.
 */
public final class ShopService implements Service, Listener, MatchLifecycleHook {


    private final Plugin plugin;
    private final ConfigService config;
    private final MapService maps;
    private final MatchService matches;
    private final MenuService menus;
    private final Icons icons;
    private final HotbarItems hotbar;
    private final Logger log;
    private final Map<MatchId, MatchShop> shops = new HashMap<>();

    public ShopService(Plugin plugin, ConfigService config, MapService maps, MatchService matches, MenuService menus,
                       Icons icons, HotbarItems hotbar, Logger log) {
        this.plugin = plugin;
        this.config = config;
        this.maps = maps;
        this.matches = matches;
        this.menus = menus;
        this.icons = icons;
        this.hotbar = hotbar;
        this.log = log;
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

    public Optional<MatchShop> shop(MatchId id) {
        return Optional.ofNullable(shops.get(id));
    }

    @Override
    public CompletableFuture<Void> prepare(GameMatch match, MatchService.MatchRequest request) {
        String shopId = request.mapId() == null ? "default" : maps.map(request.mapId()).map(m -> m.shop()).orElse("default");
        ShopDefinition def = config.shops().getOrDefault(shopId, config.shops().get("default"));
        if (def == null) {
            log.warning("[match=" + match.id() + "] no shop available (shops/default.yml invalid?); buying is disabled");
            def = new ShopDefinition("none", Map.of(), Map.of());
        }
        Map<UUID, TeamId> teams = new LinkedHashMap<>();
        for (MatchMember m : match.members()) teams.put(m.uuid(), m.team());
        Map<org.bukkit.Material, Integer> base = new HashMap<>();
        for (String spec : config.general().placeholderLoadout()) {
            org.bukkit.inventory.ItemStack i = dev.tactical.paper.arena.MatchArena.parseItem(spec);
            if (i != null) base.merge(i.getType(), i.getAmount(), Integer::sum);
        }
        MatchShop shop = new MatchShop(match, new MatchEconomy(config.economy(), teams), def, m -> base.getOrDefault(m, 0));
        shops.put(match.id(), shop);
        match.bus().subscribe(MatchFlowEvent.class, e -> onEvent(shop, e));
        match.bus().subscribe(dev.tactical.paper.match.MatchEvents.AssistAwarded.class,
                a -> shop.recordChanges(shop.economy().onAssist(a.assister())));
        int[] ticks = {0};
        match.flow().addTickable(f -> {
            if (++ticks[0] % 20 != 0 || f.state() != MatchState.BUY_PHASE) return;
            for (Player p : match.onlinePlayers()) {
                if (!f.participant(p.getUniqueId()).map(Participant::active).orElse(false)) continue;
                // Temporary money display until the custom HUD (Phase 5) owns the action bar.
                p.sendActionBar(Component.text("$" + String.format("%,d", shop.economy().balance(p.getUniqueId())), NamedTextColor.GOLD)
                        .append(Component.text("   Right-click (or press F) to open the shop", NamedTextColor.GRAY)));
            }
            menus.refreshWhere(m -> m instanceof ShopMenu s && s.shop() == shop);
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void teardown(GameMatch match) {
        shops.remove(match.id());
        menus.closeWhere(m -> m instanceof ShopMenu s && s.shop().match() == match);
    }

    private void onEvent(MatchShop shop, MatchFlowEvent e) {
        GameMatch match = shop.match();
        MatchEconomy eco = shop.economy();
        switch (e) {
            case MatchFlowEvent.RoundStarted rs -> {
                for (Player p : match.onlinePlayers()) {
                    if (!match.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false)) continue;
                    shop.onRoundStart(p);
                }
                // Inventory was cleared and refilled in the same tick as the teleport: resend equipment so no
                // client keeps showing a stale (invisible) armor state.
                Bukkit.getScheduler().runTaskLater(plugin, () -> resendEquipment(match), 2L);
            }
            case MatchFlowEvent.RoundLive rl -> {
                menus.closeWhere(m -> m instanceof ShopMenu s && s.shop() == shop);
                for (Player p : match.onlinePlayers()) hotbar.remove(p, HotbarItems.SHOP);
            }
            case MatchFlowEvent.PlayerEliminated pe -> {
                shop.onDeath(pe.victim());
                if (pe.killer() != null && pe.cause() == EliminationCause.KILLED) shop.recordChanges(eco.onKill(pe.killer()));
            }
            case MatchFlowEvent.BombPlanted bp -> shop.recordChanges(eco.onPlant(match.flow().teamOn(Side.ATTACKERS)));
            case MatchFlowEvent.BombDefused bd -> shop.recordChanges(eco.onDefuse(bd.defuser()));
            case MatchFlowEvent.RoundEnded re -> {
                Set<UUID> survivors = new HashSet<>();
                for (Participant p : match.flow().participants()) if (p.active()) survivors.add(p.id());
                boolean planted = match.flow().bomb() != BombStatus.NOT_PLANTED;
                List<EconomyChange> changes = eco.onRoundEnd(re.winnerTeam(), match.flow().teamOn(Side.ATTACKERS), planted, survivors);
                shop.recordChanges(changes);
                for (Player p : match.onlinePlayers()) {
                    if (survivors.contains(p.getUniqueId())) shop.reconcile(p);
                    int delta = changes.stream().filter(c -> c.player().equals(p.getUniqueId())).mapToInt(EconomyChange::delta).sum();
                    if (delta > 0) p.sendMessage(Component.text("+$" + String.format("%,d", delta) + " (round reward)", NamedTextColor.GOLD));
                }
            }
            case MatchFlowEvent.HalftimeStarted ht -> {
                shop.recordChanges(eco.onHalftime());
                if (eco.rules().halftimeClearPurchases()) shop.clearAll();
            }
            case MatchFlowEvent.SuddenDeathStarted sd -> shop.recordChanges(eco.onSuddenDeath());
            default -> { }
        }
    }

    private void resendEquipment(GameMatch match) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};
        for (Player p : match.onlinePlayers()) {
            Map<EquipmentSlot, org.bukkit.inventory.ItemStack> eq = new java.util.EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot s : slots) eq.put(s, p.getInventory().getItem(s));
            for (Player viewer : p.getWorld().getPlayers()) if (viewer != p) viewer.sendEquipmentChange(p, eq);
            p.updateInventory();
        }
    }

    // ------------------------------------------------------------------ input: right-click (or F) opens the shop

    /** The shop for a participant who may buy right now, or null. */
    private MatchShop buyingShop(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m == null || m.state() != MatchState.BUY_PHASE) return null;
        if (!m.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false)) return null;
        return shops.get(m.id());
    }

    private void openShop(Player p, MatchShop shop) {
        menus.open(p, new ShopMenu(shop, icons));
    }

    /** Bows and crossbows keep their right-click so they can be charged during the buy phase. */
    private static boolean keepsRightClick(org.bukkit.inventory.ItemStack item) {
        if (item == null) return false;
        org.bukkit.Material t = item.getType();
        return t == org.bukkit.Material.BOW || t == org.bukkit.Material.CROSSBOW;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (HotbarItems.KIT.equals(hotbar.typeOf(e.getItem())) || keepsRightClick(e.getItem())) return;
        MatchShop shop = buyingShop(e.getPlayer());
        if (shop == null) return;
        e.setCancelled(true);
        openShop(e.getPlayer(), shop);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseEntity(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || keepsRightClick(e.getPlayer().getInventory().getItemInMainHand())) return;
        MatchShop shop = buyingShop(e.getPlayer());
        if (shop == null) return;
        e.setCancelled(true);
        openShop(e.getPlayer(), shop);
    }

    /** F (swap hands) also opens the shop: an empty-hand right-click into the air never reaches the server. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        MatchShop shop = buyingShop(e.getPlayer());
        if (shop == null) return;
        e.setCancelled(true);
        openShop(e.getPlayer(), shop);
    }

}
