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
        int scoreAscent,
        int scoreOffsetY,
        int timerWidth,
        int headSlot,
        int stripPadding,
        int actionPanelAscent,
        boolean moneyEnabled,
        int moneyOffsetY,
        int moneyLeft
) {
    public static final int SCHEMA_VERSION = 1;

    public static HudConfig defaults() {
        return new HudConfig("pinch", 0, 8, 9, 9, 1, 6, 4, 19, 9, 3, 0, 10, 2, 9, true, 33, 10);
    }

    public HudLayout layout() {
        return new HudLayout(bossbarTileAscent, bossbarPanelAscent, scoreAscent, moneyOffsetY, actionPanelAscent);
    }

    public static HudConfig parse(ConfigNode root) {
        HudConfig d = defaults();
        root.warnUnknownKeys(List.of("schema-version", "bossbar", "money", "actionbar"));
        ConfigNode b = root.child("bossbar");
        b.warnUnknownKeys(List.of("font", "text-offset-y", "tile-ascent", "panel-ascent", "head-advance", "tile-gap",
                "section-gap", "panel-padding", "row-spacing", "score-ascent", "score-offset-y", "timer-width", "head-slot",
                "strip-padding"));
        ConfigNode ab = root.child("actionbar");
        ab.warnUnknownKeys(List.of("panel-ascent"));
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
                b.integer("score-ascent", d.scoreAscent(), -64, 16),
                b.integer("score-offset-y", d.scoreOffsetY(), -32, 32),
                b.integer("timer-width", d.timerWidth(), 0, 512),
                b.integer("head-slot", d.headSlot(), 8, 16),
                b.integer("strip-padding", d.stripPadding(), 0, 16),
                ab.integer("panel-ascent", d.actionPanelAscent(), -64, 22),
                m.bool("enabled", d.moneyEnabled()),
                m.integer("offset-y", d.moneyOffsetY(), 1, 128),
                m.integer("left", d.moneyLeft(), -200, 200));
    }
}
