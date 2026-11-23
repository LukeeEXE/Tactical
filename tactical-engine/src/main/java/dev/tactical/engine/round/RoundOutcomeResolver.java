package dev.tactical.engine.round;

import dev.tactical.engine.team.Side;

import java.util.Optional;

/**
 * Deterministic round-end precedence (architecture §3.1). Evaluated once per tick AFTER all controllers
 * (bomb, plant, defuse) have run, so same-tick events resolve consistently:
 * <ol>
 *   <li>Bomb defused → defenders (a defuse completing on the detonation tick counts as defused).</li>
 *   <li>Bomb detonated → attackers.</li>
 *   <li>Bomb planted and all defenders dead → attackers.</li>
 *   <li>Bomb planted and all attackers dead → round CONTINUES (defenders must still defuse).</li>
 *   <li>Not planted, all attackers dead → defenders (also covers both teams dead at once).</li>
 *   <li>Not planted, all defenders dead → attackers.</li>
 *   <li>Not planted and plant timer expired → defenders.</li>
 * </ol>
 */
public final class RoundOutcomeResolver {

    private RoundOutcomeResolver() {
    }

    public static Optional<RoundOutcome> resolve(RoundSnapshot s) {
        return switch (s.bomb()) {
            case DEFUSED -> Optional.of(new RoundOutcome(Side.DEFENDERS, RoundEndReason.BOMB_DEFUSED));
            case DETONATED -> Optional.of(new RoundOutcome(Side.ATTACKERS, RoundEndReason.BOMB_DETONATED));
            case PLANTED -> s.aliveDefenders() == 0
                    ? Optional.of(new RoundOutcome(Side.ATTACKERS, RoundEndReason.DEFENDERS_ELIMINATED_AFTER_PLANT))
                    : Optional.empty();
            case NOT_PLANTED -> {
                if (s.aliveAttackers() == 0) {
                    yield Optional.of(new RoundOutcome(Side.DEFENDERS, RoundEndReason.ATTACKERS_ELIMINATED));
                }
                if (s.aliveDefenders() == 0) {
                    yield Optional.of(new RoundOutcome(Side.ATTACKERS, RoundEndReason.DEFENDERS_ELIMINATED));
                }
                if (s.roundTimerExpired()) {
                    yield Optional.of(new RoundOutcome(Side.DEFENDERS, RoundEndReason.TIME_EXPIRED));
                }
                yield Optional.empty();
            }
        };
    }
}
