package dev.tactical.paper.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.tactical.engine.geo.Region;
import dev.tactical.engine.geo.SpawnPoint;
import dev.tactical.engine.map.Barrier;
import dev.tactical.engine.map.BombSite;
import dev.tactical.engine.map.KitRestriction;
import dev.tactical.engine.map.MapCodec;
import dev.tactical.engine.map.MapDefinition;
import dev.tactical.engine.map.MapPool;
import dev.tactical.engine.map.MapSelector;
import dev.tactical.engine.map.MapStatus;
import dev.tactical.engine.map.MapValidator;
import dev.tactical.paper.audit.AuditService;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Tasks;
import dev.tactical.paper.lobby.LobbyService;
import dev.tactical.paper.map.MapEditorService;
import dev.tactical.paper.map.MapService;
import dev.tactical.paper.map.MapWorlds;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /game map ...} (editor) and {@code /game pool ...}. Editing commands act on the map the admin is currently
 * editing ({@code /game map edit <id>}). Every change is saved immediately (atomic write + versioned backup),
 * bumps the map's layout version and returns it to DRAFT until validated again.
 */
public final class MapCommands {

    public static final String PERM_MAPS = MapEditorService.PERMISSION;
    public static final String PERM_POOLS = "tactical.admin.pools";

    private final MapService maps;
    private final MapEditorService editor;
    private final ConfigService config;
    private final AuditService audit;
    private final LobbyService lobby;
    private final Tasks tasks;

    public MapCommands(MapService maps, MapEditorService editor, ConfigService config, AuditService audit,
                       LobbyService lobby, Tasks tasks) {
        this.maps = maps;
        this.editor = editor;
        this.config = config;
        this.audit = audit;
        this.lobby = lobby;
        this.tasks = tasks;
    }

