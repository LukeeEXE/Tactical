package dev.tactical.paper.item;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/** The tag shown under an item's name in its lore ("UTILITY", "WEAPON", ...), with its tint. */
public enum ItemCategory {
    UTILITY("UTILITY", 0x8C9BD6),
    WEAPON("WEAPON", 0xD68C8C),
    ARMOR("ARMOR", 0x8CC4D6),
    ENCHANT("ENCHANT", 0xC08CD6),
    AMMO("AMMO", 0xD6C48C),
    ITEM("ITEM", 0xA0A0A8);

    private final String label;
    private final TextColor color;

    ItemCategory(String label, int rgb) {
        this.label = label;
        this.color = TextColor.color(rgb);
    }

    public String label() {
        return label;
    }

    public TextColor color() {
        return color;
    }

    /** Category of a plain vanilla item. */
    public static ItemCategory of(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR") || m == Material.BOW || m == Material.CROSSBOW
                || m == Material.TRIDENT || m == Material.MACE) return WEAPON;
        if (m.getEquipmentSlot().isArmor() || m == Material.SHIELD) return ARMOR;
        if (n.endsWith("ARROW")) return AMMO;
        if (m == Material.ENCHANTED_BOOK) return ENCHANT;
        return ITEM;
    }
}
