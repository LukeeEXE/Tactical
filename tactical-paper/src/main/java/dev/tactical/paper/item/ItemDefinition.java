package dev.tactical.paper.item;

import dev.tactical.engine.config.ConfigNode;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A custom item from {@code items/*.yml}. Today one behaviour type exists, {@code shot}: right-click applies the
 * effects to yourself, left-clicking a player applies them to a teammate (positive shot) or an opponent (negative
 * shot) instead of damaging them. A shot never mixes positive and negative effects.
 */
public record ItemDefinition(String id, String type, Material material, String name, List<String> description,
                             TextColor color, double ringRadius, boolean trail, int cooldownTicks,
                             ItemEffect.Polarity polarity, List<ItemEffect> effects, String model) {

    /** Item model of potion shots unless {@code model} says otherwise: liquid tinted with the shot colour + bottle. */
    public static final String DEFAULT_SHOT_MODEL = "tactical:shot";

    public static final List<String> TYPES = List.of("shot");

    public ItemCategory category() {
        return ItemCategory.UTILITY;
    }

    /** Longest effect duration (for the particle trail and the {@code <duration>} lore placeholder). */
    public int durationTicks() {
        return effects.stream().mapToInt(ItemEffect::durationTicks).max().orElse(0);
    }

    /** Parses every item in one {@code items/<file>.yml} ({@code items: { <id>: {...} }}). */
    public static Optional<Map<String, ItemDefinition>> parseFile(ConfigNode root) {
        int before = root.report().errors().size();
        root.warnUnknownKeys(List.of("schema-version", "items"));
        Map<String, ItemDefinition> out = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigNode> e : root.child("items").children().entrySet()) {
            parse(e.getKey(), e.getValue()).ifPresent(d -> out.put(d.id(), d));
        }
        if (root.report().errors().size() > before) return Optional.empty();
        return Optional.of(out);
    }

    static Optional<ItemDefinition> parse(String id, ConfigNode n) {
        n.warnUnknownKeys(List.of("type", "material", "name", "description", "color", "ring-radius", "trail",
                "cooldown-ticks", "effects", "model"));
        if (!id.matches("[a-z0-9_]{1,32}")) {
            n.error("item id '" + id + "' must be 1-32 chars of a-z, 0-9, _");
            return Optional.empty();
        }
        String type = n.string("type", "shot").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            n.child("type").error("unknown item type '" + type + "' (known: " + String.join(", ", TYPES) + ")");
            return Optional.empty();
        }
        String matName = n.string("material", "potion");
        Material material = Material.matchMaterial(matName);
        if (material == null || !material.isItem()) {
            n.child("material").error("unknown item '" + matName + "'");
            return Optional.empty();
        }
        String colorText = n.string("color", "#3fd6e0");
        TextColor color = TextColor.fromHexString(colorText.startsWith("#") ? colorText : "#" + colorText);
        if (color == null) {
            n.child("color").error("colour must look like #3fd6e0");
            return Optional.empty();
        }
        List<ItemEffect> effects = new ArrayList<>();
        for (ConfigNode en : n.child("effects").list()) ItemEffects.parse(en).ifPresent(effects::add);
        if (effects.isEmpty()) {
            n.error("a shot needs at least one effect");
            return Optional.empty();
        }
        ItemEffect.Polarity polarity = effects.get(0).polarity();
        if (effects.stream().anyMatch(x -> x.polarity() != polarity)) {
            n.child("effects").error("a shot cannot mix positive and negative effects");
            return Optional.empty();
        }
        return Optional.of(new ItemDefinition(id, type, material, n.string("name", id), n.stringList("description"), color,
                n.number("ring-radius", 0.8, 0.2, 4.0), n.bool("trail", true), n.integer("cooldown-ticks", 20, 0, 20 * 60),
                polarity, List.copyOf(effects), model(n, material)));
    }

    /** {@code model}: an item model id, or "none" for the material's own look; default tactical:shot for potions. */
    private static String model(ConfigNode n, Material material) {
        boolean potion = material == Material.POTION || material == Material.SPLASH_POTION || material == Material.LINGERING_POTION;
        String m = n.string("model", potion ? DEFAULT_SHOT_MODEL : "none").trim().toLowerCase(Locale.ROOT);
        if (m.equals("none") || m.isEmpty()) return null;
        if (net.kyori.adventure.key.Key.parseable(m)) return m;
        n.child("model").error("not a valid item model id: '" + m + "'");
        return null;
    }
}
