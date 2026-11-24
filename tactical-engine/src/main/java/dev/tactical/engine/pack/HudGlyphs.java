package dev.tactical.engine.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Original pixel art for the HUD and chat, generated into the resource pack. Each glyph has a private-use
 * character the plugin inserts into text. Art is defined as character grids so it lives in code, can be replaced
 * by dropping a PNG with the same name into {@code resourcepack/assets/tactical/textures/hud/}.
 */
public final class HudGlyphs {

    /** Which generated font a glyph belongs to (each font has its own vertical placement). */
    public enum Group { CHAT, BOSSBAR, MONEY }

    public record Glyph(String name, char ch, Group group, int width, int height, BufferedImage image) {
    }

    // ---- private-use characters (stable: the plugin and the pack agree on these)
    public static final char CHAT_KILL = '', CHAT_ROUND = '', CHAT_WIN = '', CHAT_LOSS = '',
            CHAT_BOMB = '', CHAT_DEFUSE = '', CHAT_TIMER = '', CHAT_WARNING = '',
            CHAT_COIN = '', CHAT_KIT = '', CHAT_INFO = '',
            CHAT_ARROW = '\uE20D', STAT_KILLS = '\uE210', STAT_DEATHS = '\uE211',
            STAT_ASSISTS = '\uE212', STAT_DAMAGE = '\uE213', STAT_KDR = '\uE214', CHAT_MVP = '\uE215',
            ROUND_WIN = '\uE216', ROUND_LOSS = '\uE217', ROUND_DIVIDER = '\uE218', CHAT_TEAM = '\uE219';
    public static final char BAR_TILE_RED = '', BAR_TILE_BLUE = '', BAR_TILE_DEAD = '',
            BAR_SKULL = '', BAR_PANEL_LEFT = '', BAR_PANEL_RIGHT = '';
    /** Timer panel middle segments: BAR_PANEL_FILL + i is 2^i pixels wide (i = 0..7). */
    public static final char BAR_PANEL_FILL = '';
    public static final char MONEY_PANEL = '', MONEY_COIN = '';

