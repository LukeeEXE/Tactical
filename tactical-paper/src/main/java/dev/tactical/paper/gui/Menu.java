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

    /** Called when the inventory closes for any reason (including disconnect and replacement by another menu). */
    public void onClose(Player viewer) {
    }
}
