package dev.tactical.paper.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * A menu's LOGIC. Appearance comes from {@link Icons} (placeholder items with Custom Model Data strings that a
 * resource pack can retexture), so restyling never touches this code. Rendering is repeated on every refresh,
 * so a menu always shows live state.
 */
public abstract class Menu {

    /** Permission re-checked on every click (null = none). Hiding a button is never the only protection. */
    public String permission() {
        return null;
    }

    public abstract Component title(Player viewer);

    public int rows() {
        return 6;
    }

    /** Fill the view. Called on open and on every refresh. */
    public abstract void render(Player viewer, MenuView view);

    /** Called right after the menu opened (after any previous menu's {@link #onClose}). */
    public void onOpen(Player viewer) {
    }

    /**
     * Player-inventory slots (0-8 hotbar, 9-35 main) the viewer may rearrange while this menu is open: plain
     * pick-up / place / swap-with-cursor clicks and drags that stay inside these slots. Everything else stays blocked.
     */
    public boolean editablePlayerSlot(int slot) {
        return false;
    }

    /**
     * A click on a player-inventory slot that the menu uses as a button (e.g. a confirm button drawn over the
     * inventory). Return true if handled; the click itself is always cancelled.
     */
    public boolean onPlayerSlotClick(Player viewer, int slot, org.bukkit.event.inventory.ClickType click) {
        return false;
    }

    /** Called when the inventory closes for any reason (including disconnect and replacement by another menu). */
    public void onClose(Player viewer) {
    }
}
