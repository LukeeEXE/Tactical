package dev.tactical.paper.item;

import dev.tactical.engine.config.ConfigNode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * {@code { type: cleanse, polarity: positive }}: instantly removes every effect (vanilla and custom) from the target.
 * Positive by default (cleanse yourself or a teammate); {@code polarity: negative} makes it strip an opponent's buffs.
 */
public record CleanseItemEffect(Polarity polarity) implements ItemEffect {

    static Optional<ItemEffect> parse(ConfigNode n) {
        n.warnUnknownKeys(List.of("type", "polarity"));
        return Optional.of(new CleanseItemEffect(n.enumValue("polarity", Polarity.class, Polarity.POSITIVE)));
    }

    @Override
    public String type() {
        return "cleanse";
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
        CustomEffects.playEffectSound(target, "cleansing");
        CustomEffects.get().cleanse(target);
    }
}
