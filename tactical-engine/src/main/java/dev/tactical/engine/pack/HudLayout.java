package dev.tactical.engine.pack;

/**
 * Vertical placement of HUD glyphs, baked into the pack fonts (change -> {@code /game pack rebuild}).
 * "Row" values count pixels down from the top of the text line (a player head covers rows 0-7, Pinch capitals rows 2-6).
 *
 * @param bossbarTileAscent    ascent of the 10 px team strip behind the heads (9 = rows -2..7; the game draws inline heads on rows -1..6)
 * @param bossbarPanelAscent   ascent of the 13 px timer panel (9 = rows -2..10)
 * @param bossbarScoreAscent   ascent of the 10 px score and timer boxes (9 = rows -2..7, like the strip)
 * @param bossbarTimeRow       row of the 1 px timer and health lines (8 = right under the bar)
 * @param moneyOffsetY         pixels from the action-bar text line down to the hunger-bar row
 * @param actionPanelAscent    ascent of the 22 px two-line hint box on the action bar (19 = rows -12..9)
 */
public record HudLayout(int bossbarTileAscent, int bossbarPanelAscent, int bossbarScoreAscent, int bossbarTimeRow,
                        int moneyOffsetY, int actionPanelAscent) {
    public static final HudLayout DEFAULT = new HudLayout(9, 9, 9, 8, 33, 19);
}
