package dev.tactical.paper.hud;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The ONE way gameplay code shows action-bar text. The HUD service owns the action bar (it also draws the money
 * widget there), so direct {@code sendActionBar} calls would erase the money display.
 */
public final class ActionBars {

    public interface Sink {
        /** @param placed the text positions itself (zero advance) and replaces any hint box */
        void status(Player player, Component text, int ticks, boolean placed);
    }

    private static volatile Sink sink = (p, t, ticks, placed) -> p.sendActionBar(t);

    private ActionBars() {
    }

    static void install(Sink s) {
        sink = s;
    }

    /** Shows {@code text} centred on the action bar for {@code ticks} ticks (next to the money widget). */
    public static void status(Player p, Component text, int ticks) {
        sink.status(p, text, ticks, false);
    }

    /** Plant/defuse progress: a bar of segments on a dark box, centred on the action bar (no text). */
    public static void progress(Player p, double fraction) {
        sink.status(p, HudShapes.progressBar(fraction), 6, true);
    }

    public static void status(Player p, Component text) {
        status(p, text, 40);
    }
}
