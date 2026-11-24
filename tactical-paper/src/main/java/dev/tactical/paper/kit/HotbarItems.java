package dev.tactical.paper.kit;

import dev.tactical.paper.gui.Icons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** Hotbar items that open menus (kit selection, shop). Identified by a tag, never by material or name. */
public final class HotbarItems {

    public static final String KIT = "kit_opener";
    public static final String SHOP = "shop_opener";

    private final NamespacedKey key;
    private final Icons icons;

    public HotbarItems(Plugin plugin, Icons icons) {
        this.key = new NamespacedKey(plugin, "ui_item");
        this.icons = icons;
    }

    public ItemStack create(String type) {
        ItemStack item = switch (type) {
            case KIT -> icons.icon("kit.opener", Component.text("Choose your kit", NamedTextColor.GOLD),
                    List.of(Component.text("Right-click to open kit selection", NamedTextColor.GRAY)));
            case SHOP -> icons.icon("shop.opener", Component.text("Shop", NamedTextColor.GREEN),
                    List.of(Component.text("Right-click to buy equipment", NamedTextColor.GRAY)));
            default -> throw new IllegalArgumentException(type);
        };
        item.editMeta(m -> m.getPersistentDataContainer().set(key, PersistentDataType.STRING, type));
        return item;
    }

    public String typeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public void give(Player p, String type, int slot) {
        remove(p, type);
        ItemStack existing = p.getInventory().getItem(slot);
        if (existing != null && !existing.getType().isAir()) p.getInventory().addItem(existing);
        p.getInventory().setItem(slot, create(type));
    }

    public void remove(Player p, String type) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (type.equals(typeOf(c[i]))) p.getInventory().setItem(i, null);
    }
}
