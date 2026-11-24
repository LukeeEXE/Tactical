package dev.tactical.engine.economy;

import dev.tactical.engine.config.ConfigNode;

import java.util.List;

/**
 * Economy settings ({@code economy/economy.yml}). Design (architecture §5.1): every loss payout stays below the
 * win payout, so losing on purpose never pays; the loss streak drops by one on a win (no instant reset) to damp
 * snowballing; sudden death keeps funds and tops everyone up to a floor.
 */
public record EconomyRules(
        int startMoney,
        int maxMoney,
        int winReward,
        List<Integer> lossRewards,
        int killReward,
        int assistReward,
        int plantTeamReward,
        int defuseReward,
        double surviveLossFactor,
        boolean halftimeReset,
        boolean halftimeClearPurchases,
        int suddenDeathFloor,
        int retentionRounds,
        boolean sellBackSameRound
) {
    public static final int SCHEMA_VERSION = 1;

    public static EconomyRules defaults() {
        return new EconomyRules(800, 9000, 3000, List.of(1900, 2400, 2900), 200, 50, 300, 300, 0.5, true, true, 5000, 2, true);
    }

    public EconomyRules {
        lossRewards = List.copyOf(lossRewards);
        if (lossRewards.isEmpty()) throw new IllegalArgumentException("loss rewards must not be empty");
    }

    /** Loss payout for a team that has lost {@code streak} rounds in a row (1-based). */
    public int lossReward(int streak) {
        int i = Math.max(1, streak) - 1;
        return lossRewards.get(Math.min(i, lossRewards.size() - 1));
    }

    public static java.util.Optional<EconomyRules> parse(ConfigNode root) {
        int before = root.report().errors().size();
        EconomyRules d = defaults();
        root.warnUnknownKeys(List.of("schema-version", "start-money", "max-money", "round-win", "round-loss", "kill", "assist",
                "plant-team", "defuse", "survived-loss-factor", "halftime", "sudden-death-floor", "item-retention-rounds",
                "sell-back-same-round"));
        int max = root.integer("max-money", d.maxMoney(), 100, 1_000_000);
        List<Integer> loss = new java.util.ArrayList<>();
        ConfigNode lossNode = root.child("round-loss");
        if (lossNode.isMissing()) loss.addAll(d.lossRewards());
        for (ConfigNode n : lossNode.list()) {
            try {
                int v = Integer.parseInt(n.scalar().orElse("x").trim());
                if (v < 0) throw new NumberFormatException();
                loss.add(v);
            } catch (NumberFormatException e) {
                n.error("must be a whole number >= 0");
            }
        }
        int win = root.integer("round-win", d.winReward(), 0, max);
        for (int l : loss) {
            if (l >= win) {
                root.child("round-loss").error("every loss payout must be lower than round-win (" + win + "), otherwise losing on purpose pays");
                break;
            }
        }
        ConfigNode h = root.child("halftime");
        h.warnUnknownKeys(List.of("reset-money", "clear-purchases"));
        EconomyRules r = loss.isEmpty() ? null : new EconomyRules(
                root.integer("start-money", d.startMoney(), 0, max), max, win, loss,
                root.integer("kill", d.killReward(), 0, max), root.integer("assist", d.assistReward(), 0, max),
                root.integer("plant-team", d.plantTeamReward(), 0, max), root.integer("defuse", d.defuseReward(), 0, max),
                root.number("survived-loss-factor", d.surviveLossFactor(), 0, 1),
                h.bool("reset-money", d.halftimeReset()), h.bool("clear-purchases", d.halftimeClearPurchases()),
                root.integer("sudden-death-floor", d.suddenDeathFloor(), 0, max),
                root.integer("item-retention-rounds", d.retentionRounds(), 0, 30),
                root.bool("sell-back-same-round", d.sellBackSameRound()));
        if (r == null) root.child("round-loss").error("needs at least one value");
        if (root.report().errors().size() > before) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(r);
    }
}
