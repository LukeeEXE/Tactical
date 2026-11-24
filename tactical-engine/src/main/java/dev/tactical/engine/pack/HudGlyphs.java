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
    public enum Slot { CHAT, BAR_STRIP, BAR_ICON, BAR_PANEL, BAR_SCORE, MONEY_PANEL, MONEY_ICON, ACTION_PANEL }

    public record Glyph(String name, char ch, Slot slot, int width, int height, BufferedImage image) {
    }

    /** A family of rectangle pieces: {@code base + i} is 2^i px wide (i = 0..7). */
    public record RectSet(String name, char base, Slot slot, int height, int alpha) {
        public char piece(int i) {
            return (char) (base + i);
        }
    }

    // ---- private-use characters (stable: the plugin and the pack agree on these)
    public static final char CHAT_KILL = '\uE200', CHAT_ROUND = '\uE201', CHAT_WIN = '\uE202', CHAT_LOSS = '\uE203',
            CHAT_BOMB = '\uE204', CHAT_DEFUSE = '\uE205', CHAT_TIMER = '\uE206', CHAT_WARNING = '\uE207',
            CHAT_COIN = '\uE208', CHAT_KIT = '\uE209', CHAT_INFO = '\uE20A',
            CHAT_ARROW = '\uE20D', STAT_KILLS = '\uE210', STAT_DEATHS = '\uE211',
            STAT_ASSISTS = '\uE212', STAT_DAMAGE = '\uE213', STAT_KDR = '\uE214', CHAT_MVP = '\uE215',
            ROUND_WIN = '\uE216', ROUND_LOSS = '\uE217', ROUND_DIVIDER = '\uE218', CHAT_TEAM = '\uE219';
    public static final char BAR_SKULL = '\uE223';
    public static final char MONEY_PANEL = '\uE240', MONEY_COIN = '\uE241';

    /** Team strip behind the heads (soft; tinted red/blue). */
    public static final RectSet BAR_STRIP = new RectSet("bar_strip", '\uE250', Slot.BAR_STRIP, 10, 0x66);
    /** Timer panel (tinted black). */
    public static final RectSet BAR_PANEL = new RectSet("bar_panel", '\uE230', Slot.BAR_PANEL, 11, 0x8C);
    /** Score box (tinted black). */
    public static final RectSet BAR_SCORE = new RectSet("bar_score", '\uE260', Slot.BAR_SCORE, 16, 0x8C);
    /** Two-line action-bar hint box (tinted black). */
    public static final RectSet ACTION_PANEL = new RectSet("action_panel", '\uE270', Slot.ACTION_PANEL, 22, 0x8C);
    public static final List<RectSet> RECT_SETS = List.of(BAR_STRIP, BAR_PANEL, BAR_SCORE, ACTION_PANEL);

    public static final int MONEY_PANEL_WIDTH = 81, MONEY_PANEL_HEIGHT = 11;

    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
            Map.entry('k', 0xFF1B1B1F), Map.entry('w', 0xFFF4F4F4), Map.entry('g', 0xFFB8B8C0), Map.entry('G', 0xFF5C5C66),
            Map.entry('y', 0xFFFFD23F), Map.entry('o', 0xFFE0921E), Map.entry('r', 0xFFE5483F), Map.entry('R', 0xFF9E2620),
            Map.entry('b', 0xFF3F7FE5), Map.entry('B', 0xFF1F4FA0), Map.entry('c', 0xFF55E0E0), Map.entry('n', 0xFF5BD158),
            Map.entry('N', 0xFF2E8A2C));

    private HudGlyphs() {
    }

    public static List<Glyph> all() {
        List<Glyph> out = new ArrayList<>(List.of(
                art("chat_kill", CHAT_KILL, Slot.CHAT,
                        "..kkkk..", ".kwwwwk.", "kwwwwwwk", "kwkwwkwk", "kwwwwwwk", ".kwkkwk.", "..kwwk..", "...kk..."),
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
                art("chat_warning", CHAT_WARNING, Slot.CHAT,
                        "...kk...", "..kook..", "..okko..", ".ookkoo.", ".ookkoo.", "oooooooo", "ooookooo", ".kkkkkk."),
                art("chat_coin", CHAT_COIN, Slot.CHAT,
                        "..kkkk..", ".kyyyyk.", "kyywyyok", "kywyyyok", "kyyyyyok", "kyyyyook", ".kooook.", "..kkkk.."),
                art("chat_kit", CHAT_KIT, Slot.CHAT,
                        "...kk...", "...yy...", "kkyyyykk", ".kyyyyk.", "..kyyk..", ".kyyyyk.", ".ky..yk.", ".k....k."),
                art("chat_info", CHAT_INFO, Slot.CHAT,
                        "...kk...", "...cc...", "........", "..kcck..", "...cc...", "...cc...", "..kcck..", "..kkkk.."),
                art("chat_arrow", CHAT_ARROW, Slot.CHAT,
                        "........", "g.......", "g.......", "g.......", "g....g..", "ggggggg.", ".....g..", "........"),
                art("stat_kills", STAT_KILLS, Slot.CHAT,
                        ".......w", "......wg", ".....wg.", "k...wg..", ".k.wg...", "..kg....", ".kGk....", "kG..k..."),
                art("stat_deaths", STAT_DEATHS, Slot.CHAT,
                        "........", "..kkkk..", ".kggggk.", ".kgkkgk.", ".kggggk.", "..kgkk..", "..kkkk..", "........"),
                art("stat_assists", STAT_ASSISTS, Slot.CHAT,
                        "........", "...nn...", "...nn...", ".nnnnnn.", ".nnnnnn.", "...nn...", "...nn...", "........"),
                art("stat_damage", STAT_DAMAGE, Slot.CHAT,
                        "........", ".rr.rr..", "rrrrrrr.", "rrrrrrr.", ".rrrrr..", "..rrr...", "...r....", "........"),
                art("stat_kdr", STAT_KDR, Slot.CHAT,
                        "........", "......y.", ".....y..", "....y...", "...y....", "..y.....", ".y......", "........"),
                art("chat_mvp", CHAT_MVP, Slot.CHAT,
                        "...yy...", "...yy...", "yyyyyyyy", ".yyyyyy.", "..yyyy..", ".yy..yy.", ".y....y.", "........"),
                art("round_win", ROUND_WIN, Slot.CHAT,
                        "kkkkkkkk", "kwnnnnwk", "kwnnnnwk", "kwnwwnwk", "kwwnnwwk", "kwnnnnwk", "knnnnnnk", "kkkkkkkk"),
                art("round_loss", ROUND_LOSS, Slot.CHAT,
                        "kkkkkkkk", "krwrrrrk", "krwrrrrk", "krwrrrrk", "krwrrrrk", "krwwwwrk", "krrrrrrk", "kkkkkkkk"),
                art("round_divider", ROUND_DIVIDER, Slot.CHAT,
                        "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g....."),
                // 16x16 grey "team" icon (drawn at letter height); text colour tints it red/blue per team.
                image("chat_team", CHAT_TEAM, Slot.CHAT, "pack/hud/chat_team.png"),
                art("bar_skull", BAR_SKULL, Slot.BAR_ICON,
                        "..kkkk..", ".kggggk.", "kggggggk", "kgkggkgk", "kggggggk", ".kgkkgk.", "..kggk..", "...kk..."),
                rect("money_panel", MONEY_PANEL, Slot.MONEY_PANEL, MONEY_PANEL_WIDTH, MONEY_PANEL_HEIGHT, 0x8C000000),
                art("money_coin", MONEY_COIN, Slot.MONEY_ICON,
                        "..kkkk..", ".kyyyyk.", "kyywyyok", "kywyyyok", "kyyyyyok", "kyyyyook", ".kooook.", "..kkkk..")));
        for (RectSet set : RECT_SETS) {
            for (int i = 0; i < 8; i++) {
                int w = 1 << i;
                out.add(rect(set.name() + "_" + w, set.piece(i), set.slot(), w, set.height(), (set.alpha() << 24) | 0xFFFFFF));
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

    /** A glyph drawn from a PNG bundled in the plugin jar. */
    private static Glyph image(String name, char ch, Slot slot, String resource) {
        try (var in = HudGlyphs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing bundled HUD image " + resource);
            BufferedImage img = ImageIO.read(in);
            return new Glyph(name, ch, slot, img.getWidth(), img.getHeight(), img);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Solid rectangle with square corners. */
    private static Glyph rect(String name, char ch, Slot slot, int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
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

    /** Thin yellow progress line used as the boss bar (replaces the vanilla YELLOW boss bar sprites). */
    static BufferedImage bossBarLine(boolean progress) {
        BufferedImage img = new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 182; x++) {
            for (int y = 3; y <= 4; y++) img.setRGB(x, y, progress ? 0xFFFFD23F : 0x66000000);
        }
        return img;
    }
}
