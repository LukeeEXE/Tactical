package dev.tactical.engine.map;

/**
 * An item lying on the map that any participant can pick up. Respawned at the start of every round.
 *
 * @param item   item id, e.g. {@code arrow} or {@code minecraft:golden_apple}
 * @param amount stack size (1..64)
 */
public record Pickup(String item, int amount, double x, double y, double z) {
}
