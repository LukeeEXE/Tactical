package dev.tactical.paper.hud;

import dev.tactical.engine.config.ConfigNode;
import dev.tactical.engine.pack.HudLayout;

import java.util.List;

/**
 * HUD layout ({@code hud/hud.yml}). Values marked "pack" are baked into resource-pack fonts and need
 * {@code /game pack rebuild}; the others apply after {@code /game reload hud}.
 */
public record HudConfig(
        String bossbarFont,
        int bossbarTextOffsetY,
        int bossbarTileAscent,
        int bossbarPanelAscent,
        int headAdvance,
        int tileGap,
        int sectionGap,
        int panelPadding,
        int rowSpacing,
        boolean moneyEnabled,
        int moneyOffsetY,
        int moneyLeft
) {
    public static final int SCHEMA_VERSION = 1;

    public static HudConfig defaults() {
        return new HudConfig("pinch", 0, 8, 9, 9, 1, 8, 4, 19, true, 33, 10);
    }

    public HudLayout layout() {
        return new HudLayout(bossbarTileAscent, bossbarPanelAscent, moneyOffsetY);
    }

    public static HudConfig parse(ConfigNode root) {
        HudConfig d = defaults();
        root.warnUnknownKeys(List.of("schema-version", "bossbar", "money"));
        ConfigNode b = root.child("bossbar");
        b.warnUnknownKeys(List.of("font", "text-offset-y", "tile-ascent", "panel-ascent", "head-advance", "tile-gap",
                "section-gap", "panel-padding", "row-spacing"));
        ConfigNode m = root.child("money");
        m.warnUnknownKeys(List.of("enabled", "offset-y", "left"));
        return new HudConfig(
                b.string("font", d.bossbarFont()).toLowerCase(java.util.Locale.ROOT),
                b.integer("text-offset-y", d.bossbarTextOffsetY(), -64, 64),
                b.integer("tile-ascent", d.bossbarTileAscent(), -64, 10),
                b.integer("panel-ascent", d.bossbarPanelAscent(), -64, 11),
                b.integer("head-advance", d.headAdvance(), 6, 12),
                b.integer("tile-gap", d.tileGap(), 0, 8),
                b.integer("section-gap", d.sectionGap(), 0, 64),
                b.integer("panel-padding", d.panelPadding(), 0, 16),
                b.integer("row-spacing", d.rowSpacing(), 1, 64),
                m.bool("enabled", d.moneyEnabled()),
                m.integer("offset-y", d.moneyOffsetY(), 1, 128),
                m.integer("left", d.moneyLeft(), -200, 200));
    }
}