    public void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(mapTree());
        root.then(poolTree());
        root.then(Commands.literal("lobby").requires(perm("tactical.admin.lobby"))
                .then(Commands.literal("setspawn").executes(ctx -> player(ctx).map(p -> {
                    try {
                        lobby.setSpawn(p.getLocation());
                    } catch (IOException e) {
                        return fail(ctx, "Could not save: " + e.getMessage());
                    }
                    audit.record(p, "LOBBY_SET_SPAWN", null, p.getLocation().toVector().toString());
                    return ok(ctx, "Lobby spawn set.");
                }).orElse(0)))
                .then(Commands.literal("tp").executes(ctx -> player(ctx).map(p -> {
                    lobby.sendToLobby(p);
                    return 1;
                }).orElse(0))));
    }

    // ===================================================================== /game map

    private LiteralArgumentBuilder<CommandSourceStack> mapTree() {
        return Commands.literal("map").requires(perm(PERM_MAPS))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> create(ctx, s(ctx, "id")))))
                .then(Commands.literal("edit").then(mapArg().executes(ctx -> edit(ctx, s(ctx, "map")))))
                .then(Commands.literal("leave").executes(this::leave))
                .then(Commands.literal("wand").executes(ctx -> player(ctx).map(p -> {
                    p.getInventory().addItem(editor.wand());
                    return ok(ctx, "Wand given. Left-click = corner 1, right-click = corner 2.");
                }).orElse(0)))
                .then(Commands.literal("preview").executes(ctx -> player(ctx).map(p -> {
                    editor.session(p).togglePreview();
                    return ok(ctx, "Preview " + (editor.session(p).preview() ? "on" : "off") + ".");
                }).orElse(0)))
                .then(Commands.literal("info").executes(ctx -> withEditing(ctx, m -> info(ctx, m)))
                        .then(mapArg().executes(ctx -> withMap(ctx, s(ctx, "map"), m -> info(ctx, m)))))
                .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> edit(ctx, m -> m.withName(s(ctx, "name"), now()), "RENAME"))))
                .then(Commands.literal("modes").then(Commands.argument("modes", StringArgumentType.greedyString())
                        .suggests((c, b) -> {
                            config.modes().keySet().forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> edit(ctx, m -> m.withModes(new LinkedHashSet<>(words(s(ctx, "modes"))), now()), "SET_MODES"))))
                .then(Commands.literal("bounds").executes(ctx -> withSelection(ctx, r ->
                        edit(ctx, m -> m.withBounds(r, now()), "SET_BOUNDS"))))
                .then(Commands.literal("voidy").then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
                        .executes(ctx -> edit(ctx, m -> m.withVoidY(IntegerArgumentType.getInteger(ctx, "y"), now()), "SET_VOID_Y"))))
                .then(Commands.literal("spawn")
                        .then(Commands.literal("add").then(teamArg().executes(ctx -> spawnAdd(ctx))))
                        .then(Commands.literal("clear").then(teamArg().executes(ctx -> spawnClear(ctx))))
                        .then(Commands.literal("removenearest").executes(this::spawnRemoveNearest)))
                .then(Commands.literal("site")
                        .then(Commands.literal("add").then(Commands.argument("site", StringArgumentType.word())
                                .executes(ctx -> siteAdd(ctx, s(ctx, "site")))))
                        .then(Commands.literal("remove").then(Commands.argument("site", StringArgumentType.word())
                                .executes(ctx -> edit(ctx, m -> {
                                    Map<String, BombSite> sites = new LinkedHashMap<>(m.sites());
                                    if (sites.remove(s(ctx, "site")) == null) throw new IllegalArgumentException("No such site");
                                    return m.withSites(sites, now());
                                }, "REMOVE_SITE")))))
                .then(Commands.literal("barrier")
                        .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> barrierAdd(ctx, false))
                                .then(Commands.literal("glass-only").executes(ctx -> barrierAdd(ctx, true)))))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> edit(ctx, m -> {
                                    List<Barrier> list = new ArrayList<>(m.barriers());
                                    if (!list.removeIf(b -> b.name().equals(s(ctx, "name")))) throw new IllegalArgumentException("No such barrier");
                                    return m.withBarriers(list, now());
                                }, "REMOVE_BARRIER")))))
                .then(Commands.literal("construction")
                        .then(Commands.literal("add").executes(ctx -> withSelection(ctx, r -> edit(ctx, m -> {
                            List<Region> list = new ArrayList<>(m.constructionRegions());
                            list.add(r);
                            return m.withConstructionRegions(list, now());
                        }, "ADD_CONSTRUCTION_REGION"))))
                        .then(Commands.literal("clear").executes(ctx -> edit(ctx, m -> m.withConstructionRegions(List.of(), now()),
                                "CLEAR_CONSTRUCTION_REGIONS"))))
                .then(Commands.literal("bombspawn")
                        .then(Commands.literal("set").executes(this::bombSpawnSet))
                        .then(Commands.literal("clear").executes(ctx -> edit(ctx, m -> m.withBombSpawn(null, now()), "CLEAR_BOMB_SPAWN"))))
                .then(Commands.literal("pickup")
                        .then(Commands.literal("add").then(Commands.argument("item", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    String r = b.getRemainingLowerCase();
                                    org.bukkit.Registry.ITEM.forEach(t -> {
                                        String k = t.getKey().getKey();
                                        if (k.startsWith(r)) b.suggest(k);
                                    });
                                    return b.buildFuture();
                                })
                                .executes(ctx -> pickupAdd(ctx, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> pickupAdd(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
                        .then(Commands.literal("removenearest").executes(this::pickupRemoveNearest))
                        .then(Commands.literal("clear").executes(ctx -> edit(ctx, m -> m.withPickups(List.of(), now()), "CLEAR_PICKUPS"))))
                .then(Commands.literal("kits").then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> {
                            b.suggest("all");
                            b.suggest("whitelist");
                            b.suggest("blacklist");
                            return b.buildFuture();
                        })
                        .executes(ctx -> kits(ctx, ""))
                        .then(Commands.argument("kits", StringArgumentType.greedyString()).executes(ctx -> kits(ctx, s(ctx, "kits"))))))
                .then(Commands.literal("validate").executes(ctx -> withEditing(ctx, m -> validate(ctx, m, false)))
                        .then(mapArg().executes(ctx -> withMap(ctx, s(ctx, "map"), m -> validate(ctx, m, false)))))
                .then(Commands.literal("publish").executes(ctx -> withEditing(ctx, m -> validate(ctx, m, true)))
                        .then(mapArg().executes(ctx -> withMap(ctx, s(ctx, "map"), m -> validate(ctx, m, true)))))
                .then(Commands.literal("enable").then(mapArg().executes(ctx -> setEnabled(ctx, true))))
                .then(Commands.literal("disable").then(mapArg().executes(ctx -> setEnabled(ctx, false))))
                .then(Commands.literal("delete").then(mapArg()
                        .executes(ctx -> fail(ctx, "This deletes the map, its template world and snapshot. Repeat with 'confirm' at the end."))
                        .then(Commands.literal("confirm").executes(ctx -> delete(ctx, s(ctx, "map"))))));
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        if (maps.maps().isEmpty()) return ok(ctx, "No maps yet. Create one with /game map create <id>.");
        for (MapDefinition m : maps.maps().values()) {
            reply(ctx, m.id() + " \"" + m.name() + "\" v" + m.version() + " " + m.status() + (m.enabled() ? " enabled" : " disabled")
                    + " modes=" + String.join(",", m.modes()) + (maps.worlds().hasSnapshot(m.id()) ? "" : " (no snapshot)"),
                    NamedTextColor.YELLOW);
        }
        return 1;
    }

    private int create(CommandContext<CommandSourceStack> ctx, String id) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        if (!MapCodec.ID.matcher(id).matches()) return fail(ctx, "Map id must be 1-32 chars of a-z, 0-9, _");
        if (maps.map(id).isPresent()) return fail(ctx, "Map '" + id + "' already exists.");
        MapDefinition def = MapDefinition.blank(id, p.getName(), now());
        try {
            maps.save(def);
        } catch (IOException e) {
            return fail(ctx, "Could not save: " + e.getMessage());
        }
        editor.startEditing(p, def);
        audit.record(p, "MAP_CREATE", id, "");
        ok(ctx, "Created map '" + id + "' and opened its empty template world. Build it, then set:");
        reply(ctx, " /game map wand, /game map bounds, /game map spawn add attackers|defenders,", NamedTextColor.GRAY);
        reply(ctx, " /game map site add A, /game map barrier add <name> glass-only, /game map validate, /game map publish", NamedTextColor.GRAY);
        return 1;
    }

    private int edit(CommandContext<CommandSourceStack> ctx, String id) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        return withMap(ctx, id, m -> {
            editor.startEditing(p, m);
            audit.record(p, "MAP_EDIT_START", id, "");
            return ok(ctx, "Editing '" + id + "' (v" + m.version() + "). /game map leave to return to the lobby.");
        });
    }

    private int leave(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        editor.stopEditing(p);
        lobby.sendToLobby(p);
        return ok(ctx, "Left the map editor.");
    }

    private int info(CommandContext<CommandSourceStack> ctx, MapDefinition m) {
        reply(ctx, "== " + m.id() + " \"" + m.name() + "\" by " + m.author() + " ==", NamedTextColor.GOLD);
        reply(ctx, "version " + m.version() + ", " + m.status() + ", " + (m.enabled() ? "enabled" : "disabled")
                + ", modes " + String.join(",", m.modes()), NamedTextColor.YELLOW);
        reply(ctx, "bounds " + (m.bounds() == null ? "NOT SET" : m.bounds()) + ", void-y " + m.voidY(), NamedTextColor.GRAY);
        reply(ctx, "spawns: " + m.attackerSpawns().size() + " attackers, " + m.defenderSpawns().size() + " defenders", NamedTextColor.GRAY);
        for (BombSite s : m.sites().values()) reply(ctx, "site " + s.id() + ": " + s.region(), NamedTextColor.GRAY);
        for (Barrier b : m.barriers()) reply(ctx, "barrier " + b.name() + ": " + b.region()
                + (b.onlyMaterials().isEmpty() ? "" : " only " + b.onlyMaterials()), NamedTextColor.GRAY);
        reply(ctx, "bomb spawn " + (m.bombSpawn() == null ? "NOT SET (first attacker spawn is used)"
                : m.bombSpawn().x() + " " + m.bombSpawn().y() + " " + m.bombSpawn().z()), NamedTextColor.GRAY);
        for (var k : m.pickups()) reply(ctx, "pickup " + k.amount() + "x " + k.item() + " at " + k.x() + " " + k.y() + " " + k.z(), NamedTextColor.GRAY);
        reply(ctx, "construction regions: " + m.constructionRegions().size() + ", kits: " + m.kits().mode()
                + (m.kits().kits().isEmpty() ? "" : " " + m.kits().kits()), NamedTextColor.GRAY);
        return 1;
    }

    private int spawnAdd(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        String team = s(ctx, "team");
        Location l = p.getLocation();
        SpawnPoint sp = new SpawnPoint(l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, Math.round(l.getYaw() / 15f) * 15f, 0);
        return edit(ctx, m -> {
            requireInTemplate(p, m);
            List<SpawnPoint> atk = new ArrayList<>(m.attackerSpawns());
            List<SpawnPoint> def = new ArrayList<>(m.defenderSpawns());
            (isAttackers(team) ? atk : def).add(sp);
            return m.withSpawns(atk, def, now());
        }, "ADD_SPAWN");
    }

    private int spawnClear(CommandContext<CommandSourceStack> ctx) {
        String team = s(ctx, "team");
        return edit(ctx, m -> isAttackers(team) ? m.withSpawns(List.of(), m.defenderSpawns(), now())
                : m.withSpawns(m.attackerSpawns(), List.of(), now()), "CLEAR_SPAWNS");
    }

    /** The bomb lies here (block centre, on the ground below) at the start of every round. */
    private int bombSpawnSet(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        Location l = p.getLocation();
        SpawnPoint sp = new SpawnPoint(l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5, 0, 0);
        return edit(ctx, m -> {
            requireInTemplate(p, m);
            return m.withBombSpawn(sp, now());
        }, "SET_BOMB_SPAWN");
    }

    private int pickupAdd(CommandContext<CommandSourceStack> ctx, int amount) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        String item = s(ctx, "item").toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
        if (dev.tactical.paper.arena.MatchArena.parseItem(item) == null) return fail(ctx, "Unknown item '" + item + "'.");
        Location l = p.getLocation();
        double x = Math.round(l.getX() * 2) / 2.0, y = Math.round(l.getY() * 2) / 2.0, z = Math.round(l.getZ() * 2) / 2.0;
        return edit(ctx, m -> {
            List<dev.tactical.engine.map.Pickup> list = new ArrayList<>(m.pickups());
            list.add(new dev.tactical.engine.map.Pickup(item, amount, x, y, z));
            return m.withPickups(list, now());
        }, "ADD_PICKUP");
    }

    private int pickupRemoveNearest(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        Location l = p.getLocation();
        return edit(ctx, m -> {
            List<dev.tactical.engine.map.Pickup> list = new ArrayList<>(m.pickups());
            dev.tactical.engine.map.Pickup best = null;
            double bestD = 9.0; // within 3 blocks
            for (dev.tactical.engine.map.Pickup k : list) {
                double d = Math.pow(k.x() - l.getX(), 2) + Math.pow(k.y() - l.getY(), 2) + Math.pow(k.z() - l.getZ(), 2);
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            if (best == null) throw new IllegalArgumentException("No pickup within 3 blocks.");
            list.remove(best);
            return m.withPickups(list, now());
        }, "REMOVE_PICKUP");
    }

    private int spawnRemoveNearest(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        Location l = p.getLocation();
        return edit(ctx, m -> {
            List<SpawnPoint> atk = new ArrayList<>(m.attackerSpawns());
            List<SpawnPoint> def = new ArrayList<>(m.defenderSpawns());
            SpawnPoint best = null;
            List<SpawnPoint> owner = null;
            double bestD = 9.0; // within 3 blocks
            for (List<SpawnPoint> list : List.of(atk, def)) {
                for (SpawnPoint s : list) {
                    double d = Math.pow(s.x() - l.getX(), 2) + Math.pow(s.y() - l.getY(), 2) + Math.pow(s.z() - l.getZ(), 2);
                    if (d < bestD) {
                        bestD = d;
                        best = s;
                        owner = list;
                    }
                }
            }
            if (best == null) throw new IllegalArgumentException("No spawn point within 3 blocks.");
            owner.remove(best);
            return m.withSpawns(atk, def, now());
        }, "REMOVE_SPAWN");
    }

    private int siteAdd(CommandContext<CommandSourceStack> ctx, String site) {
        if (!MapCodec.SITE_ID.matcher(site).matches()) return fail(ctx, "Site id must be 1-16 letters/digits/_");
        return withSelection(ctx, r -> edit(ctx, m -> {
            Map<String, BombSite> sites = new LinkedHashMap<>(m.sites());
            sites.put(site, new BombSite(site, site, r));
            return m.withSites(sites, now());
        }, "SET_SITE"));
    }

    private int barrierAdd(CommandContext<CommandSourceStack> ctx, boolean glassOnly) {
        String name = s(ctx, "name");
        List<String> only = glassOnly ? glassMaterials() : List.of();
        return withSelection(ctx, r -> edit(ctx, m -> {
            List<Barrier> list = new ArrayList<>(m.barriers());
            list.removeIf(b -> b.name().equals(name));
            list.add(new Barrier(name, r, only));
            return m.withBarriers(list, now());
        }, "SET_BARRIER"));
    }

    private int kits(CommandContext<CommandSourceStack> ctx, String kitList) {
        KitRestriction.Mode mode;
        try {
            mode = KitRestriction.Mode.valueOf(s(ctx, "mode").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fail(ctx, "Mode must be all, whitelist or blacklist.");
        }
        Set<String> kits = Set.copyOf(words(kitList));
        return edit(ctx, m -> m.withKits(new KitRestriction(mode, mode == KitRestriction.Mode.ALL ? Set.of() : kits), now()), "SET_KITS");
    }

    private int validate(CommandContext<CommandSourceStack> ctx, MapDefinition m, boolean publish) {
        CommandSender sender = ctx.getSource().getSender();
        int teamSize = config.modes().values().stream().mapToInt(r -> r.teamSize()).max().orElse(5);
        List<MapValidator.Problem> problems = new ArrayList<>(
                MapValidator.validate(m, config.kits().keySet(), config.modes().keySet(), teamSize));
        if (!maps.worlds().templateExists(m.id())) {
            problems.add(new MapValidator.Problem(true, "Template world does not exist."));
        } else {
            World w = maps.worlds().loadTemplate(m.id());
            problems.addAll(editor.worldChecks(m, w));
        }
        for (MapValidator.Problem p : problems) {
            reply(ctx, p.toString(), p.error() ? NamedTextColor.RED : NamedTextColor.YELLOW);
        }
        if (MapValidator.hasErrors(problems)) {
            return fail(ctx, "Validation failed: fix the errors above. The map stays " + m.status() + ".");
        }
        MapStatus target = publish ? MapStatus.PUBLISHED : MapStatus.VALIDATED;
        maps.worlds().snapshot(m.id()).whenCompleteAsync((v, t) -> {
            if (t != null) {
                sender.sendMessage(Component.text("Snapshot failed: " + t.getMessage(), NamedTextColor.RED));
                return;
            }
            try {
                MapDefinition current = maps.map(m.id()).orElse(m);
                if (current.version() != m.version()) {
                    sender.sendMessage(Component.text("The map changed during validation; run it again.", NamedTextColor.RED));
                    return;
                }
                maps.save(current.withStatus(target, now()));
                audit.record(sender, publish ? "MAP_PUBLISH" : "MAP_VALIDATE", m.id(), "v" + m.version());
                sender.sendMessage(Component.text("Map '" + m.id() + "' v" + m.version() + " is " + target
                        + ". Snapshot saved; future matches use this exact layout."
                        + (publish && !current.enabled() ? " It is still DISABLED: /game map enable " + m.id() : ""), NamedTextColor.GREEN));
            } catch (IOException e) {
                sender.sendMessage(Component.text("Could not save: " + e.getMessage(), NamedTextColor.RED));
            }
        }, tasks.main());
        return ok(ctx, "Validation passed; taking snapshot...");
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        return withMap(ctx, s(ctx, "map"), m -> {
            try {
                maps.save(m.withEnabled(enabled, now()));
            } catch (IOException e) {
                return fail(ctx, "Could not save: " + e.getMessage());
            }
            audit.record(ctx.getSource().getSender(), enabled ? "MAP_ENABLE" : "MAP_DISABLE", m.id(), "");
            return ok(ctx, "Map '" + m.id() + "' " + (enabled ? "enabled" : "disabled") + ". Running matches are not affected.");
        });
    }

    private int delete(CommandContext<CommandSourceStack> ctx, String id) {
        CommandSender sender = ctx.getSource().getSender();
        return withMap(ctx, id, m -> {
            maps.worlds().loadedTemplate(id).ifPresent(w -> w.getPlayers().forEach(lobby::sendToLobby));
            maps.worlds().deleteTemplate(id).whenCompleteAsync((v, t) -> {
                if (t != null) {
                    sender.sendMessage(Component.text("Could not delete world: " + t.getMessage(), NamedTextColor.RED));
                    return;
                }
                try {
                    maps.deleteDefinition(id);
                    audit.record(sender, "MAP_DELETE", id, "");
                    sender.sendMessage(Component.text("Deleted map '" + id + "'. Its file was moved to backups/maps/.", NamedTextColor.GREEN));
                } catch (IOException e) {
                    sender.sendMessage(Component.text("Could not delete definition: " + e.getMessage(), NamedTextColor.RED));
                }
            }, tasks.main());
            return 1;
        });
    }

    // ===================================================================== /game pool

    private LiteralArgumentBuilder<CommandSourceStack> poolTree() {
        return Commands.literal("pool").requires(perm(PERM_POOLS))
                .then(Commands.literal("list").executes(this::poolList))
                .then(Commands.literal("create").then(Commands.argument("pool", StringArgumentType.word())
                        .then(Commands.argument("mode", StringArgumentType.word()).suggests(modes())
                                .executes(ctx -> poolCreate(ctx)))))
                .then(Commands.literal("delete").then(poolArg().executes(ctx -> withPool(ctx, p -> {
                    try {
                        maps.deletePool(p.id());
                    } catch (IOException e) {
                        return fail(ctx, e.getMessage());
                    }
                    audit.record(ctx.getSource().getSender(), "POOL_DELETE", p.id(), "");
                    return ok(ctx, "Deleted pool '" + p.id() + "' (backup in backups/map-pools/).");
                }))))
                .then(Commands.literal("rename").then(poolArg().then(Commands.argument("new", StringArgumentType.word())
                        .executes(ctx -> withPool(ctx, p -> {
                            String nid = s(ctx, "new");
                            if (!MapCodec.ID.matcher(nid).matches() || maps.pool(nid).isPresent()) return fail(ctx, "Invalid or taken id.");
                            try {
                                maps.savePool(new MapPool(nid, p.mode(), p.enabled(), p.maps()));
                                maps.deletePool(p.id());
                            } catch (IOException e) {
                                return fail(ctx, e.getMessage());
                            }
                            audit.record(ctx.getSource().getSender(), "POOL_RENAME", p.id(), "to " + nid);
                            return ok(ctx, "Renamed pool to '" + nid + "'.");
                        })))))
                .then(Commands.literal("add").then(poolArg().then(mapArg().executes(ctx -> poolSet(ctx, true)))))
                .then(Commands.literal("remove").then(poolArg().then(mapArg().executes(ctx -> withPool(ctx, p -> savePool(ctx,
                        p.withoutMap(s(ctx, "map")), "POOL_REMOVE_MAP"))))))
                .then(Commands.literal("toggle").then(poolArg().then(mapArg()
                        .then(Commands.literal("on").executes(ctx -> poolSet(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> poolSet(ctx, false))))))
                .then(Commands.literal("enable").then(poolArg().executes(ctx -> withPool(ctx, p ->
                        savePool(ctx, new MapPool(p.id(), p.mode(), true, p.maps()), "POOL_ENABLE")))))
                .then(Commands.literal("disable").then(poolArg().executes(ctx -> withPool(ctx, p ->
                        savePool(ctx, new MapPool(p.id(), p.mode(), false, p.maps()), "POOL_DISABLE")))));
    }

    private int poolList(CommandContext<CommandSourceStack> ctx) {
        for (MapPool p : maps.pools().values()) {
            List<String> eligible = MapSelector.eligible(p, maps.maps());
            reply(ctx, p.id() + " (mode " + p.mode() + ", " + (p.enabled() ? "enabled" : "disabled") + "): "
                    + eligible.size() + " selectable", eligible.isEmpty() && p.enabled() ? NamedTextColor.RED : NamedTextColor.YELLOW);
            for (Map.Entry<String, Boolean> e : p.maps().entrySet()) {
                MapDefinition m = maps.map(e.getKey()).orElse(null);
                String why = m == null ? "unknown map" : !e.getValue() ? "off in pool" : !m.enabled() ? "map disabled"
                        : m.status() != MapStatus.PUBLISHED ? "not published" : !m.supportsMode(p.mode()) ? "mode not supported" : "selectable";
                reply(ctx, "  " + e.getKey() + ": " + why, why.equals("selectable") ? NamedTextColor.GREEN : NamedTextColor.GRAY);
            }
        }
        return 1;
    }

    private int poolCreate(CommandContext<CommandSourceStack> ctx) {
        String id = s(ctx, "pool");
        String mode = s(ctx, "mode");
        if (!MapCodec.ID.matcher(id).matches()) return fail(ctx, "Pool id must be 1-32 chars of a-z, 0-9, _");
        if (maps.pool(id).isPresent()) return fail(ctx, "Pool exists.");
        if (config.mode(mode).isEmpty()) return fail(ctx, "Unknown mode '" + mode + "'.");
        return savePool(ctx, new MapPool(id, mode, true, Map.of()), "POOL_CREATE");
    }

    private int poolSet(CommandContext<CommandSourceStack> ctx, boolean on) {
        String mapId = s(ctx, "map");
        if (maps.map(mapId).isEmpty()) return fail(ctx, "Unknown map '" + mapId + "'.");
        return withPool(ctx, p -> savePool(ctx, p.withMap(mapId, on), on ? "POOL_MAP_ON" : "POOL_MAP_OFF"));
    }

    private int savePool(CommandContext<CommandSourceStack> ctx, MapPool pool, String action) {
        try {
            maps.savePool(pool);
        } catch (IOException e) {
            return fail(ctx, "Could not save pool: " + e.getMessage());
        }
        audit.record(ctx.getSource().getSender(), action, pool.id(), String.valueOf(pool.maps()));
        int n = MapSelector.eligible(pool, maps.maps()).size();
        return ok(ctx, "Pool '" + pool.id() + "' saved: " + n + " selectable map(s). Applies to future matches only."
                + (n == 0 && pool.enabled() ? " WARNING: matchmaking for " + pool.mode() + " cannot start with 0 maps." : ""));
    }

    // ===================================================================== helpers

    @FunctionalInterface
    private interface Edit {
        MapDefinition apply(MapDefinition m);
    }

    /** Applies an edit to the map being edited, saves it, audits it. */
    private int edit(CommandContext<CommandSourceStack> ctx, Edit edit, String action) {
        return withEditing(ctx, m -> {
            MapDefinition updated;
            try {
                updated = edit.apply(m);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return fail(ctx, e.getMessage());
            }
            try {
                maps.save(updated);
            } catch (IOException e) {
                return fail(ctx, "Could not save: " + e.getMessage());
            }
            audit.record(ctx.getSource().getSender(), "MAP_" + action, m.id(), "v" + updated.version());
            return ok(ctx, "Saved '" + m.id() + "' (v" + updated.version() + ", now DRAFT until validated).");
        });
    }

    private int withEditing(CommandContext<CommandSourceStack> ctx, java.util.function.ToIntFunction<MapDefinition> f) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        Optional<String> id = editor.session(p).mapId();
        if (id.isEmpty()) return fail(ctx, "You are not editing a map. Use /game map edit <id>.");
        return withMap(ctx, id.get(), f);
    }

    private int withMap(CommandContext<CommandSourceStack> ctx, String id, java.util.function.ToIntFunction<MapDefinition> f) {
        Optional<MapDefinition> m = maps.map(id);
        if (m.isEmpty()) return fail(ctx, "Unknown map '" + id + "'.");
        return f.applyAsInt(m.get());
    }

    private int withPool(CommandContext<CommandSourceStack> ctx, java.util.function.ToIntFunction<MapPool> f) {
        Optional<MapPool> p = maps.pool(s(ctx, "pool"));
        if (p.isEmpty()) return fail(ctx, "Unknown pool '" + s(ctx, "pool") + "'.");
        return f.applyAsInt(p.get());
    }

    private int withSelection(CommandContext<CommandSourceStack> ctx, java.util.function.ToIntFunction<Region> f) {
        Player p = player(ctx).orElse(null);
        if (p == null) return 0;
        Optional<Region> r = editor.session(p).selection();
        if (r.isEmpty()) return fail(ctx, "Select two corners with the wand first (/game map wand).");
        Optional<String> id = editor.session(p).mapId();
        if (id.isEmpty() || !p.getWorld().getKey().equals(MapWorlds.templateKey(id.get()))) {
            return fail(ctx, "Stand in the template world of the map you are editing.");
        }
        return f.applyAsInt(r.get());
    }

    private static void requireInTemplate(Player p, MapDefinition m) {
        if (!p.getWorld().getKey().equals(MapWorlds.templateKey(m.id()))) {
            throw new IllegalStateException("Stand in the template world of '" + m.id() + "'.");
        }
    }

    private static List<String> glassMaterials() {
        List<String> out = new ArrayList<>();
        for (org.bukkit.Material mat : org.bukkit.Material.values()) {
            String k = mat.getKey().getKey();
            if (!mat.isLegacy() && mat.isBlock() && (k.equals("glass") || k.endsWith("_glass") || k.equals("glass_pane")
                    || k.endsWith("_glass_pane") || k.equals("tinted_glass"))) {
                out.add("minecraft:" + k);
            }
        }
        return out;
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> mapArg() {
        return Commands.argument("map", StringArgumentType.word()).suggests((c, b) -> {
            maps.maps().keySet().forEach(b::suggest);
            return b.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> poolArg() {
        return Commands.argument("pool", StringArgumentType.word()).suggests((c, b) -> {
            maps.pools().keySet().forEach(b::suggest);
            return b.buildFuture();
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> teamArg() {
        return Commands.argument("team", StringArgumentType.word()).suggests((c, b) -> {
            b.suggest("attackers");
            b.suggest("defenders");
            return b.buildFuture();
        });
    }

    private SuggestionProvider<CommandSourceStack> modes() {
        return (c, b) -> {
            config.modes().keySet().forEach(b::suggest);
            return b.buildFuture();
        };
    }

    private static boolean isAttackers(String team) {
        return team.toLowerCase(Locale.ROOT).startsWith("a");
    }

    private static List<String> words(String s) {
        return s.isBlank() ? List.of() : Arrays.stream(s.trim().split("[\\s,]+")).toList();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String s(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static Optional<Player> player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) return Optional.of(p);
        ctx.getSource().getSender().sendMessage(Component.text("Only players can do this.", NamedTextColor.RED));
        return Optional.empty();
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String node) {
        return src -> src.getSender().hasPermission(node);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        reply(ctx, msg, NamedTextColor.GREEN);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        reply(ctx, msg, NamedTextColor.RED);
        return 0;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text, NamedTextColor color) {
        ctx.getSource().getSender().sendMessage(Component.text(text, color));
    }
}
