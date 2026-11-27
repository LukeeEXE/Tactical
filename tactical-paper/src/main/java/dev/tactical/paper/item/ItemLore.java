package dev.tactical.paper.item;

import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.paper.hud.HudShapes;
import dev.tactical.paper.hud.TextOffsets;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Item tooltips in one style:
 * <pre>
 * Spark of Speed            (the item name)
 * [UTILITY]                 (category tag: Pinch text on a bordered box, tinted per category)
 *
 * Increase your movement speed by 60% for 1.8s.     (green description, values in white)
 *
 * [mouse] > Right-Click to Use                      (usage lines, yellow)
 * [mouse] > Left-Click to Apply to Other Player
 * </pre>
 */
public final class ItemLore {

    public static final TextColor DESCRIPTION = TextColor.color(0x5BE36F);
    public static final TextColor USAGE = TextColor.color(0xF2D24B);
    private static final TextColor TAG_TEXT = TextColor.color(0xE4E8FA);
    private static final int TAG_PADDING = 3;

    /** A usage line under the description. */
    public enum Usage {
        RIGHT_CLICK_USE(HudGlyphs.MOUSE_RIGHT, "Right-Click to Use"),
        LEFT_CLICK_APPLY(HudGlyphs.MOUSE_LEFT, "Left-Click to Apply to Other Player");

        final char icon;
        final String text;

        Usage(char icon, String text) {
            this.icon = icon;
            this.text = text;
        }
    }

    private static volatile ToIntFunction<String> pinchWidth = s -> s.length() * 5;
    private static volatile String pinchFont = "pinch";

    private ItemLore() {
    }

    /** Width of a Pinch text in GUI pixels (advance, includes the trailing 1 px). */
    public static int pinchWidth(String text) {
        return pinchWidth.applyAsInt(text);
    }

    public static String pinchFont() {
        return pinchFont;
    }

    /** Called once at startup with the HUD's TTF measurement. */
    public static void install(ToIntFunction<String> width, String font) {
        pinchWidth = width;
        pinchFont = font;
    }

    /** The category tag: Pinch capitals centred on a bordered box (zero net surprises: advance = box width). */
    public static Component tag(ItemCategory category) {
        return tag(category.label(), category.color());
    }

    /** A tag in the same style with any label and box colour (e.g. a kit's category). */
    public static Component tag(String label, TextColor color) {
        int advance = pinchWidth.applyAsInt(label);
        int box = Math.max(1, advance - 1) + 2 * TAG_PADDING;
        Component text = Component.text(label, TAG_TEXT).font(Key.key("tactical", pinchFont));
        return Component.text()
                .append(HudShapes.rect(HudGlyphs.TOOLTIP_TAG, box, "hud_tooltip", color))
                .append(TextOffsets.space(-box + TAG_PADDING)).append(text)
                .append(TextOffsets.space(box - TAG_PADDING - advance))
                .build().shadowColor(ShadowColor.none());
    }

    /** Full lore: tag, blank, description, blank, usage lines (sections left out when empty). */
    public static List<Component> lines(ItemCategory category, List<Component> description, List<Usage> usages) {
        List<Component> out = new ArrayList<>();
        out.add(tag(category));
        if (!description.isEmpty()) {
            out.add(Component.empty());
            out.addAll(description);
        }
        if (!usages.isEmpty()) {
            out.add(Component.empty());
            for (Usage u : usages) {
                out.add(Component.text()
                        .append(Component.text(String.valueOf(u.icon)).font(Key.key("tactical", "hud_chat")).color(NamedTextColor.WHITE))
                        .append(Component.text(" > ", NamedTextColor.GRAY))
                        .append(Component.text(u.text, USAGE)).build());
            }
        }
        return out.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList();
    }

    /** Lore for a plain vanilla item handed out in a match (weapons and armor); hides attribute and unbreakable lines. */
    public static ItemStack decorate(ItemStack item) {
        ItemCategory c = ItemCategory.of(item.getType());
        if (c != ItemCategory.WEAPON && c != ItemCategory.ARMOR) return item; // ammo stays plain so picked-up arrows stack
        item.lore(lines(c, List.of(), List.of()));
        hideVanillaLines(item);
        return item;
    }

    public static void hideVanillaLines(ItemStack item) {
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.UNBREAKABLE,
                        DataComponentTypes.POTION_CONTENTS)
                .build());
    }
}
