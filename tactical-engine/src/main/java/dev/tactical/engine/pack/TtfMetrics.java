package dev.tactical.engine.pack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal TrueType reader for horizontal advances (cmap format 4 + hmtx), so the server can measure text drawn in a
 * custom font and size/centre HUD panels to the pixel. Minecraft scales TTF glyphs by
 * {@code size / (ascender - descender)}, which is what {@link #width} uses.
 */
public final class TtfMetrics {

    private final int ascender;
    private final int descender;
    private final Map<Integer, Integer> advances;

    private TtfMetrics(int ascender, int descender, Map<Integer, Integer> advances) {
        this.ascender = ascender;
        this.descender = descender;
        this.advances = advances;
    }

    public static TtfMetrics parse(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data);
        int numTables = b.getShort(4) & 0xFFFF;
        Map<String, Integer> tables = new HashMap<>();
        for (int i = 0; i < numTables; i++) {
            int rec = 12 + 16 * i;
            String tag = new String(data, rec, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            tables.put(tag, b.getInt(rec + 8));
        }
        for (String t : new String[]{"cmap", "hhea", "hmtx"}) {
            if (!tables.containsKey(t)) throw new IllegalArgumentException("font has no " + t + " table");
        }
        int hhea = tables.get("hhea");
        int ascender = b.getShort(hhea + 4);
        int descender = b.getShort(hhea + 6);
        int numHMetrics = b.getShort(hhea + 34) & 0xFFFF;
        int hmtx = tables.get("hmtx");
        int[] glyphAdvance = new int[numHMetrics];
        for (int i = 0; i < numHMetrics; i++) glyphAdvance[i] = b.getShort(hmtx + 4 * i) & 0xFFFF;

        Map<Integer, Integer> advances = new HashMap<>();
        int cmap = tables.get("cmap");
        int sub = cmap + 4, n = b.getShort(cmap + 2) & 0xFFFF;
        for (int i = 0; i < n; i++) {
            int platform = b.getShort(sub + 8 * i) & 0xFFFF;
            int offset = cmap + b.getInt(sub + 8 * i + 4);
            if (b.getShort(offset) != 4 || (platform != 3 && platform != 0)) continue;
            int segX2 = b.getShort(offset + 6) & 0xFFFF, seg = segX2 / 2;
            int ends = offset + 14, starts = ends + segX2 + 2, deltas = starts + segX2, rangeOffsets = deltas + segX2;
            for (int k = 0; k < seg; k++) {
                int end = b.getShort(ends + 2 * k) & 0xFFFF, start = b.getShort(starts + 2 * k) & 0xFFFF;
                int delta = b.getShort(deltas + 2 * k), ro = b.getShort(rangeOffsets + 2 * k) & 0xFFFF;
                for (int c = start; c <= end && c != 0xFFFF; c++) {
                    int g;
                    if (ro == 0) {
                        g = (c + delta) & 0xFFFF;
                    } else {
                        int addr = rangeOffsets + 2 * k + ro + 2 * (c - start);
                        g = b.getShort(addr) & 0xFFFF;
                        if (g != 0) g = (g + delta) & 0xFFFF;
                    }
                    if (g != 0) advances.putIfAbsent(c, glyphAdvance[Math.min(g, numHMetrics - 1)]);
                }
            }
        }
        return new TtfMetrics(ascender, descender, advances);
    }

    public boolean has(int codepoint) {
        return advances.containsKey(codepoint);
    }

    /** Width in GUI pixels of {@code text} rendered at {@code size}. Unknown characters count as 0. */
    public double width(String text, double size) {
        double scale = size / (ascender - descender);
        double w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            w += advances.getOrDefault(cp, 0) * scale;
            i += Character.charCount(cp);
        }
        return w;
    }
}
