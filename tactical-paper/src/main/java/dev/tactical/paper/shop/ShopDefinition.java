package dev.tactical.paper.shop;

import dev.tactical.engine.config.ConfigNode;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A kit's shop (the {@code shop:} section of {@code kits/<id>.yml}). Every kit has its own shop, the same on every
 * map, with three fixed categories laid out in the shop screen: {@code melee} (up to 5 entries), {@code utility}
 * (up to 3) and {@code ranged} (up to 5). Prices are read at purchase time (hot reload).
 */
public record ShopDefinition(String id, Map<String, String> categories, Map<String, ShopEntry> entries) {

    /** Category id, display name and how many slots the shop screen has for it. */
    public enum Category {
        MELEE("melee", "Melee", 5), UTILITY("utility", "Utility", 3), RANGED("ranged", "Ranged", 5);

        public final String id;
        public final String display;
        public final int slots;

        Category(String id, String display, int slots) {
            this.id = id;
            this.display = display;
            this.slots = slots;
        }
    }

    public static ShopDefinition empty(String id) {
        return new ShopDefinition(id, categoryNames(), Map.of());
    }

    private static Map<String, String> categoryNames() {
        Map<String, String> c = new LinkedHashMap<>();
        for (Category cat : Category.values()) c.put(cat.id, cat.display);
        return c;
    }

    /** Entries of one category, in file order. */
    public List<ShopEntry> entries(Category category) {
        List<ShopEntry> out = new ArrayList<>();
        for (ShopEntry e : entries.values()) if (e.category().equals(category.id) && e.enabled()) out.add(e);
        return out;
    }

    /**
     * {@code shop: { melee: { <entry>: {...} }, utility: {...}, ranged: {...} }}; each entry has {@code name},
     * {@code price}, {@code max}, {@code enabled} and either {@code give: { material | item, amount }} or
     * {@code enchant: { enchantment, level, applies-to }}.
     */
    public static ShopDefinition parseKitShop(ConfigNode shop, String kitId) {
        shop.warnUnknownKeys(List.of("melee", "utility", "ranged"));
        Map<String, ShopEntry> entries = new LinkedHashMap<>();
        for (Category cat : Category.values()) {
            ConfigNode c = shop.child(cat.id);
            int n = 0;
            for (Map.Entry<String, ConfigNode> e : c.children().entrySet()) {
                if (entries.containsKey(e.getKey())) {
                    e.getValue().error("entry id '" + e.getKey() + "' is used twice in this shop");
                    continue;
                }
                if (++n > cat.slots) {
                    e.getValue().error("the " + cat.id + " category has room for " + cat.slots + " entries");
                    continue;
                }
                parseEntry(e.getKey(), cat.id, e.getValue()).ifPresent(x -> entries.put(x.id(), x));
            }
        }
        return new ShopDefinition(kitId, categoryNames(), entries);
    }

    private static Optional<ShopEntry> parseEntry(String id, String category, ConfigNode n) {
        n.warnUnknownKeys(List.of("name", "price", "max", "enabled", "give", "enchant"));
        int price = n.integer("price", 0, 0, 1_000_000);
        int max = n.integer("max", 1, 1, 64);
        boolean enabled = n.bool("enabled", true);
        String name = n.string("name", id);
        if (n.has("give") == n.has("enchant")) {
            n.error("needs exactly one of 'give' or 'enchant'");
            return Optional.empty();
        }
        if (n.has("give")) {
            ConfigNode g = n.child("give");
            g.warnUnknownKeys(List.of("material", "item", "amount"));
            int amount = g.integer("amount", 1, 1, 64);
            if (g.has("item") == g.has("material")) {
                g.error("needs exactly one of 'material' (vanilla item) or 'item' (custom item from items/*.yml)");
                return Optional.empty();
            }
            if (g.has("item")) {
                String custom = g.requireString("item");
                return custom == null ? Optional.empty() : Optional.of(new ShopEntry(id, category, name, ShopEntry.Kind.ITEM, price, max,
                        enabled, null, amount, null, 0, List.of(), custom.toLowerCase(Locale.ROOT)));
            }
            String mat = g.requireString("material");
            Material m = mat == null ? null : Material.matchMaterial(mat);
            if (mat != null && (m == null || !m.isItem())) {
                g.child("material").error("unknown item '" + mat + "'");
                return Optional.empty();
            }
            return m == null ? Optional.empty() : Optional.of(new ShopEntry(id, category, name, ShopEntry.Kind.ITEM, price, max,
                    enabled, m.getKey().asString(), amount, null, 0, List.of(), null));
        }
        ConfigNode en = n.child("enchant");
        en.warnUnknownKeys(List.of("enchantment", "level", "applies-to"));
        String ench = en.requireString("enchantment");
        int level = en.integer("level", 1, 1, 10);
        if (ench == null) return Optional.empty();
        NamespacedKey k = ench.contains(":") ? NamespacedKey.fromString(ench) : NamespacedKey.minecraft(ench.toLowerCase(Locale.ROOT));
        if (k == null || RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k) == null) {
            en.child("enchantment").error("unknown enchantment '" + ench + "'");
            return Optional.empty();
        }
        List<String> applies = new ArrayList<>();
        for (String a : en.stringList("applies-to")) {
            Material m = Material.matchMaterial(a);
            if (m == null) en.child("applies-to").error("unknown item '" + a + "'");
            else applies.add(m.getKey().asString());
        }
        if (applies.isEmpty()) {
            en.error("applies-to must list at least one item");
            return Optional.empty();
        }
        return Optional.of(new ShopEntry(id, category, name, ShopEntry.Kind.ENCHANT, price, max, enabled,
                null, 1, k.asString(), level, applies, null));
    }
}
