package dev.tactical.engine.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Original pixel art and shapes for the HUD and chat, generated into the resource pack. Each glyph has a private-use
 * character the plugin inserts into text. Any piece can be replaced by dropping a PNG with the same name into
 * {@code resourcepack/assets/tactical/textures/hud/}.
 *
 * <p>Backgrounds are built from WHITE translucent rectangle pieces (1..128 px wide, powers of two). Text colour
 * multiplies glyph colour, so the same pieces give black panels or red/blue team strips. Corners are square.
 */
public final class HudGlyphs {

    /** Where a glyph is drawn; decides its font and vertical placement. */
    public enum Slot { CHAT, BAR_STRIP, BAR_ICON, BAR_PANEL, BAR_SCORE, BAR_TIME, BAR_BOMB, PLANT_BAR, MONEY_PANEL, MONEY_ICON, ACTION_PANEL }

    public record Glyph(String name, char ch, Slot slot, int width, int height, BufferedImage image) {
    }

    /**
     * A solid rectangle available in every width from 1 to {@link #RECT_MAX} px as ONE glyph each (codepoint
     * {@code base + width - 1}), so a background is a single quad: no seams between pieces at any GUI scale.
     */
    public record RectSet(String name, char base, Slot slot, int height, int alpha, Shape shape) {
        public RectSet(String name, char base, Slot slot, int height, int alpha) {
            this(name, base, slot, height, alpha, Shape.RECT);
        }

        public char glyph(int width) {
            if (width < 1 || width > RECT_MAX) throw new IllegalArgumentException("rect width 1.." + RECT_MAX + ": " + width);
            return (char) (base + width - 1);
        }
    }

    public static final int RECT_MAX = 256;

    /** RECT: rounded rectangle. POINT_LEFT / POINT_RIGHT: one end cut at 45 degrees to a point (top row longest). */
    public enum Shape { RECT, POINT_LEFT, POINT_RIGHT }

    // ---- private-use characters (stable: the plugin and the pack agree on these)
    public static final char CHAT_KILL = '\uE200', CHAT_ROUND = '\uE201', CHAT_WIN = '\uE202', CHAT_LOSS = '\uE203',
            CHAT_BOMB = '\uE204', CHAT_DEFUSE = '\uE205', CHAT_TIMER = '\uE206', CHAT_WARNING = '\uE207',
            CHAT_COIN = '\uE208', CHAT_KIT = '\uE209', CHAT_INFO = '\uE20A',
            CHAT_ARROW = '\uE20D', STAT_KILLS = '\uE210', STAT_DEATHS = '\uE211',
            STAT_ASSISTS = '\uE212', STAT_DAMAGE = '\uE213', STAT_KDR = '\uE214', CHAT_MVP = '\uE215',
            ROUND_WIN = '\uE216', ROUND_LOSS = '\uE217', ROUND_DIVIDER = '\uE218', CHAT_TEAM = '\uE219';
    public static final char BAR_SKULL = '\uE223';
    public static final char MONEY_PANEL = '\uE240', MONEY_COIN = '\uE241';
    /** Plant/defuse progress segments (2x7): empty, half, full. */
    public static final char PLANT_EMPTY = '\uE720', PLANT_HALF = '\uE721', PLANT_FULL = '\uE722';
    public static final int PLANT_SEGMENT = 2;
    /** Large bomb icon on its own boss bar while the bomb is planted. */
    public static final char BAR_BOMB = '\uE710';

    /** Team strip behind the heads (soft; tinted red/blue). */
    public static final RectSet BAR_STRIP = new RectSet("bar_strip", '\uE400', Slot.BAR_STRIP, 10, 0x66);
    /** Team strips with a pointed outer end: left team points left, right team points right. */
    public static final RectSet STRIP_POINT_LEFT = new RectSet("strip_left", '\uEA00', Slot.BAR_STRIP, 10, 0x66, Shape.POINT_LEFT);
    public static final RectSet STRIP_POINT_RIGHT = new RectSet("strip_right", '\uEB00', Slot.BAR_STRIP, 10, 0x66, Shape.POINT_RIGHT);
    /** Extra width of a pointed end (one pixel per row). */
    public static final int STRIP_POINT = 9;
    /** Timer panel (tinted black). */
    public static final RectSet BAR_PANEL = new RectSet("bar_panel", '\uE300', Slot.BAR_PANEL, 13, 0x8C);
    /** Score box (tinted black). */
    public static final RectSet BAR_SCORE = new RectSet("bar_score", '\uE500', Slot.BAR_SCORE, 18, 0x8C);
    /** Two-line action-bar hint box (tinted black). */
    public static final RectSet ACTION_PANEL = new RectSet("action_panel", '\uE600', Slot.ACTION_PANEL, 21, 0x8C);
    /** 1 px timer line under the timer panel, any width: filled part (tinted #FFFF00) and empty part (tinted black, 50 %). */
    public static final RectSet TIME_FILL = new RectSet("time_fill", '\uE800', Slot.BAR_TIME, 1, 0xFF);
    public static final RectSet TIME_EMPTY = new RectSet("time_empty", '\uE900', Slot.BAR_TIME, 1, 0x80);
    public static final List<RectSet> RECT_SETS = List.of(BAR_STRIP, STRIP_POINT_LEFT, STRIP_POINT_RIGHT, BAR_PANEL,
            BAR_SCORE, ACTION_PANEL, TIME_FILL, TIME_EMPTY);

