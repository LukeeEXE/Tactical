package dev.tactical.paper.config;

import dev.tactical.engine.config.ConfigNode;

import java.util.List;

/** Immutable snapshot of config.yml. See docs/config-reference.md for every key. */
public record GeneralConfig(
        String storageType,
        String sqliteFile,
        String recordingFolder,
        int positionIntervalTicks,
        int recordingQueueSoftLimit,
        int recordingFlushIntervalMs,
        int rejoinWindowSeconds,
        int resumeCountdownSeconds,
        List<String> placeholderLoadout,
        double shopHitboxWidth,
        double shopHitboxHeight,
        boolean projectilesPassTeammates,
        boolean logStateChanges,
        boolean logSpectatorInput,
        int lockedFoodLevel,
        boolean regenEnabled,
        double regenAmount,
        int regenIntervalTicks,
        int regenCombatDelayTicks,
        boolean packEnabled,
        String packHosting,
        String packExternalUrl,
        int packPort,
        String packPublicUrl,
        boolean packRequired,
        String packPrompt
) {
    public static final int SCHEMA_VERSION = 1;

    public static GeneralConfig defaults() {
        return new GeneralConfig("sqlite", "data/tactical.db", "logs/matches", 4, 50_000, 1000, 180, 3,
                List.of("iron_sword", "bow", "arrow:16"), 2.5, 4.0, false, true, false, 20, true, 1.0, 80, 0,
                true, "builtin", "", 25601, "", true, "<gold>This server needs its resource pack for the HUD, items and maps.");
    }

    public static GeneralConfig parse(ConfigNode root) {
        GeneralConfig d = defaults();
        root.warnUnknownKeys(List.of("schema-version", "storage", "recording", "matches", "health", "resource-pack", "debug"));
        ConfigNode storage = root.child("storage");
        storage.warnUnknownKeys(List.of("type", "sqlite-file"));
        String type = storage.string("type", d.storageType());
        if (!type.equals("sqlite")) {
            storage.child("type").error("only 'sqlite' is supported in this version (mysql/mariadb planned)");
            type = "sqlite";
        }
        ConfigNode rec = root.child("recording");
        rec.warnUnknownKeys(List.of("folder", "position-interval-ticks", "queue-soft-limit", "flush-interval-ms"));
        ConfigNode m = root.child("matches");
        m.warnUnknownKeys(List.of("rejoin-window-seconds", "resume-countdown-seconds", "placeholder-loadout", "shop-hitbox",
                "projectiles-pass-through-teammates"));
        ConfigNode hb = m.child("shop-hitbox");
        hb.warnUnknownKeys(List.of("width", "height"));
        ConfigNode h = root.child("health");
        h.warnUnknownKeys(List.of("locked-food-level", "regeneration"));
        ConfigNode regen = h.child("regeneration");
        regen.warnUnknownKeys(List.of("enabled", "amount", "interval-ticks", "combat-delay-ticks"));
        ConfigNode pack = root.child("resource-pack");
        pack.warnUnknownKeys(List.of("enabled", "hosting", "external-url", "port", "public-url", "required", "prompt"));
        String hosting = pack.string("hosting", d.packHosting()).toLowerCase(java.util.Locale.ROOT);
        if (!hosting.equals("builtin") && !hosting.equals("external")) {
            pack.child("hosting").error("must be builtin or external");
            hosting = d.packHosting();
        }
        String externalUrl = pack.string("external-url", d.packExternalUrl());
        if (hosting.equals("external") && !externalUrl.isBlank() && !externalUrl.startsWith("http")) {
            pack.child("external-url").error("must be a direct http(s) download link to the .zip");
        }
        ConfigNode dbg = root.child("debug");
        dbg.warnUnknownKeys(List.of("log-state-changes", "log-spectator-input"));
        List<String> loadout = m.has("placeholder-loadout") ? m.stringList("placeholder-loadout") : d.placeholderLoadout();
        return new GeneralConfig(
                type,
                storage.string("sqlite-file", d.sqliteFile()),
                rec.string("folder", d.recordingFolder()),
                rec.integer("position-interval-ticks", d.positionIntervalTicks(), 1, 200),
                rec.integer("queue-soft-limit", d.recordingQueueSoftLimit(), 1000, 10_000_000),
                rec.integer("flush-interval-ms", d.recordingFlushIntervalMs(), 50, 60_000),
                m.integer("rejoin-window-seconds", d.rejoinWindowSeconds(), 0, 3600),
                m.integer("resume-countdown-seconds", d.resumeCountdownSeconds(), 0, 60),
                loadout,
                hb.number("width", d.shopHitboxWidth(), 0.5, 8),
                hb.number("height", d.shopHitboxHeight(), 2.2, 8),
                m.bool("projectiles-pass-through-teammates", d.projectilesPassTeammates()),
                dbg.bool("log-state-changes", d.logStateChanges()),
                dbg.bool("log-spectator-input", d.logSpectatorInput()),
                h.integer("locked-food-level", d.lockedFoodLevel(), 7, 20),
                regen.bool("enabled", d.regenEnabled()),
                regen.number("amount", d.regenAmount(), 0, 20),
                regen.integer("interval-ticks", d.regenIntervalTicks(), 1, 20 * 60),
                regen.integer("combat-delay-ticks", d.regenCombatDelayTicks(), 0, 20 * 60),
                pack.bool("enabled", d.packEnabled()),
                hosting,
                externalUrl,
                pack.integer("port", d.packPort(), 1, 65535),
                pack.string("public-url", d.packPublicUrl()),
                pack.bool("required", d.packRequired()),
                pack.string("prompt", d.packPrompt()));
    }
}
