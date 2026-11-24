package dev.tactical.engine.pack;

/**
 * Vertical placement of HUD glyphs, baked into the pack fonts (change -> {@code /game pack rebuild}).
 * "Row" values count pixels down from the top of the text line (a player head covers rows 0-7, Pinch capitals rows 2-6).
 *
 * @param bossbarTileAscent    ascent of the 12 px team strip behind the heads (9 = rows -2..9, 2 px around the heads)
 * @param bossbarPanelAscent   ascent of the 13 px timer panel (9 = rows -2..10, 4 px around the capitals)
 * @param bossbarScoreAscent   ascent of the 18 px square score box (12 = rows -5..12)
 * @param bossbarTimeRow       row of the 1 px timer bar under the panel
 * @param moneyOffsetY         pixels from the action-bar text line down to the hunger-bar row
 * @param actionPanelAscent    ascent of the 21 px two-line hint box on the action bar (16 = rows -9..11)
 */
public record HudLayout(int bossbarTileAscent, int bossbarPanelAscent, int bossbarScoreAscent, int bossbarTimeRow,
                        int moneyOffsetY, int actionPanelAscent) {
    public static final HudLayout DEFAULT = new HudLayout(9, 9, 12, 12, 33, 16);
}
