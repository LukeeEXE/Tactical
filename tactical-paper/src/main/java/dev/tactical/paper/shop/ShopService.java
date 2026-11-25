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
        this.itemIdKey = new NamespacedKey(plugin, "item_id");
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

    private dev.tactical.paper.item.ItemService items;

    /** Custom items (shots...) for entries with {@code give: { item: <id> }}. */
    public void setItems(dev.tactical.paper.item.ItemService items) {
        this.items = items;
    }

    /** The stack a shop entry gives: a custom item, or a prepared vanilla item. Null if it cannot be made. */
    org.bukkit.inventory.ItemStack createItem(ShopEntry e, int amount) {
        if (e.customItem() != null) return items == null ? null : items.create(e.customItem(), amount).orElse(null);
        org.bukkit.Material m = e.material() == null ? null : org.bukkit.Material.matchMaterial(e.material());
        return m == null ? null : MatchShop.prepare(new org.bukkit.inventory.ItemStack(m, amount));
    }

    String itemKey(org.bukkit.inventory.ItemStack item) {
        if (items != null) {
            var id = items.idOf(item);
            if (id.isPresent()) return "tactical:" + id.get();
        }
        return item.getType().getKey().asString();
    }

    /** Money of a match participant, or -1 if the player is not in a match with an economy. */
    public int balance(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        MatchShop shop = m == null ? null : shops.get(m.id());
        return shop == null ? -1 : shop.economy().balance(p.getUniqueId());
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
        Map<String, Integer> base = new HashMap<>();
        for (String spec : config.general().placeholderLoadout()) {
            org.bukkit.inventory.ItemStack i = dev.tactical.paper.arena.MatchArena.parseItem(spec);
            if (i != null) base.merge(i.getType().getKey().asString(), i.getAmount(), Integer::sum);
        }
        MatchShop shop = new MatchShop(match, new MatchEconomy(config.economy(), teams), def, k -> base.getOrDefault(k, 0),
                this::createItem, this::itemKey);
        shops.put(match.id(), shop);
        match.bus().subscribe(MatchFlowEvent.class, e -> onEvent(shop, e));
        match.bus().subscribe(dev.tactical.paper.match.MatchEvents.AssistAwarded.class,
                a -> shop.recordChanges(shop.economy().onAssist(a.assister())));
        int[] ticks = {0};
        match.flow().addTickable(f -> {
            if (hitboxPhase(match)) followHitboxes(match);
            if (++ticks[0] % 20 != 0 || f.state() != MatchState.BUY_PHASE) return;
            menus.refreshWhere(m -> m instanceof ShopMenu s && s.shop() == shop);
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void teardown(GameMatch match) {
        removeHitboxes(match);
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
                Bukkit.getScheduler().runTaskLater(plugin, () -> spawnHitboxes(match), 3L);
            }
            case MatchFlowEvent.KitSelectionOpened ko ->
                    Bukkit.getScheduler().runTaskLater(plugin, () -> spawnHitboxes(match), 3L);
            case MatchFlowEvent.KitSelectionClosed kc -> removeHitboxes(match);
            case MatchFlowEvent.RoundLive rl -> {
                removeHitboxes(match);
                menus.closeWhere(m -> m instanceof ShopMenu s && s.shop() == shop);
                for (Player p : match.onlinePlayers()) hotbar.remove(p, HotbarItems.SHOP);
            }
            case MatchFlowEvent.PlayerEliminated pe -> {
                shop.onDeath(pe.victim());
                removeHitbox(pe.victim());
                if (pe.killer() != null && pe.cause() == EliminationCause.KILLED) shop.recordChanges(eco.onKill(pe.killer()));
            }
            case MatchFlowEvent.BombPlanted bp -> shop.recordChanges(eco.onPlant(match.flow().teamOn(Side.ATTACKERS)));
            case MatchFlowEvent.BombDefused bd -> shop.recordChanges(eco.onDefuse(bd.defuser()));
            case MatchFlowEvent.RoundEnded re -> {
                // (purchases are reconciled by item id, so shots used during the round are gone from the ledger)
                Set<UUID> survivors = new HashSet<>();
                for (Participant p : match.flow().participants()) if (p.active()) survivors.add(p.id());
                boolean planted = match.flow().bomb() != BombStatus.NOT_PLANTED;
                List<EconomyChange> changes = eco.onRoundEnd(re.winnerTeam(), match.flow().teamOn(Side.ATTACKERS), planted, survivors);
                shop.recordChanges(changes);
                for (Player p : match.onlinePlayers()) {
                    if (survivors.contains(p.getUniqueId())) shop.reconcile(p);
                    int delta = changes.stream().filter(c -> c.player().equals(p.getUniqueId())).mapToInt(EconomyChange::delta).sum();
                    if (delta > 0) p.sendMessage(dev.tactical.paper.hud.ChatStyle.line(dev.tactical.engine.pack.HudGlyphs.CHAT_WARNING, NamedTextColor.GOLD,
                            Component.text("+" + String.format("%,d", delta), NamedTextColor.GOLD).append(Component.text(" round reward", NamedTextColor.GRAY))));
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

    // ------------------------------------------------------------------ shop hitbox

    /**
     * During the buy phase every buyer gets an invisible Interaction entity around their own body, visible only to
     * them. The client always targets an entity whose box contains the camera, so right-clicks (empty hand, plain
     * items, into the air) reach the server as an entity interaction and open the shop. It is not mounted on the
     * player: the client ignores entities riding the player when picking a target. It is hidden while either hand
     * holds an item whose right-click does something itself (see {@link #usesRightClick}), so those keep working.
     */
    private final Map<UUID, org.bukkit.entity.Interaction> hitboxes = new HashMap<>();
    private final Set<UUID> hitboxHidden = new HashSet<>();

    /** Hitboxes exist while a right-click opens a menu: the buy phase (shop) and kit selection (kit menu). */
    private static boolean hitboxPhase(GameMatch match) {
        return match.state() == MatchState.BUY_PHASE || match.state().isKitSelection();
    }

    private void spawnHitboxes(GameMatch match) {
        if (!hitboxPhase(match)) return;
        boolean kits = match.state().isKitSelection();
        for (Player p : match.onlinePlayers()) {
            if (!kits && !match.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false)) continue;
            removeHitbox(p.getUniqueId());
            org.bukkit.Location at = hitboxLocation(p);
            org.bukkit.entity.Interaction box = p.getWorld().spawn(at, org.bukkit.entity.Interaction.class, i -> {
                i.setInteractionWidth((float) config.general().shopHitboxWidth());
                i.setInteractionHeight((float) config.general().shopHitboxHeight());
                i.setResponsive(false);
                i.setPersistent(false);
                i.setVisibleByDefault(false);
                i.setSilent(true);
            });
            hitboxes.put(p.getUniqueId(), box);
            hitboxHidden.add(p.getUniqueId());
        }
        followHitboxes(match);
    }

    private void followHitboxes(GameMatch match) {
        for (Player p : match.onlinePlayers()) {
            org.bukkit.entity.Interaction box = hitboxes.get(p.getUniqueId());
            if (box == null) continue;
            if (!box.isValid() || !box.getWorld().equals(p.getWorld())) {
                removeHitbox(p.getUniqueId());
                continue;
            }
            org.bukkit.Location target = hitboxLocation(p);
            if (box.getLocation().distanceSquared(target) > 0.0004) box.teleport(target);
            boolean hide = holdsRightClickItem(p);
            if (hide && !hitboxHidden.contains(p.getUniqueId())) {
                p.hideEntity(plugin, box);
                hitboxHidden.add(p.getUniqueId());
            } else if (!hide && hitboxHidden.contains(p.getUniqueId())) {
                p.showEntity(plugin, box);
                hitboxHidden.remove(p.getUniqueId());
            }
        }
    }

    /** Centred on the player so the eyes stay inside, with margin above and below for jumping and movement. */
    private org.bukkit.Location hitboxLocation(Player p) {
        org.bukkit.Location l = p.getLocation();
        l.setY(l.getY() + p.getEyeHeight() - config.general().shopHitboxHeight() / 2);
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    private void removeHitbox(UUID player) {
        org.bukkit.entity.Interaction box = hitboxes.remove(player);
        hitboxHidden.remove(player);
        if (box != null) box.remove();
    }

    private void removeHitboxes(GameMatch match) {
        for (MatchMember m : match.members()) removeHitbox(m.uuid());
    }

    private boolean isOwnHitbox(Player p, org.bukkit.entity.Entity e) {
        org.bukkit.entity.Interaction box = hitboxes.get(p.getUniqueId());
        return box != null && box.equals(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitboxUse(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof org.bukkit.entity.Interaction)) return;
        boolean own = isOwnHitbox(e.getPlayer(), e.getRightClicked());
        if (!own && !hitboxes.containsValue(e.getRightClicked())) return;
        e.setCancelled(true);
        if (!own || e.getHand() != EquipmentSlot.HAND || menus.openMenu(e.getPlayer()).isPresent()) return;
        openMenuFor(e.getPlayer());
    }

    private java.util.function.BiConsumer<Player, GameMatch> kitOpener = (p, m) -> { };

    /** The kit selection menu opener (KitSelectService), used by the right-click hitbox during kit selection. */
    public void setKitOpener(java.util.function.BiConsumer<Player, GameMatch> opener) {
        this.kitOpener = opener;
    }

    /** Opens whatever a right-click opens right now (kit menu or shop); false if nothing. */
    private boolean openMenuFor(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m != null && m.state().isKitSelection()) {
            kitOpener.accept(p, m);
            return true;
        }
        MatchShop shop = buyingShop(p);
        if (shop == null) return false;
        openShop(p, shop);
        return true;
    }

    /** Action-bar hint while the shop can be opened. */
    public dev.tactical.paper.hud.HudService.Hint hint(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m == null || buyingShop(p) == null) return null;
        int s = (Math.max(0, m.flow().stateTicksRemaining()) + 19) / 20;
        return new dev.tactical.paper.hud.HudService.Hint("Right-click to open the shop", HINT_ORANGE,
                String.format("Buy phase ends in %d:%02d", s / 60, s % 60), NamedTextColor.WHITE);
    }

    public static final net.kyori.adventure.text.format.TextColor HINT_ORANGE = net.kyori.adventure.text.format.TextColor.color(0xFFA62B);

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitboxHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Interaction && hitboxes.containsValue(e.getEntity())) e.setCancelled(true);
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
        menus.open(p, new ShopMenu(shop, icons, this::createItem));
    }

    /** Items whose right-click does something on its own (vanilla items without a component that says so). */
    private static final java.util.Set<org.bukkit.Material> RIGHT_CLICK_ITEMS = java.util.EnumSet.of(
            org.bukkit.Material.BOW, org.bukkit.Material.CROSSBOW, org.bukkit.Material.TRIDENT,
            org.bukkit.Material.SNOWBALL, org.bukkit.Material.EGG, org.bukkit.Material.BROWN_EGG, org.bukkit.Material.BLUE_EGG,
            org.bukkit.Material.ENDER_PEARL, org.bukkit.Material.ENDER_EYE, org.bukkit.Material.WIND_CHARGE,
            org.bukkit.Material.SPLASH_POTION, org.bukkit.Material.LINGERING_POTION, org.bukkit.Material.EXPERIENCE_BOTTLE,
            org.bukkit.Material.FIRE_CHARGE, org.bukkit.Material.FLINT_AND_STEEL, org.bukkit.Material.FISHING_ROD,
            org.bukkit.Material.SPYGLASS, org.bukkit.Material.GOAT_HORN, org.bukkit.Material.FIREWORK_ROCKET,
            org.bukkit.Material.SHIELD, org.bukkit.Material.BRUSH, org.bukkit.Material.REDSTONE, org.bukkit.Material.STRING,
            org.bukkit.Material.WRITABLE_BOOK, org.bukkit.Material.WRITTEN_BOOK, org.bukkit.Material.CARROT_ON_A_STICK,
            org.bukkit.Material.WARPED_FUNGUS_ON_A_STICK, org.bukkit.Material.LEAD, org.bukkit.Material.NAME_TAG,
            org.bukkit.Material.BONE_MEAL, org.bukkit.Material.ARMOR_STAND, org.bukkit.Material.ITEM_FRAME,
            org.bukkit.Material.GLOW_ITEM_FRAME, org.bukkit.Material.PAINTING, org.bukkit.Material.END_CRYSTAL);

    /** Extra checks registered by other systems (custom utility items in Phase 3). */
    private final List<java.util.function.Predicate<org.bukkit.inventory.ItemStack>> rightClickUsable = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Lets e.g. the custom item framework declare items whose right-click must never open the shop. */
    public void addRightClickUsable(java.util.function.Predicate<org.bukkit.inventory.ItemStack> test) {
        rightClickUsable.add(test);
    }

    private final NamespacedKey itemIdKey;

    /**
     * True if right-clicking with this item does something itself (place, shoot, throw, eat/drink, block, equip...).
     * Such items never open the shop, so they keep working during the buy phase. The bomb is the exception: it
     * cannot be planted during the buy phase, so its right-click is free for the shop.
     */
    private boolean usesRightClick(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (item.hasItemMeta() && "bomb".equals(item.getItemMeta().getPersistentDataContainer().get(itemIdKey,
                org.bukkit.persistence.PersistentDataType.STRING))) return false;
        if (HotbarItems.KIT.equals(hotbar.typeOf(item))) return true; // has its own menu
        org.bukkit.Material t = item.getType();
        if (t.isBlock() || RIGHT_CLICK_ITEMS.contains(t)) return true;
        String n = t.name();
        if (n.endsWith("_SPAWN_EGG") || n.endsWith("_BUCKET") || n.endsWith("_BOAT") || n.endsWith("_RAFT")
                || n.endsWith("_MINECART") || n.equals("MINECART") || n.endsWith("_BUNDLE") || n.equals("BUNDLE")) return true;
        if (item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE) != null) return true; // food, drinks
        if (item.getData(io.papermc.paper.datacomponent.DataComponentTypes.BLOCKS_ATTACKS) != null) return true; // shields, claymore
        if (item.getData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE) != null) return true; // armor, heads
        for (java.util.function.Predicate<org.bukkit.inventory.ItemStack> test : rightClickUsable) if (test.test(item)) return true;
        return false;
    }

    /** Either hand: an empty main hand with e.g. a shield in the off hand still right-clicks the off-hand item. */
    private boolean holdsRightClickItem(Player p) {
        return usesRightClick(p.getInventory().getItemInMainHand()) || usesRightClick(p.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (holdsRightClickItem(e.getPlayer())) return;
        if (openMenuFor(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseEntity(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || holdsRightClickItem(e.getPlayer())) return;
        if (openMenuFor(e.getPlayer())) e.setCancelled(true);
    }


}
