package dev.tactical.engine.mode;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable rules for a game mode (Quickplay, Competitive, test...). Loaded from {@code modes/<id>.yml}.
 * All durations are in server ticks (20 ticks = 1 second).
 *
 * @param id                      mode id, e.g. {@code quickplay}
 * @param displayName             MiniMessage display name
 * @param teamSize                players per team (5 for real matches)
 * @param roundsToWin             first team to this many round wins takes the match
 * @param halftimeAfterRound      teams swap sides after this round number
 * @param suddenDeathAt           when both scores equal this value, one decider round is played
 * @param kitSelectTicks          kit selection before round 1
 * @param buyPhaseTicks           shop phase before each round
 * @param roundTicks              time attackers have to plant
 * @param bombTicks               fuse after plant
 * @param plantTicks              time to plant
 * @param defuseTicks             time to defuse
 * @param defuseCheckpointTicks   defuse progress kept by the bomb once reached
 * @param roundEndTicks           pause after a round ends
 * @param halftimeTicks           halftime break (includes second-half kit selection)
 * @param suddenDeathKitSelectTicks kit selection before sudden death (1st/2nd half kit)
 * @param postgameTicks           results screen duration
 * @param suddenDeathSides        side assignment for the sudden-death round
 * @param skipVoteRule            how a team agrees to skip halftime / kit selection remainder
 * @param ranked                  whether results count for statistics/progression
 */
public record ModeRules(
        String id,
        String displayName,
        int teamSize,
        int roundsToWin,
        int halftimeAfterRound,
        int suddenDeathAt,
        int kitSelectTicks,
        int buyPhaseTicks,
        int roundTicks,
        int bombTicks,
        int plantTicks,
        int defuseTicks,
        int defuseCheckpointTicks,
        int roundEndTicks,
        int halftimeTicks,
        int suddenDeathKitSelectTicks,
        int postgameTicks,
        SuddenDeathSides suddenDeathSides,
        SkipVoteRule skipVoteRule,
        boolean ranked
) {

    public static final int TICKS_PER_SECOND = 20;

    public ModeRules {
        List<String> problems = validate(teamSize, roundsToWin, halftimeAfterRound, suddenDeathAt,
                defuseTicks, defuseCheckpointTicks,
                kitSelectTicks, buyPhaseTicks, roundTicks, bombTicks, plantTicks, roundEndTicks,
                halftimeTicks, suddenDeathKitSelectTicks, postgameTicks);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid mode '" + id + "': " + String.join("; ", problems));
        }
    }

    /** Structural validation shared by the constructor and the YAML parser (which reports per-key). */
    public static List<String> validate(int teamSize, int roundsToWin, int halftimeAfterRound, int suddenDeathAt,
                                        int defuseTicks, int defuseCheckpointTicks, int... durations) {
        List<String> problems = new ArrayList<>();
        if (teamSize < 1 || teamSize > 5) problems.add("team-size must be 1..5");
        if (roundsToWin < 1) problems.add("rounds-to-win must be >= 1");
        if (halftimeAfterRound < 1) problems.add("halftime-after-round must be >= 1");
        if (suddenDeathAt < 0 || suddenDeathAt >= roundsToWin) problems.add("sudden-death-at must be 0..rounds-to-win-1");
        if (halftimeAfterRound > 2 * (roundsToWin - 1)) problems.add("halftime-after-round is after the last possible regular round");
        if (defuseCheckpointTicks < 0 || defuseCheckpointTicks >= defuseTicks) problems.add("defuse checkpoint must be < defuse time");
        for (int d : durations) {
            if (d < 1) {
                problems.add("all durations must be >= 1 tick");
                break;
            }
        }
        return problems;
    }

    /** Valorant-style Quickplay defaults (first to 5, swap after 4, decider at 4-4). */
    public static ModeRules quickplayDefaults() {
        return new ModeRules("quickplay", "<aqua>Quickplay", 5, 5, 4, 4,
                sec(30), sec(30), sec(100), sec(45), sec(5), sec(10), sec(5), sec(5), sec(60), sec(20), sec(15),
                SuddenDeathSides.RANDOM, SkipVoteRule.MAJORITY, true);
    }

    /** Competitive defaults (first to 9, swap after 8, decider at 8-8). */
    public static ModeRules competitiveDefaults() {
        return new ModeRules("competitive", "<gold>Competitive", 5, 9, 8, 8,
                sec(30), sec(30), sec(100), sec(45), sec(5), sec(10), sec(5), sec(5), sec(60), sec(20), sec(15),
                SuddenDeathSides.RANDOM, SkipVoteRule.MAJORITY, true);
    }

    public static int sec(double seconds) {
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }
}
