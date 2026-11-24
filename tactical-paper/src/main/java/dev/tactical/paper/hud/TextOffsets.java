package dev.tactical.paper.hud;

import dev.tactical.engine.pack.PackBuilder;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * Pixel-precise positioning of text, backed by fonts in the server resource pack:
 * <ul>
 *   <li>Horizontal: Negative Space Font by AmberW (CC BY 4.0), translation keys {@code space.<px>} and
 *       {@code offset.<px>}, from -8192 to 8192 pixels.</li>
 *   <li>Vertical: generated fonts {@code tactical:offset_down_<1..128>} and {@code tactical:offset_up_1}.</li>
 * </ul>
 * Every helper falls back to nothing if a client does not have the pack, so text never shows raw keys.
 */
public final class TextOffsets {

    private TextOffsets() {
    }

    /** Moves everything after it {@code px} pixels right (negative = left). */
    public static Component space(int px) {
        if (px == 0) return Component.empty();
        int clamped = Math.max(-8192, Math.min(8192, px));
        return Component.translatable().key("space." + clamped).fallback("").build();
    }

    /** Draws {@code content} shifted {@code px} pixels horizontally without moving the text around it. */
    public static Component offset(int px, Component content) {
        if (px == 0) return content;
        int clamped = Math.max(-8192, Math.min(8192, px));
        return Component.translatable().key("offset." + clamped).arguments(content).fallback("").build();
    }

    /**
     * Renders {@code content} in a TrueType font from {@code resourcepack/fonts/} (e.g. {@code "pinch"}), shifted
     * {@code px} pixels down (negative = up). TTF fonts can move both ways, up to their configured max-offset.
     */
    public static Component ttf(String font, int px, Component content) {
        String id = px == 0 ? font : px > 0 ? font + "_down_" + px : font + "_up_" + (-px);
        return content.font(Key.key("tactical", id));
    }

    /**
     * Shifts printable-ASCII text down by {@code px} pixels (1..128), or up by 1 with {@code px = -1}.
     * Applies to text only; inline heads/sprites are not affected by fonts.
     */
    public static Component vertical(int px, Component content) {
        if (px == 0) return content;
        if (px < -1 || px > PackBuilder.MAX_DOWN_OFFSET) {
            throw new IllegalArgumentException("vertical offset must be -1..." + PackBuilder.MAX_DOWN_OFFSET + " (was " + px + ")");
        }
        String font = px < 0 ? "offset_up_1" : "offset_down_" + px;
        return content.font(Key.key("tactical", font));
    }
}
