package dev.tactical.paper.hud;

import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.engine.pack.TtfMetrics;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures text the way the client draws it, so HUD pieces can be positioned to the pixel.
 * Vanilla font widths come from a table; TTF fonts (e.g. Pinch) are measured from the font file itself.
 */
public final class HudText {

    /** Advance (glyph width + 1) of vanilla ASCII characters that are not the usual 6 px. */
    private static final Map<Character, Integer> VANILLA = Map.ofEntries(
            Map.entry(' ', 4), Map.entry('!', 2), Map.entry('"', 4), Map.entry('\'', 2), Map.entry('(', 4), Map.entry(')', 4),
            Map.entry('*', 4), Map.entry(',', 2), Map.entry('.', 2), Map.entry(':', 2), Map.entry(';', 2), Map.entry('<', 5),
            Map.entry('>', 5), Map.entry('@', 7), Map.entry('I', 4), Map.entry('[', 4), Map.entry(']', 4), Map.entry('`', 3),
            Map.entry('f', 5), Map.entry('i', 2), Map.entry('k', 5), Map.entry('l', 3), Map.entry('t', 4), Map.entry('{', 4),
            Map.entry('|', 2), Map.entry('}', 4), Map.entry('~', 7));

    private final Path fontsDir;
    private final Map<String, TtfMetrics> metrics = new ConcurrentHashMap<>();
    private final Map<String, Double> sizes = new ConcurrentHashMap<>();

    public HudText(Path fontsDir) {
        this.fontsDir = fontsDir;
    }

    public static int vanillaWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += VANILLA.getOrDefault(c, 6);
        return w;
    }

    /** Width of {@code s} in a TTF font from resourcepack/fonts/ (0 if the font is missing). */
    public int ttfWidth(String font, String s) {
        TtfMetrics m = metrics(font);
        return m == null ? 0 : (int) Math.round(m.width(s, sizes.getOrDefault(font, 11.0)));
    }

    public boolean hasFont(String font) {
        return metrics(font) != null;
    }

    /** Text in a TTF font, shifted vertically (+ down / - up). */
    public static Component ttf(String font, int offsetY, Component text) {
        String id = offsetY == 0 ? font : offsetY > 0 ? font + "_down_" + offsetY : font + "_up_" + (-offsetY);
        return text.font(Key.key("tactical", id));
    }

    public void reload() {
        metrics.clear();
        sizes.clear();
    }

    private TtfMetrics metrics(String font) {
        return metrics.computeIfAbsent(font, f -> {
            Path ttf = fontsDir.resolve(f + ".ttf");
            if (!Files.exists(ttf)) ttf = fontsDir.resolve(f + ".otf");
            try {
                Path yml = fontsDir.resolve(f + ".yml");
                if (Files.exists(yml)) {
                    YamlLoader.load(yml, f + ".yml", new ConfigReport()).ifPresent(n -> sizes.put(f, n.number("size", 11, 1, 256)));
                }
                return TtfMetrics.parse(Files.readAllBytes(ttf));
            } catch (Exception e) {
                return null;
            }
        });
    }
}