    public static final int TILE_SIZE = 10, PANEL_HEIGHT = 11, PANEL_CAP = 2, MONEY_PANEL_WIDTH = 81;

    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
            Map.entry('k', 0xFF1B1B1F), Map.entry('w', 0xFFF4F4F4), Map.entry('g', 0xFFB8B8C0), Map.entry('G', 0xFF5C5C66),
            Map.entry('y', 0xFFFFD23F), Map.entry('o', 0xFFE0921E), Map.entry('r', 0xFFE5483F), Map.entry('R', 0xFF9E2620),
            Map.entry('b', 0xFF3F7FE5), Map.entry('B', 0xFF1F4FA0), Map.entry('c', 0xFF55E0E0), Map.entry('n', 0xFF5BD158),
            Map.entry('N', 0xFF2E8A2C));

    private HudGlyphs() {
    }

    public static List<Glyph> all() {
        return List.of(
                art("chat_kill", CHAT_KILL, Group.CHAT,
                        "..kkkk..", ".kwwwwk.", "kwwwwwwk", "kwkwwkwk", "kwwwwwwk", ".kwkkwk.", "..kwwk..", "...kk..."),
                art("chat_round", CHAT_ROUND, Group.CHAT,
                        "kk......", "knnnnk..", "knnNnnk.", "knnnnnnk", "knnnnk..", "kk......", "k.......", "k......."),
                art("chat_win", CHAT_WIN, Group.CHAT,
                        "kkkkkkkk", "kyyyyyok", ".kyyyok.", "..kyok..", "...ko...", "...ko...", "..kook..", ".kkkkkk."),
                art("chat_loss", CHAT_LOSS, Group.CHAT,
                        "kk....kk", "krk..krk", ".krkkrk.", "..krrk..", "..krrk..", ".krkkrk.", "krk..krk", "kk....kk"),
                art("chat_bomb", CHAT_BOMB, Group.CHAT,
                        "......yo", ".....r..", "..kkkk..", ".kGGGGk.", "kGgGGGGk", "kGGGGGGk", ".kGGGGk.", "..kkkk.."),
                art("chat_defuse", CHAT_DEFUSE, Group.CHAT,
                        ".kkk....", "kcckk...", "kc.ckk..", "kkckcck.", "..kccck.", "...kcccc", "....kcck", ".....kk."),
                art("chat_timer", CHAT_TIMER, Group.CHAT,
                        "kkkkkkkk", ".kyyyyk.", "..kyyk..", "...kk...", "...kk...", "..k..k..", ".kyyyyk.", "kkkkkkkk"),
                art("chat_warning", CHAT_WARNING, Group.CHAT,
                        "...kk...", "..kook..", "..okko..", ".ookkoo.", ".ookkoo.", "oooooooo", "ooookooo", ".kkkkkk."),
                art("chat_coin", CHAT_COIN, Group.CHAT,
                        "..kkkk..", ".kyyyyk.", "kyywyyok", "kywyyyok", "kyyyyyok", "kyyyyook", ".kooook.", "..kkkk.."),
                art("chat_kit", CHAT_KIT, Group.CHAT,
                        "...kk...", "...yy...", "kkyyyykk", ".kyyyyk.", "..kyyk..", ".kyyyyk.", ".ky..yk.", ".k....k."),
                art("chat_info", CHAT_INFO, Group.CHAT,
                        "...kk...", "...cc...", "........", "..kcck..", "...cc...", "...cc...", "..kcck..", "..kkkk.."),
                art("chat_arrow", CHAT_ARROW, Group.CHAT,
                        "........", "g.......", "g.......", "g.......", "g....g..", "ggggggg.", ".....g..", "........"),
                // Stat icons are PLACEHOLDERS: replace resourcepack/assets/tactical/textures/hud/stat_*.png with your own art.
                art("stat_kills", STAT_KILLS, Group.CHAT,
                        ".......w", "......wg", ".....wg.", "k...wg..", ".k.wg...", "..kg....", ".kGk....", "kG..k..."),
                art("stat_deaths", STAT_DEATHS, Group.CHAT,
                        "........", "..kkkk..", ".kggggk.", ".kgkkgk.", ".kggggk.", "..kgkk..", "..kkkk..", "........"),
                art("stat_assists", STAT_ASSISTS, Group.CHAT,
                        "........", "...nn...", "...nn...", ".nnnnnn.", ".nnnnnn.", "...nn...", "...nn...", "........"),
                art("stat_damage", STAT_DAMAGE, Group.CHAT,
                        "........", ".rr.rr..", "rrrrrrr.", "rrrrrrr.", ".rrrrr..", "..rrr...", "...r....", "........"),
                art("stat_kdr", STAT_KDR, Group.CHAT,
                        "........", "......y.", ".....y..", "....y...", "...y....", "..y.....", ".y......", "........"),
                art("chat_mvp", CHAT_MVP, Group.CHAT,
                        "...yy...", "...yy...", "yyyyyyyy", ".yyyyyy.", "..yyyy..", ".yy..yy.", ".y....y.", "........"),
                // Round history boxes (placeholders: replace round_win.png / round_loss.png to restyle).
                art("round_win", ROUND_WIN, Group.CHAT,
                        "kkkkkkkk", "kwnnnnwk", "kwnnnnwk", "kwnwwnwk", "kwwnnwwk", "kwnnnnwk", "knnnnnnk", "kkkkkkkk"),
                art("round_loss", ROUND_LOSS, Group.CHAT,
                        "kkkkkkkk", "krwrrrrk", "krwrrrrk", "krwrrrrk", "krwrrrrk", "krwwwwrk", "krrrrrrk", "kkkkkkkk"),
                art("round_divider", ROUND_DIVIDER, Group.CHAT,
                        "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g.....", "..g....."),
                // 16x16 grey "team" icon (drawn at letter height); text colour tints it red/blue per team.
                image("chat_team", CHAT_TEAM, Group.CHAT, "pack/hud/chat_team.png"),
                tile("bar_tile_red", BAR_TILE_RED, 0xE5483F),
                tile("bar_tile_blue", BAR_TILE_BLUE, 0x3F7FE5),
                tile("bar_tile_dead", BAR_TILE_DEAD, 0x2A2A30),
                art("bar_skull", BAR_SKULL, Group.BOSSBAR,
                        "..kkkk..", ".kggggk.", "kggggggk", "kgkggkgk", "kggggggk", ".kgkkgk.", "..kggk..", "...kk..."),
                panel("bar_panel_left", BAR_PANEL_LEFT, Group.BOSSBAR, PANEL_CAP, PANEL_HEIGHT, true, false),
                panel("bar_panel_right", BAR_PANEL_RIGHT, Group.BOSSBAR, PANEL_CAP, PANEL_HEIGHT, false, true),
                panel("bar_panel_fill_1", BAR_PANEL_FILL, Group.BOSSBAR, 1, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_2", (char) (BAR_PANEL_FILL + 1), Group.BOSSBAR, 2, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_4", (char) (BAR_PANEL_FILL + 2), Group.BOSSBAR, 4, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_8", (char) (BAR_PANEL_FILL + 3), Group.BOSSBAR, 8, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_16", (char) (BAR_PANEL_FILL + 4), Group.BOSSBAR, 16, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_32", (char) (BAR_PANEL_FILL + 5), Group.BOSSBAR, 32, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_64", (char) (BAR_PANEL_FILL + 6), Group.BOSSBAR, 64, PANEL_HEIGHT, false, false),
                panel("bar_panel_fill_128", (char) (BAR_PANEL_FILL + 7), Group.BOSSBAR, 128, PANEL_HEIGHT, false, false),
                panel("money_panel", MONEY_PANEL, Group.MONEY, MONEY_PANEL_WIDTH, PANEL_HEIGHT, true, true),
                art("money_coin", MONEY_COIN, Group.MONEY,
                        "..kkkk..", ".kyyyyk.", "kyywyyok", "kywyyyok", "kyyyyyok", "kyyyyook", ".kooook.", "..kkkk.."));
    }

    private static Glyph art(String name, char ch, Group group, String... rows) {
        BufferedImage img = new BufferedImage(rows[0].length(), rows.length, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                Integer c = PALETTE.get(rows[y].charAt(x));
                img.setRGB(x, y, c == null ? 0 : c);
            }
        }
        return new Glyph(name, ch, group, img.getWidth(), img.getHeight(), img);
    }

    /** A glyph drawn from a PNG bundled in the plugin jar. */
    private static Glyph image(String name, char ch, Group group, String resource) {
        try (var in = HudGlyphs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing bundled HUD image " + resource);
            BufferedImage img = ImageIO.read(in);
            return new Glyph(name, ch, group, img.getWidth(), img.getHeight(), img);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Low-opacity team tile behind a head: translucent fill, slightly stronger border. */
    private static Glyph tile(String name, char ch, int rgb) {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                boolean border = x == 0 || y == 0 || x == TILE_SIZE - 1 || y == TILE_SIZE - 1;
                int alpha = border ? 0x99 : 0x55;
                img.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return new Glyph(name, ch, Group.BOSSBAR, TILE_SIZE, TILE_SIZE, img);
    }

    /** Dark translucent panel piece (square corners; the end flags are kept for future styling). */
    private static Glyph panel(String name, char ch, Group group, int w, int h, boolean roundLeft, boolean roundRight) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0x8C000000); // square corners
            }
        }
        return new Glyph(name, ch, group, w, h, img);
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
