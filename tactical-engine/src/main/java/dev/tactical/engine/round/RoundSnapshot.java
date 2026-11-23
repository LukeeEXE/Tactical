package dev.tactical.engine.round;

/**
 * Facts about the current tick that decide whether the round is over.
 *
 * @param aliveAttackers       attackers alive and connected (disconnected/AFK-eliminated count as dead)
 * @param aliveDefenders       defenders alive and connected
 * @param bomb                 bomb status at the end of this tick's controller phase
 * @param roundTimerExpired    plant timer reached zero this tick (only meaningful while not planted)
 */
public record RoundSnapshot(int aliveAttackers, int aliveDefenders, BombStatus bomb, boolean roundTimerExpired) {
}
