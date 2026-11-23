package dev.tactical.engine.map;

import dev.tactical.engine.config.ConfigNode;
import dev.tactical.engine.geo.BlockPos;
import dev.tactical.engine.geo.Region;
import dev.tactical.engine.geo.SpawnPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads and writes {@code maps/<id>.yml}. Writing produces a stable key order so diffs stay readable. */
public final class MapCodec {

    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    public static final Pattern SITE_ID = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final List<String> KEYS = List.of("schema-version", "id", "name", "author", "enabled", "version",
            "status", "modes", "bounds", "void-y", "spawns", "bomb-sites", "barriers", "construction", "kits", "shop",
            "updated-at");

    private MapCodec() {
    }

    public static Optional<MapDefinition> parse(ConfigNode root, String expectedId) {
        int before = root.report().errors().size();
        root.warnUnknownKeys(KEYS);
        String id = root.string("id", expectedId);
        if (!id.equals(expectedId)) root.child("id").error("must match the file name '" + expectedId + "'");
        if (!ID.matcher(id).matches()) root.child("id").error("must be 1-32 chars of a-z, 0-9, _");
        String name = root.string("name", id);
        String author = root.string("author", "");
        boolean enabled = root.bool("enabled", false);
        int version = root.integer("version", 1, 1, Integer.MAX_VALUE);
        MapStatus status = root.enumValue("status", MapStatus.class, MapStatus.DRAFT);
        Set<String> modes = new LinkedHashSet<>(root.stringList("modes"));
        Region bounds = root.has("bounds") ? region(root.child("bounds")) : null;
        int voidY = root.integer("void-y", -64, -2048, 2048);

        ConfigNode spawns = root.child("spawns");
        spawns.warnUnknownKeys(List.of("attackers", "defenders"));
        List<SpawnPoint> atk = spawnList(spawns.child("attackers"));
        List<SpawnPoint> def = spawnList(spawns.child("defenders"));

        Map<String, BombSite> sites = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigNode> e : root.child("bomb-sites").children().entrySet()) {
            ConfigNode s = e.getValue();
            if (!SITE_ID.matcher(e.getKey()).matches()) s.error("site id must be 1-16 letters/digits/_");
            s.warnUnknownKeys(List.of("display-name", "region"));
            Region r = region(s.child("region"));
            if (r != null) sites.put(e.getKey(), new BombSite(e.getKey(), s.string("display-name", e.getKey()), r));
        }

        List<Barrier> barriers = new ArrayList<>();
        for (Map.Entry<String, ConfigNode> e : root.child("barriers").children().entrySet()) {
            ConfigNode b = e.getValue();
            b.warnUnknownKeys(List.of("region", "only"));
            Region r = region(b.child("region"));
            if (r != null) barriers.add(new Barrier(e.getKey(), r, b.stringList("only")));
        }

        List<Region> construction = new ArrayList<>();
        ConfigNode cons = root.child("construction");
        cons.warnUnknownKeys(List.of("allowed-regions"));
        for (ConfigNode n : cons.child("allowed-regions").list()) {
            Region r = region(n);
            if (r != null) construction.add(r);
        }

        ConfigNode k = root.child("kits");
        k.warnUnknownKeys(List.of("mode", "list"));
        KitRestriction kits = new KitRestriction(k.enumValue("mode", KitRestriction.Mode.class, KitRestriction.Mode.ALL),
                Set.copyOf(k.stringList("list")));
        String shop = root.string("shop", "default");
        long updated = (long) root.number("updated-at", 0, 0, Double.MAX_VALUE);

