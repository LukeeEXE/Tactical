package dev.tactical.engine.economy;

import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.engine.team.TeamId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchEconomyTest {

    static final UUID A1 = new UUID(1, 1), A2 = new UUID(1, 2), B1 = new UUID(2, 1);

    MatchEconomy eco() {
        return new MatchEconomy(EconomyRules.defaults(), Map.of(A1, TeamId.A, A2, TeamId.A, B1, TeamId.B));
    }

    @Test
    void spendingIsAllOrNothingAndNeverNegative() {
        MatchEconomy e = eco();
        List<EconomyChange> out = new ArrayList<>();
        assertTrue(e.trySpend(A1, 800, out, "buy"));
        assertEquals(0, e.balance(A1));
        assertFalse(e.trySpend(A1, 1, out, "buy"));
        assertFalse(e.trySpend(A1, -5, out, "buy"));
        assertFalse(e.trySpend(UUID.randomUUID(), 0, out, "buy"));
        assertEquals(1, out.size());
    }

    @Test
    void lossStreakEscalatesAndWinOnlyDecrements() {
        MatchEconomy e = eco();
        e.onRoundEnd(TeamId.B, TeamId.A, true, Set.of());
        assertEquals(800 + 1900, e.balance(A1));
        e.onRoundEnd(TeamId.B, TeamId.A, true, Set.of());
        e.onRoundEnd(TeamId.B, TeamId.A, true, Set.of());
        e.onRoundEnd(TeamId.B, TeamId.A, true, Set.of());
        assertEquals(Math.min(9000, 800 + 1900 + 2400 + 2900 + 2900), e.balance(A1));
        assertEquals(4, e.lossStreak(TeamId.A));
        e.onRoundEnd(TeamId.A, TeamId.A, true, Set.of());
        assertEquals(3, e.lossStreak(TeamId.A), "a win reduces the streak by one, not to zero");
        assertEquals(9000, e.balance(A1), "capped at max money");
    }

    @Test
    void savingAttackersGetReducedPayAndLosingNeverBeatsWinning() {
        MatchEconomy e = eco();
        e.onRoundEnd(TeamId.B, TeamId.A, false, Set.of(A1));
        assertEquals(800 + 950, e.balance(A1), "survived without planting: half the loss payout");
        assertEquals(800 + 1900, e.balance(A2));
        EconomyRules r = EconomyRules.defaults();
        for (int s = 1; s < 10; s++) assertTrue(r.lossReward(s) < r.winReward());
    }

    @Test
    void halftimeAndSuddenDeath() {
        MatchEconomy e = eco();
        e.onKill(A1);
        e.onHalftime();
        assertEquals(800, e.balance(A1));
        e.onSuddenDeath();
        assertEquals(5000, e.balance(A1));
        e.credit(B1, 3000, "x");
        e.onSuddenDeath();
        assertEquals(8000, e.balance(B1), "players above the floor keep their money");
    }

    @Test
    void configRejectsLossPayoutsAboveWin() {
        ConfigReport r = new ConfigReport();
        var root = YamlLoader.parse("round-win: 2000\nround-loss: [1900, 2500]\n", "economy/economy.yml", r).orElseThrow();
        assertTrue(EconomyRules.parse(root).isEmpty());
        assertTrue(r.errors().toString().contains("losing on purpose"));
    }
}
