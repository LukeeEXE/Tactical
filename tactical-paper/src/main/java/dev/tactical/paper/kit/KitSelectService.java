package dev.tactical.paper.kit;

import dev.tactical.engine.match.MatchFlowEvent;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.gui.Icons;
import dev.tactical.paper.gui.MenuService;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchLifecycleHook;
import dev.tactical.paper.match.MatchService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Opens the kit selection menu for every participant whenever a selection starts (match start, halftime,
 * sudden death), keeps all open menus live while teammates and opponents pick, and closes them when it ends.
 * Players who close the menu reopen it by right-clicking (the shop's hitbox, see ShopService).
 */
public final class KitSelectService implements Service, Listener, MatchLifecycleHook {

    public static final int OPENER_SLOT = 4;

    private final Plugin plugin;
    private final ConfigService config;
    private final MatchService matches;
    private final MenuService menus;
    private final Icons icons;
    private final HotbarItems hotbar;

    public KitSelectService(Plugin plugin, ConfigService config, MatchService matches, MenuService menus, Icons icons, HotbarItems hotbar) {
        this.plugin = plugin;
        this.config = config;
        this.matches = matches;
        this.menus = menus;
        this.icons = icons;
        this.hotbar = hotbar;
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

    @Override
    public CompletableFuture<Void> prepare(GameMatch match, MatchService.MatchRequest request) {
        match.bus().subscribe(MatchFlowEvent.class, e -> onEvent(match, e));
        int[] ticks = {0};
        match.flow().addTickable(f -> {
            if (++ticks[0] % 20 == 0 && f.state().isKitSelection()) refresh(match);
        });
        return CompletableFuture.completedFuture(null);
    }

    private void onEvent(GameMatch match, MatchFlowEvent e) {
        switch (e) {
            case MatchFlowEvent.KitSelectionOpened o -> Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : match.onlinePlayers()) {
                    hotbar.remove(p, HotbarItems.KIT);
                    open(p, match);
                }
            });
            case MatchFlowEvent.KitHovered h -> refresh(match);
            case MatchFlowEvent.KitLocked l -> refresh(match);
            case MatchFlowEvent.SkipVoted v -> refresh(match);
            case MatchFlowEvent.KitSelectionClosed c -> {
                menus.closeWhere(m -> m instanceof KitSelectMenu k && k.match() == match);
                for (Player p : match.onlinePlayers()) hotbar.remove(p, HotbarItems.KIT);
            }
            case MatchFlowEvent.PlayerReconnected r -> {
                Player p = Bukkit.getPlayer(r.player());
                if (p != null && match.flow().kitSelection().isPresent()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> open(p, match), 10L);
                }
            }
            default -> { }
        }
    }

    private void refresh(GameMatch match) {
        menus.refreshWhere(m -> m instanceof KitSelectMenu k && k.match() == match);
    }

    /** Action-bar hint during kit selection: how to (re)open the menu and what is currently picked. */
    public dev.tactical.paper.hud.HudService.Hint hint(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m == null || !m.state().isKitSelection()) return null;
        var sel = m.flow().kitSelection().orElse(null);
        if (sel == null || sel.optionsOf(p.getUniqueId()).isEmpty()) return null;
        String detail = sel.lockedBy(p.getUniqueId()).map(k -> "Locked: " + kitName(k))
                .or(() -> sel.hoveredBy(p.getUniqueId()).map(k -> "Selected: " + kitName(k)))
                .orElse("No kit selected");
        return new dev.tactical.paper.hud.HudService.Hint("Right-click to select a kit",
                dev.tactical.paper.shop.ShopService.HINT_ORANGE, detail, net.kyori.adventure.text.format.NamedTextColor.WHITE);
    }

    private String kitName(String id) {
        var d = config.kits().get(id);
        return d == null ? id : d.name();
    }

    /** Opens the kit menu for a member while a selection is running (also used by the right-click hitbox). */
    public void openFor(Player p, GameMatch match) {
        open(p, match);
    }

    private void open(Player p, GameMatch match) {
        if (match.flow().kitSelection().isEmpty() || !match.isMember(p.getUniqueId())) return;
        menus.open(p, new KitSelectMenu(match, config, icons));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!HotbarItems.KIT.equals(hotbar.typeOf(e.getItem()))) return;
        e.setCancelled(true);
        matches.matchOf(e.getPlayer().getUniqueId()).ifPresentOrElse(m -> open(e.getPlayer(), m),
                () -> hotbar.remove(e.getPlayer(), HotbarItems.KIT));
    }
}
