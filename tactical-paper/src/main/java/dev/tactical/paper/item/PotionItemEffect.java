package dev.tactical.paper.item;

import dev.tactical.engine.config.ConfigNode;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code { type: potion, effect: speed, level: 2, duration-ticks: 36, polarity: positive }}: any vanilla potion
 * effect. Polarity defaults to the effect's own category (beneficial = positive, harmful = negative); neutral
 * effects must say which.
 */
public record PotionItemEffect(PotionEffectType effect, int level, int durationTicks, Polarity polarity) implements ItemEffect {

    static Optional<ItemEffect> parse(ConfigNode n) {
        n.warnUnknownKeys(List.of("type", "effect", "level", "duration-ticks", "polarity"));
        String name = n.requireString("effect");
        int level = n.integer("level", 1, 1, 256);
        int duration = n.integer("duration-ticks", 100, 1, 20 * 60 * 60);
        if (name == null) return Optional.empty();
        NamespacedKey key = name.contains(":") ? NamespacedKey.fromString(name.toLowerCase(Locale.ROOT))
                : NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
        PotionEffectType type = key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key);
        if (type == null) {
            n.child("effect").error("unknown potion effect '" + name + "'");
            return Optional.empty();
        }
        Polarity polarity;
        if (n.has("polarity")) {
            polarity = n.enumValue("polarity", Polarity.class, Polarity.POSITIVE);
        } else if (type.getCategory() == PotionEffectTypeCategory.NEUTRAL) {
            n.error("'" + name + "' is neither helpful nor harmful: add polarity: positive or negative");
            return Optional.empty();
        } else {
            polarity = type.getCategory() == PotionEffectTypeCategory.BENEFICIAL ? Polarity.POSITIVE : Polarity.NEGATIVE;
        }
        return Optional.of(new PotionItemEffect(type, level, duration, polarity));
    }

    @Override
    public String type() {
        return "potion";
    }

    @Override
    public void apply(Player target, Player source) {
        // No vanilla swirl particles: the item draws its own coloured ring and trail.
        target.addPotionEffect(new PotionEffect(effect, durationTicks, level - 1, false, false, true));
        // Instant effects (harming) may never show up as an added effect: play their sound here (deduplicated).
        if (effect.isInstant()) CustomEffects.playEffectSound(target, effect.getKey().getKey());
    }
}
