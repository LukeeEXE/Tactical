package dev.tactical.engine.config;

import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.mode.SuddenDeathSides;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    private static final String VALID = """
            schema-version: 1
            id: quickplay
            display-name: "<aqua>Quickplay"
            rounds-to-win: 5
            halftime-after-round: 4
            sudden-death-at: 4
            sudden-death-sides: keep
            timers:
              buy-phase: 30
              round: 100
              bomb: 45
            """;

    @Test
    void parsesValidModeInSeconds() {
        ConfigReport r = new ConfigReport();
        ModeRules rules = ModeRulesParser.parse(YamlLoader.parse(VALID, "modes/quickplay.yml", r).orElseThrow(), "quickplay").orElseThrow();
        assertFalse(r.hasErrors(), r.issues().toString());
        assertEquals(600, rules.buyPhaseTicks());
        assertEquals(2000, rules.roundTicks());
        assertEquals(SuddenDeathSides.KEEP, rules.suddenDeathSides());
    }

    @Test
    void reportsEveryProblemWithLineNumbers() {
        String bad = """
                schema-version: 1
                id: quickplay
                rounds-to-win: lots
                sudden-death-sides: sometimes
                rounds-to-wn: 5
                timers:
                  bomb: -3
                """;
        ConfigReport r = new ConfigReport();
        Optional<ModeRules> rules = ModeRulesParser.parse(YamlLoader.parse(bad, "modes/quickplay.yml", r).orElseThrow(), "quickplay");
        assertTrue(rules.isEmpty(), "invalid files are rejected, never partially applied");
        List<ConfigIssue> errors = r.errors();
        assertTrue(errors.stream().anyMatch(i -> i.line() == 3 && i.path().equals("rounds-to-win")), errors.toString());
        assertTrue(errors.stream().anyMatch(i -> i.line() == 4 && i.message().contains("keep")), errors.toString());
        assertTrue(errors.stream().anyMatch(i -> i.line() == 7 && i.path().equals("timers.bomb")), errors.toString());
        assertTrue(r.issues().stream().anyMatch(i -> i.severity() == ConfigIssue.Severity.WARNING && i.path().equals("rounds-to-wn")));
    }

    @Test
    void syntaxErrorsAndDuplicateKeysAreReported() {
        ConfigReport r = new ConfigReport();
        assertTrue(YamlLoader.parse("a: [1, 2\nb: 3", "x.yml", r).isEmpty());
        assertTrue(r.hasErrors());
        ConfigReport r2 = new ConfigReport();
        assertTrue(YamlLoader.parse("a: 1\na: 2\n", "x.yml", r2).isEmpty());
        assertTrue(r2.hasErrors());
    }

    @Test
    void idMustMatchFileName() {
        ConfigReport r = new ConfigReport();
        assertTrue(ModeRulesParser.parse(YamlLoader.parse("id: other\n", "modes/quickplay.yml", r).orElseThrow(), "quickplay").isEmpty());
    }

    @Test
    void migratorChainsAndRefusesNewerFiles() {
        ConfigMigration renameV1 = new ConfigMigration() {
            public int fromVersion() { return 1; }
            public void migrate(Map<String, Object> root) { root.put("rounds-to-win", root.remove("win-rounds")); }
        };
        ConfigMigration addV2 = new ConfigMigration() {
            public int fromVersion() { return 2; }
            public void migrate(Map<String, Object> root) { root.putIfAbsent("ranked", true); }
        };
        ConfigMigrator m = new ConfigMigrator("mode", 3, List.of(addV2, renameV1));
        Map<String, Object> root = new HashMap<>(Map.of("win-rounds", 5));
        assertEquals(ConfigMigrator.Outcome.MIGRATED, m.migrate(root).outcome());
        assertEquals(5, root.get("rounds-to-win"));
        assertEquals(true, root.get("ranked"));
        assertEquals(3, root.get("schema-version"));
        assertEquals(ConfigMigrator.Outcome.UP_TO_DATE, m.migrate(root).outcome());
        assertEquals(ConfigMigrator.Outcome.TOO_NEW, m.migrate(new HashMap<>(Map.of("schema-version", 9))).outcome());
        assertThrows(IllegalArgumentException.class, () -> new ConfigMigrator("x", 3, List.of(renameV1)));
    }
}
