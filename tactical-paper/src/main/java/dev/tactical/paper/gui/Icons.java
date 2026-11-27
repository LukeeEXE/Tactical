package dev.tactical.paper.gui;

import dev.tactical.engine.config.ConfigNode;
import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.paper.config.KitDefinition;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Every menu item comes from here, never from hardcoded ItemStacks in menu code. Each icon is a PLACEHOLDER vanilla
 * item carrying Custom Model Data strings, so a resource pack can give it its own texture:
 * <ul>
 *   <li>UI icons: {@code strings[0] = "tactical:icon/<key>"} (materials overridable in {@code gui/icons.yml})</li>
 *   <li>Kit logos: {@code strings[0] = "tactical:kit/<kit_id>"}, {@code strings[1] = "state:<state>"}
 *       (states: available, selected, locked, taken, unavailable) so every state can have its own texture.</li>
 *   <li>Map logos: {@code "tactical:map/<map_id>"}.</li>
 * </ul>
 * All menu items are tagged so one that escapes a menu is recognised and destroyed.
 */
public final class Icons {

    private static NamespacedKey menuKey;

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("ui.filler", "gray_stained_glass_pane"),
            Map.entry("ui.close", "barrier"),
            Map.entry("ui.back", "arrow"),
            Map.entry("ui.next_page", "arrow"),
            Map.entry("ui.previous_page", "arrow"),
            Map.entry("ui.info", "book"),
            Map.entry("ui.timer", "clock"),
            Map.entry("ui.money", "gold_nugget"),
            Map.entry("ui.player_unknown", "skeleton_skull"),
            Map.entry("kit.lock", "lime_concrete"),
            Map.entry("kit.lock_disabled", "gray_concrete"),
            Map.entry("kit.skip", "feather"),
            Map.entry("kit.skip_voted", "lime_dye"),
            Map.entry("kit.opener", "nether_star"),
            Map.entry("shop.opener", "emerald"),
            Map.entry("shop.category", "chest"),
            Map.entry("shop.sold_out", "barrier"));

    private final Logger log;
    private final Map<String, Material> materials = new HashMap<>();

    public Icons(Plugin plugin, Path file, Logger log) {
        this.log = log;
        menuKey = new NamespacedKey(plugin, "menu_item");
        DEFAULTS.forEach((k, v) -> materials.put(k, material(v, Material.PAPER)));
        if (Files.exists(file)) {
            ConfigReport r = new ConfigReport();
            YamlLoader.load(file, "gui/icons.yml", r).ifPresent(root -> {
                ConfigNode icons = root.child("icons");
                for (String key : icons.keys()) {
                    ConfigNode n = icons.child(key);
                    String m = n.isMap() ? n.string("material", null) : n.scalar().orElse(null);
                    Material mat = m == null ? null : material(m, null);
                    if (mat == null) n.error("unknown item '" + m + "'");
                    else materials.put(key, mat);
                }
            });
            r.issues().forEach(i -> log.warning(i.toString()));
        }
    }

    public static boolean isMenuItem(ItemStack item) {
        return menuKey != null && item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.BYTE);
    }

    public ItemStack icon(String key, Component name, List<Component> lore) {
        Material m = materials.getOrDefault(key, Material.PAPER);
        return build(m, name, lore, List.of("tactical:icon/" + key), false);
    }

    public ItemStack kit(KitDefinition kit, String state, Component name, List<Component> lore, boolean glow) {
        Material m = material(kit.iconMaterial(), Material.IRON_SWORD);
        String model = kit.iconModel();
        boolean logo = model != null && !model.isBlank();
        // Bundled logos have variants: the locked art when a teammate locked it, generated grey when not available, an animated grey shine when picked
        // (instead of the enchantment glint).
        boolean variants = logo && model.startsWith("tactical:kit_icon_");
        String suffix = !variants ? "" : switch (state) {
            case "taken" -> "_locked";
            case "unavailable" -> "_gray";
            case "selected", "locked" -> "_selected";
            default -> "";
        };
        ItemStack item = build(m, name, lore, List.of("tactical:kit/" + kit.id(), "state:" + state), glow && !variants);
        if (logo) item.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(model + suffix));
        return item;
    }

    public ItemStack head(UUID player, String playerName, boolean real, Component name, List<Component> lore) {
        if (!real) return icon("ui.player_unknown", name, lore);
        ItemStack item = build(Material.PLAYER_HEAD, name, lore, List.of("tactical:icon/player_head"), false);
        item.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("tactical", "player_head"));
        item.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().uuid(player).name(playerName)
                .addProperties(dev.tactical.paper.hud.Heads.textures(player, playerName)).build());
        return item;
    }

    private static ItemStack build(Material m, Component name, List<Component> lore, List<String> cmd, boolean glow) {
        ItemStack item = new ItemStack(m);
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addStrings(cmd).build());
        if (glow) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.lore(lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    static Material material(String id, Material fallback) {
        if (id == null) return fallback;
        String k = id.toLowerCase(Locale.ROOT);
        NamespacedKey key = k.contains(":") ? NamespacedKey.fromString(k) : NamespacedKey.minecraft(k);
        ItemType type = key == null ? null : Registry.ITEM.get(key);
        Material m = type == null ? null : Material.matchMaterial(key.asString());
        return m != null && m.isItem() ? m : fallback;
    }
}
