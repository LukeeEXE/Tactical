package dev.tactical.engine.config;

import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.mode.SkipVoteRule;
import dev.tactical.engine.mode.SuddenDeathSides;

import java.util.List;
import java.util.Optional;

/** Parses {@code modes/<id>.yml}. Durations are written in SECONDS in YAML and converted to ticks. */
public final class ModeRulesParser {

    public static final int SCHEMA_VERSION = 1;

    private static final List<String> TOP_KEYS = List.of("schema-version", "id", "display-name", "team-size",
            "rounds-to-win", "halftime-after-round", "sudden-death-at", "sudden-death-sides", "skip-vote", "ranked",
            "timers");
    private static final List<String> TIMER_KEYS = List.of("kit-select", "buy-phase", "round", "bomb", "plant",
            "defuse", "defuse-checkpoint", "round-end", "halftime", "sudden-death-kit-select", "postgame");

    private ModeRulesParser() {
    }

    public static Optional<ModeRules> parse(ConfigNode root, String expectedId) {
        ConfigReport report = root.report();
        int before = report.errors().size();
        root.warnUnknownKeys(TOP_KEYS);
        String id = root.string("id", expectedId);
        if (!id.equals(expectedId)) {
            root.child("id").error("must match the file name '" + expectedId + "'");
        }
        ModeRules d = ModeRules.quickplayDefaults();
        String display = root.string("display-name", id);
        int teamSize = root.integer("team-size", 5, 1, 5);
        int roundsToWin = root.integer("rounds-to-win", d.roundsToWin(), 1, 50);
        int halftime = root.integer("halftime-after-round", d.halftimeAfterRound(), 1, 100);
        int sd = root.integer("sudden-death-at", d.suddenDeathAt(), 0, 49);
        SuddenDeathSides sides = root.enumValue("sudden-death-sides", SuddenDeathSides.class, SuddenDeathSides.RANDOM);
        SkipVoteRule skip = root.enumValue("skip-vote", SkipVoteRule.class, SkipVoteRule.MAJORITY);
        boolean ranked = root.bool("ranked", true);

        ConfigNode t = root.child("timers");
        t.warnUnknownKeys(TIMER_KEYS);
        int kitSelect = sec(t, "kit-select", 30);
        int buy = sec(t, "buy-phase", 30);
        int roundT = sec(t, "round", 100);
        int bomb = sec(t, "bomb", 45);
        int plant = sec(t, "plant", 5);
        int defuse = sec(t, "defuse", 10);
        int checkpoint = sec(t, "defuse-checkpoint", 5);
        int roundEnd = sec(t, "round-end", 5);
        int half = sec(t, "halftime", 60);
        int sdKit = sec(t, "sudden-death-kit-select", 20);
        int post = sec(t, "postgame", 15);

        for (String problem : ModeRules.validate(teamSize, roundsToWin, halftime, sd, defuse, checkpoint,
                kitSelect, buy, roundT, bomb, plant, roundEnd, half, sdKit, post)) {
            root.error(problem);
        }
        if (report.errors().size() > before) {
            return Optional.empty();
        }
        return Optional.of(new ModeRules(id, display, teamSize, roundsToWin, halftime, sd, kitSelect, buy, roundT,
                bomb, plant, defuse, checkpoint, roundEnd, half, sdKit, post, sides, skip, ranked));
    }

    private static int sec(ConfigNode timers, String key, double def) {
        return ModeRules.sec(timers.number(key, def, 0.05, 3600));
    }
}
