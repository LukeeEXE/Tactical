package dev.tactical.engine.mode;

/**
 * Bomb settings of a mode (the {@code bomb:} section). Timings (plant/defuse/fuse/checkpoint) live in the timers.
 *
 * @param defuseRadius            horizontal defuse range; the particle ring is drawn at this radius
 * @param pickupRadius            distance at which attackers pick up a dropped bomb
 * @param interruptDefuseOnDamage whether damage interrupts the active defuse
 * @param detonationKillRadius    players within this radius die on detonation (0 = none)
 */
public record BombRules(double defuseRadius, double pickupRadius, boolean interruptDefuseOnDamage, double detonationKillRadius) {

    public static final BombRules DEFAULT = new BombRules(3.5, 1.5, false, 0);
}
