package dev.tactical.paper.hud;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The ONE way gameplay code shows action-bar text. The HUD service owns the action bar (it also draws the money
 * widget there), so direct {@code sendActionBar} calls would erase the money display.
 */
public final class ActionBars {

    public interface Sink {
        void status(Player player, Component text, int ticks);
    }

    private static volatile Sink sink = (p, t, ticks) -> p.sendActionBar(t);

    private ActionBars() {
    }

    static void install(Sink s) {
        sink = s;
    }

    /** Shows {@code text} centred on the action bar for {@code ticks} ticks (next to the money widget). */
    public static void status(Player p, Component text, int ticks) {
        sink.status(p, text, ticks);
    }

    public static void status(Player p, Component text) {
        status(p, text, 40);
    }
}
