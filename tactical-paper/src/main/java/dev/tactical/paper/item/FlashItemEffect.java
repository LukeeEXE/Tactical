package dev.tactical.paper.item;

import dev.tactical.engine.config.ConfigNode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * {@code { type: flash, duration-ticks: 60 }}: the target's screen turns completely white (they can still move); the
 * last second fades out. Drawn in the world, so F1 does not remove it (see {@link CustomEffects}). Negative by default.
 */
public record FlashItemEffect(int durationTicks, Polarity polarity) implements ItemEffect {

    static Optional<ItemEffect> parse(ConfigNode n) {
        n.warnUnknownKeys(List.of("type", "duration-ticks", "polarity"));
        int duration = n.integer("duration-ticks", 60, 1, 20 * 60);
        return Optional.of(new FlashItemEffect(duration, n.enumValue("polarity", Polarity.class, Polarity.NEGATIVE)));
    }

    @Override
    public String type() {
        return "flash";
    }

    @Override
    public int level() {
        return 1;
    }

    @Override
    public void apply(Player target, Player source) {
        CustomEffects.playEffectSound(target, "flashing");
        CustomEffects.get().flash(target, durationTicks);
    }
}
