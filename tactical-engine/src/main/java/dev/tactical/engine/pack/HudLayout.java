package dev.tactical.engine.pack;

/**
 * Vertical placement of HUD glyphs, baked into the pack fonts (change → {@code /game pack rebuild}).
 *
 * @param bossbarTileAscent    ascent of the 10 px team strip behind the heads (8 = 1 px above the heads)
 * @param bossbarPanelAscent   ascent of the 11 px timer panel
 * @param bossbarScoreAscent   ascent of the 16 px score box
 * @param moneyOffsetY         pixels from the action-bar text line down to the hunger-bar row
 * @param actionPanelAscent    ascent of the 22 px two-line hint box on the action bar
 */
public record HudLayout(int bossbarTileAscent, int bossbarPanelAscent, int bossbarScoreAscent, int moneyOffsetY,
                        int actionPanelAscent) {
    public static final HudLayout DEFAULT = new HudLayout(8, 9, 9, 33, 9);
}
