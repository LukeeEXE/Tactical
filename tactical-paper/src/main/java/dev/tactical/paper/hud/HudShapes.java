package dev.tactical.paper.hud;

import dev.tactical.engine.pack.HudGlyphs;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

/** Pixel-exact building blocks for HUD text: rectangles of any width and zero-advance centring. */
public final class HudShapes {

    private HudShapes() {
    }

    /**
     * A rectangle exactly {@code width} px wide (advance = width) drawn as a single glyph of {@code set},
     * tinted with {@code color} (black for panels, red/blue for team strips). No shadow.
     */
    public static Component rect(HudGlyphs.RectSet set, int width, String font, TextColor color) {
        Key key = Key.key("tactical", font);
        TextComponent.Builder b = Component.text();
        int left = Math.max(0, width);
        while (left > 0) { // one glyph per rectangle (two only beyond RECT_MAX px)
            int w = Math.min(left, HudGlyphs.RECT_MAX);
            b.append(Component.text(String.valueOf(set.glyph(w))).font(key)).append(TextOffsets.space(-1)); // bitmap advance = w + 1
            left -= w;
        }
        return b.build().color(color).shadowColor(ShadowColor.none());
    }

    /** Draws {@code c} (which is {@code width} px wide) centred on the current position, with a net advance of 0. */
    public static Component centred(Component c, int width) {
        int a = width / 2;
        return Component.text().append(TextOffsets.space(-a)).append(c).append(TextOffsets.space(-(width - a))).build();
    }
}
