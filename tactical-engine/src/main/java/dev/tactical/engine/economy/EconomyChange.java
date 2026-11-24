package dev.tactical.engine.economy;

import java.util.UUID;

/** One wallet change, for recordings and the money HUD. {@code delta} is what was actually applied (after the cap). */
public record EconomyChange(UUID player, int delta, int balance, String reason) {
}
