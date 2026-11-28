package dev.tactical.paper.item;

import dev.tactical.engine.config.ConfigNode;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Optional;

/**
 * {@code { type: knockback, distance: 7.5, lift: 0.42 }}: launches the target backwards (away from where they are
 * looking) about {@code distance} blocks on flat ground. Negative by default.
 */
public record KnockbackItemEffect(double distance, double lift, Polarity polarity) implements ItemEffect {

    static Optional<ItemEffect> parse(ConfigNode n) {
        n.warnUnknownKeys(List.of("type", "distance", "lift", "polarity"));
        double distance = n.number("distance", 7.5, 0.5, 40);
        double lift = n.number("lift", 0.42, 0, 2);
        return Optional.of(new KnockbackItemEffect(distance, lift, n.enumValue("polarity", Polarity.class, Polarity.NEGATIVE)));
    }

    @Override
    public String type() {
        return "knockback";
    }

    @Override
    public int durationTicks() {
        return 0;
    }

    @Override
    public int level() {
        return 1;
    }

    @Override
    public void apply(Player target, Player source) {
        Vector back = target.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1e-6) back = new Vector(0, 0, 1);
        back.normalize().multiply(-1);
        // Airborne for ~t ticks with this lift; horizontal speed decays x0.91 per tick in the air, so the speed that
        // covers `distance` is distance * 0.09 / (1 - 0.91^t), minus a little for the slide after landing.
        int air = airTicks(lift);
        double speed = distance * 0.09 / (1 - Math.pow(0.91, air)) * 0.93;
        target.setVelocity(back.multiply(speed).setY(lift));
    }

    /** Ticks until a jump with this upward speed lands again (gravity 0.08, drag 0.98). */
    static int airTicks(double vy) {
        double y = 0, v = vy;
        for (int t = 1; t < 200; t++) {
            y += v;
            v = (v - 0.08) * 0.98;
            if (y <= 0) return Math.max(1, t);
        }
        return 200;
    }
}
