package dev.tactical.paper.hud;

import dev.tactical.engine.config.ConfigNode;
import dev.tactical.engine.pack.HudLayout;

import java.util.List;

/**
 * HUD layout ({@code hud/hud.yml}). Values marked "pack" are baked into resource-pack fonts and need
 * {@code /game pack rebuild}; the others apply after {@code /game reload hud}.
 * <p>
 * Vertical positions are "rows": pixels down from the top of a text line. An inline player head covers rows 0-7 and
 * cannot be moved, so the boss bar is laid out around it; Pinch capitals cover rows 2-6 unless shifted.
 */
public record HudConfig(
        String bossbarFont,
        int bossbarTextOffsetY,
        int bossbarTileAscent,
        int bossbarPanelAscent,
        int scoreAscent,
        int timeRow,
        int headAdvance,
        int headSlot,
        int stripPadding,
        int sectionGap,
        int panelPadding,
        int rowSpacing,
        int scoreOffsetY,
        int timerSlotWidth,
        int actionPanelAscent,
        int actionHintWidth,
        int actionLine1Offset,
        int actionLine2Offset,
        boolean moneyEnabled,
        int moneyOffsetY,
        int moneyTextOffset,
        int moneyLeft
) {
    /** 3: top-aligned layout (older files are replaced by the new defaults, with a backup). */
    public static final int SCHEMA_VERSION = 3;

    public static HudConfig defaults() {
        return new HudConfig("pinch", 0, 9, 9, 9, 11, 9, 10, 3, 4, 4, 19, 5, 0,
                16, 160, -7, 2, true, 33, 32, 45);
    }

    public HudLayout layout() {
        return new HudLayout(bossbarTileAscent, bossbarPanelAscent, scoreAscent, timeRow, moneyOffsetY, actionPanelAscent);
    }

    public static HudConfig parse(ConfigNode root) {
        HudConfig d = defaults();
        root.warnUnknownKeys(List.of("schema-version", "bossbar", "money", "actionbar"));
        ConfigNode b = root.child("bossbar");
        b.warnUnknownKeys(List.of("font", "text-offset-y", "tile-ascent", "panel-ascent", "score-ascent", "time-row",
                "head-advance", "head-slot", "strip-padding", "section-gap", "panel-padding", "row-spacing",
                "score-offset-y", "timer-width"));
        ConfigNode ab = root.child("actionbar");
        ab.warnUnknownKeys(List.of("panel-ascent", "hint-width", "line1-offset-y", "line2-offset-y"));
        ConfigNode m = root.child("money");
        m.warnUnknownKeys(List.of("enabled", "offset-y", "text-offset-y", "left"));
        return new HudConfig(
                b.string("font", d.bossbarFont()).toLowerCase(java.util.Locale.ROOT),
                b.integer("text-offset-y", d.bossbarTextOffsetY(), -64, 64),
                b.integer("tile-ascent", d.bossbarTileAscent(), -64, 10),
                b.integer("panel-ascent", d.bossbarPanelAscent(), -64, 13),
                b.integer("score-ascent", d.scoreAscent(), -64, 18),
                b.integer("time-row", d.timeRow(), -64, 64),
                b.integer("head-advance", d.headAdvance(), 6, 12),
                b.integer("head-slot", d.headSlot(), 8, 16),
                b.integer("strip-padding", d.stripPadding(), 0, 16),
                b.integer("section-gap", d.sectionGap(), 0, 64),
                b.integer("panel-padding", d.panelPadding(), 0, 16),
                b.integer("row-spacing", d.rowSpacing(), 1, 64),
                b.integer("score-offset-y", d.scoreOffsetY(), -32, 32),
                b.integer("timer-width", d.timerSlotWidth(), 0, 256),
                ab.integer("panel-ascent", d.actionPanelAscent(), -64, 21),
                ab.integer("hint-width", d.actionHintWidth(), 0, 256),
                ab.integer("line1-offset-y", d.actionLine1Offset(), -64, 64),
                ab.integer("line2-offset-y", d.actionLine2Offset(), -64, 64),
                m.bool("enabled", d.moneyEnabled()),
                m.integer("offset-y", d.moneyOffsetY(), 1, 128),
                m.integer("text-offset-y", d.moneyTextOffset(), -64, 128),
                m.integer("left", d.moneyLeft(), -200, 200));
    }
}
