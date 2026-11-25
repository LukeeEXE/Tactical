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

/** A shop ({@code shops/<id>.yml}): ordered categories and entries. Prices are read at purchase time (hot reload). */
public record ShopDefinition(String id, Map<String, String> categories, Map<String, ShopEntry> entries) {

    public static final int SCHEMA_VERSION = 1;

    public static Optional<ShopDefinition> parse(ConfigNode root, String expectedId) {
        int before = root.report().errors().size();
        root.warnUnknownKeys(List.of("schema-version", "id", "categories", "items"));
        String id = root.string("id", expectedId);
        if (!id.equals(expectedId)) root.child("id").error("must match the file name '" + expectedId + "'");
        Map<String, String> categories = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigNode> e : root.child("categories").children().entrySet()) {
            categories.put(e.getKey(), e.getValue().scalar().orElseGet(() -> e.getValue().string("name", e.getKey())));
        }
        Map<String, ShopEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigNode> e : root.child("items").children().entrySet()) {
            ConfigNode n = e.getValue();
            n.warnUnknownKeys(List.of("category", "name", "price", "max", "enabled", "give", "enchant"));
            String category = n.string("category", categories.isEmpty() ? "items" : categories.keySet().iterator().next());
            if (!categories.containsKey(category)) categories.put(category, category);
            int price = n.integer("price", 0, 0, 1_000_000);
            int max = n.integer("max", 1, 1, 64);
            boolean enabled = n.bool("enabled", true);
            String name = n.string("name", e.getKey());
            if (n.has("give") == n.has("enchant")) {
                n.error("needs exactly one of 'give' or 'enchant'");
                continue;
            }
            if (n.has("give")) {
                ConfigNode g = n.child("give");
                g.warnUnknownKeys(List.of("material", "item", "amount"));
                int amount = g.integer("amount", 1, 1, 64);
                if (g.has("item") == g.has("material")) {
                    g.error("needs exactly one of 'material' (vanilla item) or 'item' (custom item from items/*.yml)");
                    continue;
                }
                if (g.has("item")) {
                    String custom = g.requireString("item");
                    if (custom != null) entries.put(e.getKey(), new ShopEntry(e.getKey(), category, name, ShopEntry.Kind.ITEM, price, max,
                            enabled, null, amount, null, 0, List.of(), custom.toLowerCase(Locale.ROOT)));
                    continue;
                }
                String mat = g.requireString("material");
                Material m = mat == null ? null : Material.matchMaterial(mat);
                if (mat != null && (m == null || !m.isItem())) g.child("material").error("unknown item '" + mat + "'");
                if (m != null) entries.put(e.getKey(), new ShopEntry(e.getKey(), category, name, ShopEntry.Kind.ITEM, price, max,
                        enabled, m.getKey().asString(), amount, null, 0, List.of(), null));
            } else {
                ConfigNode en = n.child("enchant");
                en.warnUnknownKeys(List.of("enchantment", "level", "applies-to"));
                String ench = en.requireString("enchantment");
                int level = en.integer("level", 1, 1, 10);
                if (ench != null) {
                    NamespacedKey k = ench.contains(":") ? NamespacedKey.fromString(ench) : NamespacedKey.minecraft(ench.toLowerCase(Locale.ROOT));
                    if (k == null || RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k) == null) {
                        en.child("enchantment").error("unknown enchantment '" + ench + "'");
                        continue;
                    }
                    ench = k.asString();
                }
                List<String> applies = new ArrayList<>();
                for (String a : en.stringList("applies-to")) {
                    Material m = Material.matchMaterial(a);
                    if (m == null) en.child("applies-to").error("unknown item '" + a + "'");
                    else applies.add(m.getKey().asString());
                }
                if (applies.isEmpty()) en.error("applies-to must list at least one item");
                entries.put(e.getKey(), new ShopEntry(e.getKey(), category, name, ShopEntry.Kind.ENCHANT, price, max, enabled,
                        null, 1, ench, level, applies, null));
            }
        }
        if (root.report().errors().size() > before) return Optional.empty();
        return Optional.of(new ShopDefinition(id, categories, entries));
    }
}
