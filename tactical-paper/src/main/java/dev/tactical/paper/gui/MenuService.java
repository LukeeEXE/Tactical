package dev.tactical.paper.gui;

import dev.tactical.paper.core.Service;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The plugin's own inventory GUI framework (no external library).
 *
 * <p>Security model: while one of our menus is open EVERY click and drag is cancelled first, then only a plain
 * left/right/shift click on a button slot of the TOP inventory is dispatched to that button. That blocks item
 * movement, shift-click transfer, number-key swaps, off-hand swaps, double-click collection, drags, drops and
 * creative middle-clicks into or out of menus. Permissions are re-checked on every click, clicks are rate limited,
 * and menu state is dropped on close and disconnect.
 */
public final class MenuService implements Service, Listener {

    private static final long CLICK_COOLDOWN_MS = 120;

    public static final class Holder implements InventoryHolder {
        private final Menu menu;
        private final UUID viewer;
        private Inventory inventory;
        private MenuView view;

        Holder(Menu menu, UUID viewer) {
            this.menu = menu;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public Menu menu() {
            return menu;
        }
    }

    private final Plugin plugin;
    private final Logger log;
    private final Map<UUID, Holder> open = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public MenuService(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        for (UUID id : List.copyOf(open.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.closeInventory();
        }
        open.clear();
        HandlerList.unregisterAll(this);
    }

    public void open(Player p, Menu menu) {
        if (menu.permission() != null && !p.hasPermission(menu.permission())) return;
        Holder h = new Holder(menu, p.getUniqueId());
        int size = Math.max(1, Math.min(6, menu.rows())) * 9;
        Component title = menu.title(p);
        h.inventory = Bukkit.createInventory(h, size, title);
        render(p, h);
        p.openInventory(h.inventory); // closes any previous menu first (its onClose runs)
        open.put(p.getUniqueId(), h);
        try {
            menu.onOpen(p);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Menu open handler failed in " + menu.getClass().getSimpleName(), t);
        }
    }

    public Optional<Menu> openMenu(Player p) {
        Holder h = open.get(p.getUniqueId());
        return h == null ? Optional.empty() : Optional.of(h.menu);
    }

    /** Re-renders the player's open menu in place (no flicker, cursor kept). */
    public void refresh(Player p) {
        Holder h = open.get(p.getUniqueId());
        if (h != null) render(p, h);
    }

    /** Re-renders every open menu matching the predicate. */
    public void refreshWhere(Predicate<Menu> filter) {
        for (Holder h : List.copyOf(open.values())) {
            Player p = Bukkit.getPlayer(h.viewer);
            if (p != null && filter.test(h.menu)) render(p, h);
        }
    }

    public void closeWhere(Predicate<Menu> filter) {
        for (Holder h : List.copyOf(open.values())) {
            Player p = Bukkit.getPlayer(h.viewer);
            if (p != null && filter.test(h.menu)) p.closeInventory();
        }
    }

    private void render(Player p, Holder h) {
        MenuView view = new MenuView(h.inventory.getSize());
        try {
            h.menu.render(p, view);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Menu " + h.menu.getClass().getSimpleName() + " failed to render", t);
        }
        h.view = view;
        ItemStack[] contents = new ItemStack[h.inventory.getSize()];
        view.buttons.forEach((slot, b) -> contents[slot] = b.icon());
        h.inventory.setContents(contents);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        e.setCancelled(true); // cancel FIRST, whatever happens below
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(h.viewer)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) {
            // Own inventory: only plain moves within the slots the menu opens up (e.g. arranging the hotbar).
            if (e.getClickedInventory() == e.getView().getBottomInventory() && h.menu.editablePlayerSlot(e.getSlot())
                    && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT) && PLAIN_MOVES.contains(e.getAction())) {
                e.setCancelled(false);
            }
            return;
        }
        ClickType type = e.getClick();
        if (type != ClickType.LEFT && type != ClickType.RIGHT && type != ClickType.SHIFT_LEFT && type != ClickType.SHIFT_RIGHT) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getAction() == InventoryAction.HOTBAR_SWAP) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p.getUniqueId(), now);
        if (last != null && now - last < CLICK_COOLDOWN_MS) return;
        if (h.menu.permission() != null && !p.hasPermission(h.menu.permission())) {
            p.closeInventory();
            return;
        }
        MenuView.Button b = h.view == null ? null : h.view.buttons.get(e.getRawSlot());
        if (b == null || b.handler() == null) return;
        try {
            b.handler().click(type);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Menu click handler failed in " + h.menu.getClass().getSimpleName(), t);
        }
        if (open.get(p.getUniqueId()) == h) render(p, h); // the handler may have opened another menu
    }

    private static final java.util.Set<InventoryAction> PLAIN_MOVES = java.util.EnumSet.of(InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_ONE, InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR, InventoryAction.NOTHING);

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        int top = e.getView().getTopInventory().getSize();
        boolean allowed = !e.getRawSlots().isEmpty();
        for (int raw : e.getRawSlots()) {
            if (raw < top || !h.menu.editablePlayerSlot(e.getView().convertSlot(raw))) allowed = false;
        }
        e.setCancelled(!allowed);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopper(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder(false) instanceof Holder || e.getDestination().getHolder(false) instanceof Holder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder h)) return;
        if (open.get(h.viewer) == h) open.remove(h.viewer);
        if (e.getPlayer() instanceof Player p) {
            try {
                h.menu.onClose(p);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Menu close handler failed", t);
            }
            // Any item that somehow reached the cursor from a menu is destroyed, never kept.
            if (p.getItemOnCursor() != null && Icons.isMenuItem(p.getItemOnCursor())) p.setItemOnCursor(null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }
}
