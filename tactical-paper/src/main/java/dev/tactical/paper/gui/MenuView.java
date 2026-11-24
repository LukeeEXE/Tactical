package dev.tactical.paper.gui;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** The buttons of one rendered frame. Slots without a button are inert. */
public final class MenuView {

    @FunctionalInterface
    public interface ClickHandler {
        void click(ClickType type);
    }

    record Button(ItemStack icon, ClickHandler handler) {
    }

    private final int size;
    final Map<Integer, Button> buttons = new HashMap<>();

    MenuView(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public void set(int slot, ItemStack icon) {
        set(slot, icon, null);
    }

    public void set(int slot, ItemStack icon, ClickHandler handler) {
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot " + slot + " outside menu of size " + size);
        buttons.put(slot, new Button(icon, handler));
    }
}
