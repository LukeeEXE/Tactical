package dev.tactical.engine.economy;

import dev.tactical.engine.team.TeamId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Wallets and team loss streaks of one match. Pure logic: callers apply the returned {@link EconomyChange}s to
 * recordings/HUD. Money can never go negative or above the cap; spending is all-or-nothing.
 */
public final class MatchEconomy {

    private final EconomyRules rules;
    private final Map<UUID, TeamId> teams;
    private final Map<UUID, Integer> wallets = new LinkedHashMap<>();
    private final EnumMap<TeamId, Integer> lossStreak = new EnumMap<>(TeamId.class);

    public MatchEconomy(EconomyRules rules, Map<UUID, TeamId> teams) {
        this.rules = Objects.requireNonNull(rules);
        this.teams = Map.copyOf(teams);
        for (UUID id : teams.keySet()) wallets.put(id, rules.startMoney());
        for (TeamId t : TeamId.values()) lossStreak.put(t, 0);
    }

    public EconomyRules rules() {
        return rules;
    }

    public int balance(UUID player) {
        return wallets.getOrDefault(player, 0);
    }

    public int lossStreak(TeamId team) {
        return lossStreak.get(team);
    }

    /** Spends exactly {@code amount} or nothing. */
    public boolean trySpend(UUID player, int amount, List<EconomyChange> out, String reason) {
        if (amount < 0 || !wallets.containsKey(player)) return false;
        int b = wallets.get(player);
        if (b < amount) return false;
        wallets.put(player, b - amount);
        out.add(new EconomyChange(player, -amount, b - amount, reason));
        return true;
    }

    /** Adds money (capped). Returns the change actually applied. */
    public EconomyChange credit(UUID player, int amount, String reason) {
        int b = wallets.getOrDefault(player, 0);
        int nb = Math.max(0, Math.min(rules.maxMoney(), b + Math.max(0, amount)));
        wallets.put(player, nb);
        return new EconomyChange(player, nb - b, nb, reason);
    }

    public List<EconomyChange> onKill(UUID killer) {
        return wallets.containsKey(killer) ? List.of(credit(killer, rules.killReward(), "kill")) : List.of();
    }

    public List<EconomyChange> onAssist(UUID assister) {
        return wallets.containsKey(assister) ? List.of(credit(assister, rules.assistReward(), "assist")) : List.of();
    }

    public List<EconomyChange> onPlant(TeamId attackers) {
        List<EconomyChange> out = new ArrayList<>();
        for (Map.Entry<UUID, TeamId> e : teams.entrySet()) {
            if (e.getValue() == attackers) out.add(credit(e.getKey(), rules.plantTeamReward(), "plant"));
        }
        return out;
    }

    public List<EconomyChange> onDefuse(UUID defuser) {
        return wallets.containsKey(defuser) ? List.of(credit(defuser, rules.defuseReward(), "defuse")) : List.of();
    }

    /**
     * Round payouts. Losers who survived a round they lost WITHOUT planting (attackers saving instead of playing the
     * objective) get the reduced "survived" payout.
     *
     * @param attackers    team that attacked this round
     * @param planted      whether the bomb was planted
     * @param survivors    participants alive at the end of the round
     */
    public List<EconomyChange> onRoundEnd(TeamId winner, TeamId attackers, boolean planted, Set<UUID> survivors) {
        TeamId loser = winner.other();
        lossStreak.put(winner, Math.max(0, lossStreak.get(winner) - 1));
        lossStreak.put(loser, lossStreak.get(loser) + 1);
        int lossPay = rules.lossReward(lossStreak.get(loser));
        List<EconomyChange> out = new ArrayList<>();
        for (Map.Entry<UUID, TeamId> e : teams.entrySet()) {
            if (e.getValue() == winner) {
                out.add(credit(e.getKey(), rules.winReward(), "round_win"));
            } else {
                boolean saved = loser == attackers && !planted && survivors.contains(e.getKey());
                int pay = saved ? (int) Math.round(lossPay * rules.surviveLossFactor()) : lossPay;
                out.add(credit(e.getKey(), pay, saved ? "round_loss_survived" : "round_loss"));
            }
        }
        return out;
    }

    /** Second pistol round: everyone back to the start money (if configured); loss streaks reset. */
    public List<EconomyChange> onHalftime() {
        for (TeamId t : TeamId.values()) lossStreak.put(t, 0);
        if (!rules.halftimeReset()) return List.of();
        List<EconomyChange> out = new ArrayList<>();
        for (UUID id : wallets.keySet()) {
            int b = wallets.get(id);
            wallets.put(id, rules.startMoney());
            out.add(new EconomyChange(id, rules.startMoney() - b, rules.startMoney(), "halftime_reset"));
        }
        return out;
    }

    /** Sudden death: keep funds, top up to the floor. */
    public List<EconomyChange> onSuddenDeath() {
        List<EconomyChange> out = new ArrayList<>();
        for (UUID id : wallets.keySet()) {
            int b = wallets.get(id);
            if (b < rules.suddenDeathFloor()) out.add(credit(id, rules.suddenDeathFloor() - b, "sudden_death_floor"));
        }
        return out;
    }

    public Map<UUID, Integer> wallets() {
        return Map.copyOf(wallets);
    }

    public Collection<UUID> players() {
        return wallets.keySet();
    }
}