    public static final int MONEY_PANEL_WIDTH = 46, MONEY_PANEL_HEIGHT = 11;

    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
            Map.entry('k', 0xFF1B1B1F), Map.entry('w', 0xFFF4F4F4), Map.entry('g', 0xFFB8B8C0), Map.entry('G', 0xFF5C5C66),
            Map.entry('y', 0xFFFFD23F), Map.entry('o', 0xFFE0921E), Map.entry('r', 0xFFE5483F), Map.entry('R', 0xFF9E2620),
            Map.entry('b', 0xFF3F7FE5), Map.entry('B', 0xFF1F4FA0), Map.entry('c', 0xFF55E0E0), Map.entry('n', 0xFF5BD158),
            Map.entry('N', 0xFF2E8A2C));

    private HudGlyphs() {
    }

    public static List<Glyph> all() {
        List<Glyph> out = new ArrayList<>(List.of(
                image("chat_kill", CHAT_KILL, Slot.CHAT, "pack/hud/skull.png"),
                art("chat_round", CHAT_ROUND, Slot.CHAT,
                        "kk......", "knnnnk..", "knnNnnk.", "knnnnnnk", "knnnnk..", "kk......", "k.......", "k......."),
                art("chat_win", CHAT_WIN, Slot.CHAT,
                        "kkkkkkkk", "kyyyyyok", ".kyyyok.", "..kyok..", "...ko...", "...ko...", "..kook..", ".kkkkkk."),
                art("chat_loss", CHAT_LOSS, Slot.CHAT,
                        "kk....kk", "krk..krk", ".krkkrk.", "..krrk..", "..krrk..", ".krkkrk.", "krk..krk", "kk....kk"),
                art("chat_bomb", CHAT_BOMB, Slot.CHAT,
                        "......yo", ".....r..", "..kkkk..", ".kGGGGk.", "kGgGGGGk", "kGGGGGGk", ".kGGGGk.", "..kkkk.."),
                art("chat_defuse", CHAT_DEFUSE, Slot.CHAT,
                        ".kkk....", "kcckk...", "kc.ckk..", "kkckcck.", "..kccck.", "...kcccc", "....kcck", ".....kk."),
                art("chat_timer", CHAT_TIMER, Slot.CHAT,
                        "kkkkkkkk", ".kyyyyk.", "..kyyk..", "...kk...", "...kk...", "..k..k..", ".kyyyyk.", "kkkkkkkk"),
                image("chat_warning", CHAT_WARNING, Slot.CHAT, "pack/hud/warning.png"),
                art("chat_coin", CHAT_COIN, Slot.CHAT,
                        "..kkkk..", ".kyyyyk.", "kyywyyok", "kywyyyok", "kyyyyyok", "kyyyyook", ".kooook.", "..kkkk.."),
                art("chat_kit", CHAT_KIT, Slot.CHAT,
                        "...kk...", "...yy...", "kkyyyykk", ".kyyyyk.", "..kyyk..", ".kyyyyk.", ".ky..yk.", ".k....k."),
                art("chat_info", CHAT_INFO, Slot.CHAT,
                        "...kk...", "...cc...", "........", "..kcck..", "...cc...", "...cc...", "..kcck..", "..kkkk.."),
                art("chat_arrow", CHAT_ARROW, Slot.CHAT,
                        "........", "g.......", "g.......", "g.......", "g....g..", "ggggggg.", ".....g..", "........"),
                image("stat_kills", STAT_KILLS, Slot.CHAT, "pack/hud/stat_kills.png"),
                image("stat_deaths", STAT_DEATHS, Slot.CHAT, "pack/hud/skull.png"),
                image("stat_assists", STAT_ASSISTS, Slot.CHAT, "pack/hud/stat_assists.png"),
                art("stat_damage", STAT_DAMAGE, Slot.CHAT,
                        "........", ".rr.rr..", "rrrrrrr.", "rrrrrrr.", ".rrrrr..", "..rrr...", "...r....", "........"),
                art("stat_kdr", STAT_KDR, Slot.CHAT,
                        "........", "......w.", ".....w..", "....w...", "...w....", "..w.....", ".w......", "........"),
                art("chat_mvp", CHAT_MVP, Slot.CHAT,
                        "...yy...", "...yy...", "yyyyyyyy", ".yyyyyy.", "..yyyy..", ".yy..yy.", ".y....y.", "........"),
                art("round_win", ROUND_WIN, Slot.CHAT,
                        "kkkkkkkk", "kwnnnnwk", "kwnnnnwk", "kwnwwnwk", "kwwnnwwk", "kwnnnnwk", "knnnnnnk", "kkkkkkkk"),
                art("round_loss", ROUND_LOSS, Slot.CHAT,
                        "kkkkkkkk", "krwrrrrk", "krwrrrrk", "krwrrrrk", "krwrrrrk", "krwwwwrk", "krrrrrrk", "kkkkkkkk"),
                art("round_divider", ROUND_DIVIDER, Slot.CHAT,
                        "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g....."),
                // 16x16 light team flag (drawn at letter height); text colour tints it red/blue per team.
                image("chat_team", CHAT_TEAM, Slot.CHAT, "pack/hud/team_flag.png"),
                image("bar_skull", BAR_SKULL, Slot.BAR_ICON, "pack/hud/skull.png"),
                image("bar_bomb", BAR_BOMB, Slot.BAR_BOMB, "pack/hud/bomb.png"),
                rect("money_panel", MONEY_PANEL, Slot.MONEY_PANEL, MONEY_PANEL_WIDTH, MONEY_PANEL_HEIGHT, 0x8C000000),
                art("money_coin", MONEY_COIN, Slot.MONEY_ICON,
                        "..kkkk..", ".kyyyyk.", "kyywyyok", "kywyyyok", "kyyyyyok", "kyyyyook", ".kooook.", "..kkkk..")));
        out.add(image("plant_empty", PLANT_EMPTY, Slot.PLANT_BAR, "pack/hud/plant/empty.png"));
        out.add(image("plant_half", PLANT_HALF, Slot.PLANT_BAR, "pack/hud/plant/half.png"));
        out.add(image("plant_full", PLANT_FULL, Slot.PLANT_BAR, "pack/hud/plant/full.png"));
        for (RectSet set : RECT_SETS) {
            for (int w = 1; w <= RECT_MAX; w++) {
                out.add(rect(set.name() + "_" + w, set.glyph(w), set.slot(), w, set.height(), (set.alpha() << 24) | 0xFFFFFF, set.shape()));
            }
        }
        return out;
    }

    private static Glyph art(String name, char ch, Slot slot, String... rows) {
        BufferedImage img = new BufferedImage(rows[0].length(), rows.length, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                Integer c = PALETTE.get(rows[y].charAt(x));
                img.setRGB(x, y, c == null ? 0 : c);
            }
        }
        return new Glyph(name, ch, slot, img.getWidth(), img.getHeight(), img);
    }

    /** Chat icons are drawn 7 px tall, the height of chat capitals; images taller than 8 px are scaled to that. */
    public static final int CHAT_ICON_HEIGHT = 7;

    /** A glyph drawn from a PNG bundled in the plugin jar. */
    private static Glyph image(String name, char ch, Slot slot, String resource) {
        try (var in = HudGlyphs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing bundled HUD image " + resource);
            BufferedImage img = ImageIO.read(in);
            if (slot == Slot.CHAT && img.getHeight() > 8) img = chatCanvas(img);
            return new Glyph(name, ch, slot, img.getWidth(), img.getHeight(), img);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Crops a large chat icon to its ink and centres it on a canvas that the client draws {@value #CHAT_ICON_HEIGHT} px
     * tall and 8 px wide (14 rows = exactly 2:1). A single 1/255-alpha pixel in the last column fixes the measured
     * width, so every chat icon advances 9 px like the 8x8 ones; it is invisible (text discards alpha below 0.1).
     */
    private static BufferedImage chatCanvas(BufferedImage src) {
        int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if ((src.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return src;
        int w = maxX - minX + 1, h = maxY - minY + 1;
        int canvasH = Math.max(h, 14);
        double scale = CHAT_ICON_HEIGHT / (double) canvasH;
        int canvasW = Math.max(w + 1, (int) Math.round(8 / scale));
        BufferedImage out = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        int ox = (canvasW - 1 - w) / 2, oy = (canvasH - h) / 2;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.setRGB(ox + x, oy + y, src.getRGB(minX + x, minY + y));
        out.setRGB(canvasW - 1, canvasH - 1, 0x01FFFFFF); // width marker, never visible
        return out;
    }

    /** Solid rectangle with slightly rounded corners (the four corner pixels are left out). */
    private static Glyph rect(String name, char ch, Slot slot, int w, int h, int argb) {
        return rect(name, ch, slot, w, h, argb, Shape.RECT);
    }

    private static Glyph rect(String name, char ch, Slot slot, int w, int h, int argb, Shape shape) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean in = switch (shape) {
                    case RECT -> !(w > 2 && h > 2 && (x == 0 || x == w - 1) && (y == 0 || y == h - 1));
                    // pointed end: row y starts y px in (top row is the longest); the square end keeps rounded corners
                    case POINT_LEFT -> x >= y && !(w > 2 && h > 2 && x == w - 1 && (y == 0 || y == h - 1));
                    case POINT_RIGHT -> x < w - y && !(w > 2 && h > 2 && x == 0 && (y == 0 || y == h - 1));
                };
                img.setRGB(x, y, in ? argb : 0);
            }
        }
        return new Glyph(name, ch, slot, w, h, img);
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fully transparent boss bar (the WHITE colour): used by the text-only second boss bar row. */
    static BufferedImage bossBarInvisible() {
        return new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB);
    }
}
