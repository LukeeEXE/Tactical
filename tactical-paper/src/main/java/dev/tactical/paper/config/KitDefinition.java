package dev.tactical.paper.config;

import dev.tactical.engine.config.ConfigNode;
import dev.tactical.paper.shop.ShopDefinition;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A kit ({@code kits/<id>.yml}): identity, the BASE KIT every player of the kit gets at the start of each round,
 * and the kit's own SHOP (the same on every map).
 */
public record KitDefinition(String id, String name, String description, boolean enabled, String iconMaterial,
                            String iconModel, Category category, List<KitItem> baseKit, ShopDefinition shop) {

    /** Row of the kit menu the kit is listed in. */
    public enum Category {
        DEFENSE("Defense", 0x5B8CE6), DAMAGE("Damage", 0xE05A5A), SUPPORT("Support", 0x5BC46B);

        private final String displayName;
        private final int color;

        Category(String displayName, int color) {
            this.displayName = displayName;
            this.color = color;
        }

        /** Tag colour: defense blue, damage red, support green. */
        public int color() {
            return color;
        }

        public String displayName() {
            return displayName;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 4: the new kit roster (older files are replaced by the bundled defaults, or removed if that kit was retired;
     * either way backed up first).
     */
    public static final int SCHEMA_VERSION = 4;
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    /**
     * One base-kit item. {@code slot}: hotbar 0-8, inventory 9-35, {@code helmet}, {@code chestplate},
     * {@code leggings}, {@code boots}, {@code offhand}, or {@code auto} (first free slot; armor is worn).
     * Exactly one of {@code material} (vanilla) and {@code item} (custom item id) is set.
     */
    public record KitItem(String slot, String material, String item, int amount) {
        public static final List<String> NAMED_SLOTS = List.of("helmet", "chestplate", "leggings", "boots", "offhand", "auto");
    }

    public static Optional<KitDefinition> parse(ConfigNode root, String expectedId) {
        int before = root.report().errors().size();
        root.warnUnknownKeys(List.of("schema-version", "id", "name", "description", "enabled", "icon", "category", "base-kit", "shop"));
        String id = root.string("id", expectedId);
        if (!id.equals(expectedId)) root.child("id").error("must match the file name '" + expectedId + "'");
        if (!ID.matcher(id).matches()) root.child("id").error("must be 1-32 chars of a-z, 0-9, _");
        String name = root.string("name", id);
        String description = root.string("description", "");
        boolean enabled = root.bool("enabled", true);
        String icon = root.child("icon").string("material", "iron_sword");
        String iconModel = root.child("icon").has("model") ? root.child("icon").string("model", "") : null;
        Category category = Category.DAMAGE;
        String cat = root.string("category", defaultCategory(id)).toUpperCase(Locale.ROOT);
        try {
            category = Category.valueOf(cat);
        } catch (IllegalArgumentException e) {
            root.child("category").error("must be defense, damage or support");
        }
        List<KitItem> base = new ArrayList<>();
        for (ConfigNode n : root.child("base-kit").list()) {
            n.warnUnknownKeys(List.of("slot", "material", "item", "amount"));
            String slot = n.string("slot", "auto").toLowerCase(Locale.ROOT);
            boolean numeric = slot.matches("\\d{1,2}") && Integer.parseInt(slot) <= 35;
            if (!numeric && !KitItem.NAMED_SLOTS.contains(slot)) {
                n.child("slot").error("slot must be 0-35, helmet, chestplate, leggings, boots, offhand or auto");
                continue;
            }
            if (n.has("material") == n.has("item")) {
                n.error("needs exactly one of 'material' (vanilla item) or 'item' (custom item from items/*.yml)");
                continue;
            }
            int amount = n.integer("amount", 1, 1, 64);
            if (n.has("material")) {
                String mat = n.requireString("material");
                Material m = mat == null ? null : Material.matchMaterial(mat);
                if (m == null || !m.isItem()) {
                    n.child("material").error("unknown item '" + mat + "'");
                    continue;
                }
                base.add(new KitItem(slot, m.getKey().asString(), null, amount));
            } else {
                String item = n.requireString("item");
                if (item != null) base.add(new KitItem(slot, null, item.toLowerCase(Locale.ROOT), amount));
            }
        }
        ShopDefinition shop = ShopDefinition.parseKitShop(root.child("shop"), id);
        if (root.report().errors().size() > before) return Optional.empty();
        return Optional.of(new KitDefinition(id, name, description, enabled, icon, iconModel, category, List.copyOf(base), shop));
    }

    /** Category of the bundled kits when their file has no {@code category} yet. */
    private static String defaultCategory(String id) {
        return switch (id) {
            case "bruiser", "tactician" -> "defense";
            case "medic", "specialist" -> "support";
            default -> "damage";
        };
    }
}
