package dev.tactical.engine.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Generated variants of a 16x16 kit logo for the kit menu:
 * <ul>
 *   <li>{@link #gray}: desaturated and darkened (a teammate locked it, or it is not available to you);</li>
 *   <li>{@link #selected}: a bright grey shine sweeping over the logo, as an animated texture (stronger than, and
 *       instead of, the purple enchantment glint).</li>
 * </ul>
 */
public final class KitIconVariants {

    /** Frames of the shine animation (one sweep, then a pause while the band is off the icon). */
    static final int FRAMES = 28;
    public static final byte[] SELECTED_MCMETA = "{\"animation\":{\"frametime\":2,\"interpolate\":false}}"
            .getBytes(StandardCharsets.UTF_8);

    private KitIconVariants() {
    }

    public static byte[] gray(byte[] png) {
        BufferedImage src = read(png);
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                int a = argb >>> 24;
                if (a == 0) continue;
                int l = luma(argb);
                int v = 40 + l * 45 / 100; // compressed and darkened: clearly "not available"
                out.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
        }
        return HudGlyphs.png(out);
    }

    /** The logo, a little brighter, with a light grey diagonal band sweeping across it ({@value #FRAMES} frames). */
    public static byte[] selected(byte[] png) {
        BufferedImage src = read(png);
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h * FRAMES, BufferedImage.TYPE_INT_ARGB);
        int sweep = w + h + 8; // band centre travels from -4 to w+h+4, then waits off the icon
        for (int f = 0; f < FRAMES; f++) {
            double centre = -4 + f * (double) (sweep + 12) / FRAMES;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int a = argb >>> 24;
                    if (a == 0) continue;
                    double band = Math.max(0, 1 - Math.abs(x + y - centre) / 4.5);
                    double mix = 0.2 + 0.8 * band; // always a bit lighter, strongly lit under the band
                    int r = blend((argb >> 16) & 0xFF, 0xF0, mix);
                    int g = blend((argb >> 8) & 0xFF, 0xF0, mix);
                    int b = blend(argb & 0xFF, 0xF0, mix);
                    out.setRGB(x, f * h + y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
        }
        return HudGlyphs.png(out);
    }

    private static int blend(int c, int to, double t) {
        return (int) Math.round(c + (to - c) * t);
    }

    private static int luma(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static BufferedImage read(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
