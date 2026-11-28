package dev.tactical.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tactical.engine.config.ConfigIssue;
import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.kit.KitSelectionResult;
import dev.tactical.engine.match.EliminationCause;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Tasks;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import dev.tactical.paper.message.Messages;
import dev.tactical.paper.record.RecorderService;
import dev.tactical.paper.record.RecordingInfo;
import dev.tactical.paper.audit.AuditService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /game} command tree. Every node checks its permission in {@code requires} (evaluated server-side for
 * both execution and tab-completion), and handlers re-check before acting.
 */
public final class GameCommand {

    public static final String PERM_MATCH = "tactical.admin.match";
    public static final String PERM_RELOAD = "tactical.admin.reload";
    public static final String PERM_LOGS = "tactical.admin.logs";
    public static final String PERM_DEBUG = "tactical.admin.debug";

    private final ConfigService config;
    private final MatchService matches;
    private final RecorderService recorders;
    private final AuditService audit;
    private final Messages messages;
    private final Tasks tasks;
    private final String version;
    private final dev.tactical.paper.map.MapService maps;
    private final List<java.util.function.Consumer<LiteralArgumentBuilder<CommandSourceStack>>> extensions;

    public GameCommand(ConfigService config, MatchService matches, RecorderService recorders, AuditService audit,
                       Messages messages, Tasks tasks, String version, dev.tactical.paper.map.MapService maps,
                       List<java.util.function.Consumer<LiteralArgumentBuilder<CommandSourceStack>>> extensions) {
        this.maps = maps;
        this.extensions = extensions;
        this.config = config;
        this.matches = matches;
        this.recorders = recorders;
        this.audit = audit;
        this.messages = messages;
        this.tasks = tasks;
        this.version = version;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("game");
        root.then(Commands.literal("version").executes(ctx -> {
            reply(ctx, "Tactical " + version + " - " + matches.matches().size() + " active match(es)", NamedTextColor.GRAY);
            return ok();
        }));

        // ---------------------------------------------------------------- matches
        root.then(Commands.literal("match").requires(perm(PERM_MATCH))
                .then(Commands.literal("test")
                        .then(Commands.argument("mode", StringArgumentType.word()).suggests(modeSuggestions())
                                .then(Commands.argument("map", StringArgumentType.word()).suggests(mapSuggestions())
                                        .executes(ctx -> createTest(ctx, 4, null))
                                        .then(Commands.argument("virtual", IntegerArgumentType.integer(0, 5))
                                                .executes(ctx -> createTest(ctx, IntegerArgumentType.getInteger(ctx, "virtual"), null))
                                                .then(Commands.argument("players", StringArgumentType.greedyString())
                                                        .suggests(this::suggestPlayers)
                                                        .executes(ctx -> createTest(ctx, IntegerArgumentType.getInteger(ctx, "virtual"),
                                                                StringArgumentType.getString(ctx, "players"))))))))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("info").then(matchArg().executes(ctx -> withMatch(ctx, m -> info(ctx, m)))))
                .then(Commands.literal("end").then(matchArg()
                        .executes(ctx -> withMatch(ctx, m -> end(ctx, m, "ended by administrator")))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> withMatch(ctx, m -> end(ctx, m, StringArgumentType.getString(ctx, "reason"))))))));

        root.then(Commands.literal("round").requires(perm(PERM_MATCH))
                .then(Commands.literal("end").then(matchArg()
                        .then(Commands.argument("winner", StringArgumentType.word())
                                .suggests((c, b) -> { b.suggest("attackers"); b.suggest("defenders"); return b.buildFuture(); })
                                .executes(ctx -> withMatch(ctx, m -> forceRound(ctx, m)))))));

        root.then(Commands.literal("pause").requires(perm(PERM_MATCH))
                .executes(ctx -> withOwnMatch(ctx, m -> pause(ctx, m, "")))
                .then(matchArg().executes(ctx -> withMatch(ctx, m -> pause(ctx, m, "")))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> withMatch(ctx, m -> pause(ctx, m, StringArgumentType.getString(ctx, "reason")))))));
        root.then(Commands.literal("resume").requires(perm(PERM_MATCH))
                .executes(ctx -> withOwnMatch(ctx, m -> resume(ctx, m)))
                .then(matchArg().executes(ctx -> withMatch(ctx, m -> resume(ctx, m)))));

        // ---------------------------------------------------------------- debug (test matches only)
        root.then(Commands.literal("debug").requires(perm(PERM_DEBUG))
                .then(Commands.literal("eliminate").then(matchArg()
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests((c, b) -> { b.suggest("A"); b.suggest("B"); return b.buildFuture(); })
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 5))
                                        .executes(ctx -> withTestMatch(ctx, m -> eliminate(ctx, m)))))))
                .then(Commands.literal("plant").then(matchArg()
                        .executes(ctx -> withTestMatch(ctx, m -> plant(ctx, m)))))
                .then(Commands.literal("forfeit").then(matchArg()
                        .executes(ctx -> withTestMatch(ctx, m -> {
                            boolean ok = m.flow().forfeit(dev.tactical.engine.team.TeamId.A);
                            reply(ctx, ok ? "Team A forfeited." : "This match cannot be forfeited now.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
                            return ok ? 1 : 0;
                        }))))
                .then(Commands.literal("defuse").then(matchArg()
                        .executes(ctx -> withTestMatch(ctx, m -> defuse(ctx, m)))))
                .then(Commands.literal("heads").executes(ctx -> {
                    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        dev.tactical.paper.hud.Heads.textures(p.getUniqueId(), p.getName());
                        reply(ctx, p.getName() + ": " + dev.tactical.paper.hud.Heads.describe(p.getUniqueId())
                                + (org.bukkit.Bukkit.getOnlineMode() ? "" : " (server is in offline mode)"), NamedTextColor.YELLOW);
                    }
                    return ok();
                }))
                .then(Commands.literal("json").executes(ctx -> {
                    // What actually goes over the wire for HUD text: Adventure JSON and the server's own (vanilla) codec.
                    net.kyori.adventure.text.Component c = net.kyori.adventure.text.Component.text("x", NamedTextColor.WHITE)
                            .shadowColor(net.kyori.adventure.text.format.ShadowColor.none())
                            .append(net.kyori.adventure.text.Component.text("y"));
                    reply(ctx, "adventure: " + net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(c), NamedTextColor.GRAY);
                    try {
                        Object vanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                                .getMethod("asVanilla", net.kyori.adventure.text.Component.class).invoke(null, c);
                        Object codec = Class.forName("net.minecraft.network.chat.ComponentSerialization").getField("CODEC").get(null);
                        Object ops = Class.forName("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
                        Object result = codec.getClass().getMethod("encodeStart", Class.forName("com.mojang.serialization.DynamicOps"), Object.class)
                                .invoke(codec, ops, vanilla);
                        reply(ctx, "vanilla: " + result, NamedTextColor.GRAY);
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        reply(ctx, "vanilla: n/a (" + ex + ")", NamedTextColor.RED);
                    }
                    return ok();
                }))
                .then(Commands.literal("skip").then(matchArg()
                        .executes(ctx -> withTestMatch(ctx, m -> skip(ctx, m))))));

        // ---------------------------------------------------------------- config
        root.then(Commands.literal("reload").requires(perm(PERM_RELOAD))
                .executes(ctx -> reload(ctx, "all"))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (ConfigService.Target t : ConfigService.Target.values()) b.suggest(t.name().toLowerCase(Locale.ROOT));
                            return b.buildFuture();
                        })
                        .executes(ctx -> reload(ctx, StringArgumentType.getString(ctx, "target")))));

        // ---------------------------------------------------------------- logs
        root.then(Commands.literal("logs").requires(perm(PERM_LOGS))
                .then(Commands.literal("recent").executes(this::logsRecent))
                .then(Commands.literal("errors").executes(this::logsErrors))
                .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> logsInfo(ctx, StringArgumentType.getString(ctx, "id"))))));
        extensions.forEach(e -> e.accept(root));
        return root.build();
    }

    // ===================================================================== handlers

    /**
     * Test match. Real players (listed, or the sender if none listed) are split A/B alternately; each team is then
     * topped up with up to {@code virtual} engine-only players. Map "none" runs the engine without a world.
     */
    private int createTest(CommandContext<CommandSourceStack> ctx, int virtual, String playerSpec) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission(PERM_MATCH)) return deny(ctx);
        String mode = StringArgumentType.getString(ctx, "mode");
        String mapArg = StringArgumentType.getString(ctx, "map");
        String mapId = mapArg.equalsIgnoreCase("none") ? null : mapArg;
        ModeRules rules = config.mode(mode).orElse(null);
        if (rules == null) {
            reply(ctx, "Unknown mode '" + mode + "'. Known: " + String.join(", ", config.modes().keySet()), NamedTextColor.RED);
            return 0;
        }
        List<Player> teamA = new java.util.ArrayList<>();
        List<Player> teamB = new java.util.ArrayList<>();
        if (playerSpec == null) {
            if (sender instanceof Player p) teamA.add(p);
        } else if (!parseTeams(sender, playerSpec, teamA, teamB)) {
            return 0;
        }
        int perTeamVirtual = Math.max(0, Math.min(virtual, rules.teamSize() - Math.max(teamA.size(), teamB.size())));
        matches.create(new MatchService.MatchRequest(mode, mapId, teamA, teamB, perTeamVirtual, true), sender)
                .whenCompleteAsync((m, t) -> {
                    if (t != null) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        sender.sendMessage(Component.text("Could not create match: " + cause.getMessage(), NamedTextColor.RED));
                    } else {
                        messages.send(sender, "match.created", "match", m.id().value(), "mode", mode,
                                "players", m.members().size());
                    }
                }, tasks.main());
        return ok();
    }

    /**
     * Player list for test matches: names separated by spaces or commas, selectors such as {@code @a} allowed.
     * {@code vs} splits the list into explicit teams (left = team A / first attackers); without it players alternate.
     */
    private boolean parseTeams(CommandSender sender, String spec, List<Player> teamA, List<Player> teamB) {
        List<Player> left = new java.util.ArrayList<>();
        List<Player> right = new java.util.ArrayList<>();
        boolean explicit = false;
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        List<String> unknown = new java.util.ArrayList<>();
        for (String token : spec.trim().split("[\\s,]+")) {
            if (token.isEmpty()) continue;
            if (token.equalsIgnoreCase("vs")) {
                explicit = true;
                continue;
            }
            List<Player> found = new java.util.ArrayList<>();
            if (token.startsWith("@")) {
                try {
                    for (org.bukkit.entity.Entity e : org.bukkit.Bukkit.selectEntities(sender, token)) if (e instanceof Player p) found.add(p);
                } catch (IllegalArgumentException e) {
                    unknown.add(token);
                }
            } else {
                Player p = org.bukkit.Bukkit.getPlayerExact(token);
                if (p == null) unknown.add(token);
                else found.add(p);
            }
            for (Player p : found) if (seen.add(p.getUniqueId())) (explicit ? right : left).add(p);
        }
        if (!unknown.isEmpty()) {
            sender.sendMessage(Component.text("Not online / invalid: " + String.join(", ", unknown), NamedTextColor.RED));
            return false;
        }
        if (explicit) {
            teamA.addAll(left);
            teamB.addAll(right);
        } else {
            for (int i = 0; i < left.size(); i++) (i % 2 == 0 ? teamA : teamB).add(left.get(i));
        }
        return true;
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        String remaining = b.getRemaining();
        int cut = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf(',')) + 1;
        String prefix = remaining.substring(cut).toLowerCase(Locale.ROOT);
        com.mojang.brigadier.suggestion.SuggestionsBuilder o = b.createOffset(b.getStart() + cut);
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) o.suggest(p.getName());
        }
        if ("vs".startsWith(prefix) && cut > 0) o.suggest("vs");
        if ("@a".startsWith(prefix)) o.suggest("@a");
        return o.buildFuture();
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        if (matches.matches().isEmpty()) {
            reply(ctx, "No active matches.", NamedTextColor.GRAY);
            return ok();
        }
        for (GameMatch m : matches.matches()) {
            reply(ctx, m.id() + "  " + m.rules().id() + "  " + m.state() + (m.flow().isPaused() ? " (PAUSED)" : "")
                    + "  round " + m.flow().round() + "  " + m.flow().scoreOf(TeamId.A) + "-" + m.flow().scoreOf(TeamId.B)
                    + "  players " + m.realMemberCount() + "/" + m.members().size(), NamedTextColor.YELLOW);
        }
        return ok();
    }

    private int info(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        var f = m.flow();
        reply(ctx, "== " + m.id() + " (" + m.rules().id() + (m.isTest() ? ", TEST" : "") + ") ==", NamedTextColor.GOLD);
        reply(ctx, "State " + f.state() + (f.isPaused() ? " PAUSED" : "") + ", " + f.stateTicksRemaining() / 20.0
                + "s left, round " + f.round() + (f.isSuddenDeathRound() ? " (sudden death)" : "")
                + ", bomb " + f.bomb(), NamedTextColor.YELLOW);
        reply(ctx, "Score A " + f.scoreOf(TeamId.A) + " - " + f.scoreOf(TeamId.B) + " B; A plays " + f.sideOf(TeamId.A),
                NamedTextColor.YELLOW);
        for (MatchMember mm : m.members()) {
            Participant p = f.participant(mm.uuid()).orElseThrow();
            reply(ctx, " " + mm.team() + " " + mm.name() + (mm.virtual() ? " [virtual]" : "")
                    + (p.connected() ? "" : " [disconnected]") + (p.alive() ? " alive" : " dead")
                    + " kit=" + (p.currentKit() == null ? "-" : p.currentKit()), NamedTextColor.GRAY);
        }
        reply(ctx, "Recording: " + m.recorder().eventCount() + " events", NamedTextColor.GRAY);
        return ok();
    }

    private int end(CommandContext<CommandSourceStack> ctx, GameMatch m, String reason) {
        matches.end(m, ctx.getSource().getSender(), reason);
        reply(ctx, "Ending " + m.id() + ".", NamedTextColor.GREEN);
        return ok();
    }

    private int forceRound(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        String w = StringArgumentType.getString(ctx, "winner").toLowerCase(Locale.ROOT);
        Side side = switch (w) {
            case "attackers", "atk" -> Side.ATTACKERS;
            case "defenders", "def" -> Side.DEFENDERS;
            default -> null;
        };
        if (side == null) {
            reply(ctx, "Winner must be attackers or defenders.", NamedTextColor.RED);
            return 0;
        }
        if (!m.flow().forceEndRound(side)) {
            reply(ctx, "No round in progress.", NamedTextColor.RED);
            return 0;
        }
        matches.adminAction(m, ctx.getSource().getSender(), "FORCE_END_ROUND", "winner=" + side);
        return ok();
    }

    private int pause(CommandContext<CommandSourceStack> ctx, GameMatch m, String reason) {
        if (!matches.pause(m, ctx.getSource().getSender(), reason)) {
            reply(ctx, "Cannot pause " + m.id() + " now (already paused or finished).", NamedTextColor.RED);
            return 0;
        }
        reply(ctx, "Paused " + m.id() + ".", NamedTextColor.GREEN);
        return ok();
    }

    private int resume(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        if (!matches.resume(m, ctx.getSource().getSender())) {
            reply(ctx, m.id() + " is not paused.", NamedTextColor.RED);
            return 0;
        }
        reply(ctx, "Resuming " + m.id() + ".", NamedTextColor.GREEN);
        return ok();
    }

    private int eliminate(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        TeamId team;
        try {
            team = TeamId.valueOf(StringArgumentType.getString(ctx, "team").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            reply(ctx, "Team must be A or B.", NamedTextColor.RED);
            return 0;
        }
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int done = 0;
        for (Participant p : m.flow().participants()) {
            if (done >= count) break;
            if (p.team() == team && p.active() && m.flow().eliminate(p.id(), null, EliminationCause.ADMIN)) done++;
        }
        matches.adminAction(m, ctx.getSource().getSender(), "DEBUG_ELIMINATE", "team=" + team + " count=" + done);
        reply(ctx, "Eliminated " + done + " player(s) of team " + team + ".", NamedTextColor.GREEN);
        return ok();
    }

    private int plant(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        Optional<Participant> planter = m.flow().participants().stream()
                .filter(p -> p.active() && m.flow().sideOf(p.team()) == Side.ATTACKERS).findFirst();
        boolean ok = planter.isPresent() && m.flow().bombPlanted(planter.get().id(), "A");
        if (ok) matches.adminAction(m, ctx.getSource().getSender(), "DEBUG_PLANT", "site=A");
        reply(ctx, ok ? "Bomb planted at A." : "Cannot plant now.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return ok ? ok() : 0;
    }

    private int defuse(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        Optional<Participant> defuser = m.flow().participants().stream()
                .filter(p -> p.active() && m.flow().sideOf(p.team()) == Side.DEFENDERS).findFirst();
        boolean ok = defuser.isPresent() && m.flow().bombDefused(defuser.get().id());
        if (ok) matches.adminAction(m, ctx.getSource().getSender(), "DEBUG_DEFUSE", "");
        reply(ctx, ok ? "Bomb defused." : "Cannot defuse now.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return ok ? ok() : 0;
    }

    /** Locks every remaining kit and votes skip for all members (lets admins fast-forward a test match). */
    private int skip(CommandContext<CommandSourceStack> ctx, GameMatch m) {
        var sel = m.flow().kitSelection().orElse(null);
        if (sel == null) {
            reply(ctx, "No kit selection in progress.", NamedTextColor.RED);
            return 0;
        }
        for (Participant p : m.flow().participants()) {
            if (!sel.isLocked(p.id())) {
                for (String kit : sel.optionsOf(p.id())) {
                    if (m.flow().lockKit(p.id(), kit) == KitSelectionResult.OK) break;
                }
            }
            m.flow().voteSkip(p.id());
        }
        matches.adminAction(m, ctx.getSource().getSender(), "DEBUG_SKIP_SELECTION", "");
        reply(ctx, "Kit selection completed.", NamedTextColor.GREEN);
        return ok();
    }

    private int reload(CommandContext<CommandSourceStack> ctx, String targetName) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission(PERM_RELOAD)) return deny(ctx);
        Optional<ConfigService.Target> target = ConfigService.Target.parse(targetName);
        if (target.isEmpty()) {
            reply(ctx, "Unknown target. Use: config, modes, kits, messages, maps, shops, economy, all", NamedTextColor.RED);
            return 0;
        }
        ConfigReport report = config.reload(target.get());
        if (target.get() == ConfigService.Target.MAPS || target.get() == ConfigService.Target.ALL) report.addAll(maps.reload());
        audit.record(sender, "RELOAD", target.get().name(), report.errors().size() + " error(s)");
        if (report.issues().isEmpty()) {
            reply(ctx, "Reloaded " + targetName + " without problems. Running matches keep their current settings.", NamedTextColor.GREEN);
        } else {
            for (ConfigIssue i : report.issues()) {
                reply(ctx, i.toString(), i.severity() == ConfigIssue.Severity.ERROR ? NamedTextColor.RED : NamedTextColor.YELLOW);
            }
            if (report.hasErrors()) {
                reply(ctx, "Invalid files were NOT applied; their previous version stays active.", NamedTextColor.GOLD);
            } else {
                reply(ctx, "Reloaded with warnings (applied).", NamedTextColor.GREEN);
            }
        }
        return ok();
    }

    private int logsRecent(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        recorders.recent(10).whenCompleteAsync((list, t) -> {
            if (t != null) {
                sender.sendMessage(Component.text("Could not list recordings: " + t.getMessage(), NamedTextColor.RED));
                return;
            }
            if (list.isEmpty()) sender.sendMessage(Component.text("No recordings yet.", NamedTextColor.GRAY));
            for (RecordingInfo i : list) sender.sendMessage(Component.text(describe(i), NamedTextColor.YELLOW));
        }, tasks.main());
        return ok();
    }

    private int logsInfo(CommandContext<CommandSourceStack> ctx, String id) {
        CommandSender sender = ctx.getSource().getSender();
        recorders.info(id).whenCompleteAsync((info, t) -> {
            if (t != null || info.isEmpty()) {
                sender.sendMessage(Component.text("No recording for '" + id + "'.", NamedTextColor.RED));
                return;
            }
            RecordingInfo i = info.get();
            sender.sendMessage(Component.text(describe(i), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("File " + i.file().getFileName() + ", " + i.sizeBytes() / 1024 + " KiB, "
                    + i.lines() + " events, " + i.errors() + " error event(s), started " + i.startedAt(), NamedTextColor.GRAY));
        }, tasks.main());
        return ok();
    }

    private int logsErrors(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        recorders.recentErrors(20, 20).whenCompleteAsync((list, t) -> {
            if (t != null) return;
            if (list.isEmpty()) sender.sendMessage(Component.text("No errors in the 20 most recent recordings.", NamedTextColor.GREEN));
            for (String s : list) sender.sendMessage(Component.text(s, NamedTextColor.RED));
        }, tasks.main());
        return ok();
    }

    private static String describe(RecordingInfo i) {
        return i.matchId() + "  " + i.mode() + "  " + i.score() + "  " + i.endReason() + "  "
                + (i.complete() ? "complete" : "INCOMPLETE");
    }

    // ===================================================================== helpers

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> matchArg() {
        return Commands.argument("match", StringArgumentType.word()).suggests(matchSuggestions());
    }

    private SuggestionProvider<CommandSourceStack> matchSuggestions() {
        return (c, b) -> {
            matches.matches().forEach(m -> b.suggest(m.id().value()));
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> mapSuggestions() {
        return (c, b) -> {
            b.suggest("none");
            maps.maps().keySet().forEach(b::suggest);
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> modeSuggestions() {
        return (c, b) -> {
            config.modes().keySet().forEach(b::suggest);
            return b.buildFuture();
        };
    }

    private int withMatch(CommandContext<CommandSourceStack> ctx, MatchHandler handler) {
        String id = StringArgumentType.getString(ctx, "match");
        Optional<GameMatch> m = matches.match(id);
        if (m.isEmpty()) {
            reply(ctx, "No active match '" + id + "'.", NamedTextColor.RED);
            return 0;
        }
        return handler.handle(m.get());
    }

    private int withTestMatch(CommandContext<CommandSourceStack> ctx, MatchHandler handler) {
        return withMatch(ctx, m -> {
            if (!m.isTest()) {
                reply(ctx, "Debug tools only work on test matches.", NamedTextColor.RED);
                return 0;
            }
            return handler.handle(m);
        });
    }

    private int withOwnMatch(CommandContext<CommandSourceStack> ctx, MatchHandler handler) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
            reply(ctx, "Specify a match id.", NamedTextColor.RED);
            return 0;
        }
        Optional<GameMatch> m = matches.matchOf(p.getUniqueId());
        if (m.isEmpty()) {
            reply(ctx, "You are not in a match; specify a match id.", NamedTextColor.RED);
            return 0;
        }
        return handler.handle(m.get());
    }

    @FunctionalInterface
    private interface MatchHandler {
        int handle(GameMatch m);
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String node) {
        return src -> src.getSender().hasPermission(node);
    }

    private int deny(CommandContext<CommandSourceStack> ctx) {
        messages.send(ctx.getSource().getSender(), "no-permission");
        return 0;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text, NamedTextColor color) {
        ctx.getSource().getSender().sendMessage(Component.text(text, color));
    }

    private static int ok() {
        return Command.SINGLE_SUCCESS;
    }
}