        if (root.report().errors().size() > before) return Optional.empty();
        return Optional.of(new MapDefinition(id, name, author, enabled, version, status, modes, bounds, atk, def, sites,
                barriers, construction, kits, shop, voidY, updated));
    }

    /** Serialises to plain maps/lists for YAML dumping. */
    public static Map<String, Object> toYaml(MapDefinition m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schema-version", MapDefinition.SCHEMA_VERSION);
        o.put("id", m.id());
        o.put("name", m.name());
        o.put("author", m.author());
        o.put("enabled", m.enabled());
        o.put("version", m.version());
        o.put("status", m.status().name().toLowerCase(Locale.ROOT));
        o.put("modes", List.copyOf(m.modes()));
        if (m.bounds() != null) o.put("bounds", region(m.bounds()));
        o.put("void-y", m.voidY());
        Map<String, Object> spawns = new LinkedHashMap<>();
        spawns.put("attackers", m.attackerSpawns().stream().map(MapCodec::spawn).toList());
        spawns.put("defenders", m.defenderSpawns().stream().map(MapCodec::spawn).toList());
        o.put("spawns", spawns);
        Map<String, Object> sites = new LinkedHashMap<>();
        for (BombSite s : m.sites().values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList()) {
            Map<String, Object> so = new LinkedHashMap<>();
            so.put("display-name", s.displayName());
            so.put("region", region(s.region()));
            sites.put(s.id(), so);
        }
        o.put("bomb-sites", sites);
        Map<String, Object> barriers = new LinkedHashMap<>();
        for (Barrier b : m.barriers()) {
            Map<String, Object> bo = new LinkedHashMap<>();
            bo.put("region", region(b.region()));
            if (!b.onlyMaterials().isEmpty()) bo.put("only", b.onlyMaterials());
            barriers.put(b.name(), bo);
        }
        o.put("barriers", barriers);
        o.put("construction", Map.of("allowed-regions", m.constructionRegions().stream().map(MapCodec::region).toList()));
        Map<String, Object> kits = new LinkedHashMap<>();
        kits.put("mode", m.kits().mode().name().toLowerCase(Locale.ROOT));
        kits.put("list", m.kits().kits().stream().sorted().toList());
        o.put("kits", kits);
        o.put("shop", m.shop());
        o.put("updated-at", m.updatedAt());
        return o;
    }

    private static Region region(ConfigNode n) {
        if (n.isMissing()) return null;
        n.warnUnknownKeys(List.of("min", "max"));
        BlockPos a = pos(n.child("min"));
        BlockPos b = pos(n.child("max"));
        if (a == null || b == null) {
            n.error("region needs min: [x, y, z] and max: [x, y, z]");
            return null;
        }
        return Region.of(a, b);
    }

    private static BlockPos pos(ConfigNode n) {
        List<ConfigNode> l = n.list();
        if (l.size() != 3) return null;
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                v[i] = Integer.parseInt(l.get(i).scalar().orElse("x").trim());
            } catch (NumberFormatException e) {
                l.get(i).error("must be a whole number");
                return null;
            }
        }
        return new BlockPos(v[0], v[1], v[2]);
    }

    private static List<SpawnPoint> spawnList(ConfigNode n) {
        List<SpawnPoint> out = new ArrayList<>();
        for (ConfigNode s : n.list()) {
            s.warnUnknownKeys(List.of("x", "y", "z", "yaw", "pitch"));
            for (String key : List.of("x", "y", "z")) {
                if (!s.has(key)) s.error("spawn needs x, y and z");
            }
            out.add(new SpawnPoint(s.number("x", 0, -3e7, 3e7), s.number("y", 0, -2048, 2048), s.number("z", 0, -3e7, 3e7),
                    (float) s.number("yaw", 0, -360, 360), (float) s.number("pitch", 0, -90, 90)));
        }
        return out;
    }

    private static Map<String, Object> region(Region r) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("min", List.of(r.min().x(), r.min().y(), r.min().z()));
        o.put("max", List.of(r.max().x(), r.max().y(), r.max().z()));
        return o;
    }

    private static Map<String, Object> spawn(SpawnPoint s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("x", round(s.x()));
        o.put("y", round(s.y()));
        o.put("z", round(s.z()));
        o.put("yaw", (double) Math.round(s.yaw() * 10) / 10);
        o.put("pitch", (double) Math.round(s.pitch() * 10) / 10);
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
