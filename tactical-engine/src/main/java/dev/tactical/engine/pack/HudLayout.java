package dev.tactical.engine.pack;

/**
 * Vertical placement of HUD glyphs, baked into the pack fonts (change → {@code /game pack rebuild}).
 *
 * @param bossbarTileAscent  ascent of the 10x10 team tiles in the boss bar title (8 = 1 px above the heads)
 * @param bossbarPanelAscent ascent of the 11 px timer panel behind the boss bar text
 * @param moneyOffsetY       pixels from the action-bar text line down to the hunger-bar row
 */
public record HudLayout(int bossbarTileAscent, int bossbarPanelAscent, int moneyOffsetY) {
    public static final HudLayout DEFAULT = new HudLayout(8, 9, 33);
}
