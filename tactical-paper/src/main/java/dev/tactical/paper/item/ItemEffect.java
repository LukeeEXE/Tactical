package dev.tactical.paper.item;

import org.bukkit.entity.Player;

/**
 * One effect an item applies (a Shot, later orbs and other utility). Effects are pluggable: each has a type id
 * ({@code potion} today) registered in {@link ItemEffects}, so new custom effect types only add a class and a
 * {@code register(...)} call; items list them in YAML with {@code type: <id>}.
 */
public interface ItemEffect {

    /** Helps or hurts: decides who a Shot may be applied to (teammates vs opponents). */
    enum Polarity { POSITIVE, NEGATIVE }

    String type();

    Polarity polarity();

    /** How long the effect lasts (0 = instant); shown in lore as {@code <duration>}. */
    int durationTicks();

    /** 1-based strength, shown in lore as {@code <level>}. */
    int level();

    /** Applies the effect to {@code target}; {@code source} is the player who used the item (may be the target). */
    void apply(Player target, Player source);
}
