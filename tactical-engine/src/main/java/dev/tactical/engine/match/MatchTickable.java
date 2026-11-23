package dev.tactical.engine.match;

/**
 * A per-match controller ticked by {@link MatchFlow} at the START of every un-paused tick, before timers advance
 * and before round-end resolution (bomb/plant/defuse controllers, AFK tracking, ...).
 */
@FunctionalInterface
public interface MatchTickable {
    void tick(MatchFlow flow);
}
