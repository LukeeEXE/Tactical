package dev.tactical.engine.map;

import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.engine.geo.BlockPos;
import dev.tactical.engine.geo.Region;
import dev.tactical.engine.geo.SpawnPoint;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class MapTest {

    static final List<String> KITS = List.of("a", "b", "c", "d", "e", "f");

    static MapDefinition valid() {
        long now = 1;
        MapDefinition m = MapDefinition.blank("ascent", "tester", now);
        m = m.withBounds(Region.of(new BlockPos(-100, 0, -100), new BlockPos(100, 100, 100)), now);
        m = m.withSpawns(List.of(new SpawnPoint(-50, 10, 0, 90, 0)), List.of(new SpawnPoint(50, 10, 0, -90, 0)), now);
        m = m.withSites(Map.of(
                "A", new BombSite("A", "A", Region.of(new BlockPos(0, 9, 30), new BlockPos(10, 12, 40))),
                "B", new BombSite("B", "B", Region.of(new BlockPos(0, 9, -40), new BlockPos(10, 12, -30)))), now);
        return m;
    }

    @Test
    void roundTripsThroughYaml() {
        MapDefinition m = valid().withBarriers(List.of(new Barrier("atk", Region.of(new BlockPos(-45, 10, -3), new BlockPos(-45, 13, 3)),
                List.of("minecraft:glass"))), 2).withPickups(List.of(new Pickup("arrow", 8, 1.5, 10, 2.5)), 3);
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = new Yaml(o).dump(MapCodec.toYaml(m));
        ConfigReport r = new ConfigReport();
        MapDefinition back = MapCodec.parse(YamlLoader.parse(yaml, "maps/ascent.yml", r).orElseThrow(), "ascent").orElseThrow();
        assertFalse(r.hasErrors(), r.issues().toString());
        assertEquals(m, back);
    }

    @Test
    void editsBumpVersionAndDemoteToDraft() {
        MapDefinition m = valid().withStatus(MapStatus.PUBLISHED, 5);
        int v = m.version();
        MapDefinition e = m.withName("New", 6);
        assertEquals(v + 1, e.version());
        assertEquals(MapStatus.DRAFT, e.status());
        assertEquals(v, m.withEnabled(true, 7).version(), "enabling is not a layout change");
    }

    @Test
    void validatorFindsProblems() {
        assertFalse(MapValidator.hasErrors(MapValidator.validate(valid(), KITS, Set.of("quickplay", "competitive"), 5)));
        List<MapValidator.Problem> p = MapValidator.validate(MapDefinition.blank("x", "t", 0), KITS, Set.of("quickplay"), 5);
        assertTrue(p.stream().anyMatch(x -> x.message().contains("boundaries")));
        assertTrue(p.stream().anyMatch(x -> x.message().contains("attacker spawn")));
        assertTrue(p.stream().anyMatch(x -> x.message().contains("bomb sites")));
        MapDefinition overlap = valid().withSites(Map.of(
                "A", new BombSite("A", "A", Region.of(new BlockPos(0, 9, 0), new BlockPos(10, 12, 10))),
                "B", new BombSite("B", "B", Region.of(new BlockPos(5, 9, 5), new BlockPos(15, 12, 15)))), 3);
        assertTrue(MapValidator.validate(overlap, KITS, Set.of("quickplay"), 5).stream().anyMatch(x -> x.message().contains("overlap")));
        MapDefinition fewKits = valid().withKits(new KitRestriction(KitRestriction.Mode.WHITELIST, Set.of("a", "b")), 3);
        assertTrue(MapValidator.hasErrors(MapValidator.validate(fewKits, KITS, Set.of("quickplay"), 5)));
    }

    @Test
    void selectorOnlyOffersEligibleMapsAndAvoidsRecent() {
        Map<String, MapDefinition> maps = new HashMap<>();
        for (String id : List.of("m1", "m2", "m3", "m4")) {
            maps.put(id, MapDefinition.blank(id, "t", 0).withEnabled(true, 0).withStatus(MapStatus.PUBLISHED, 0));
        }
        maps.put("draft", MapDefinition.blank("draft", "t", 0).withEnabled(true, 0));
        MapPool pool = new MapPool("qp", "quickplay", true, Map.of("m1", true, "m2", true, "m3", false, "m4", true, "draft", true, "ghost", true));
        List<String> eligible = MapSelector.eligible(pool, maps);
        assertEquals(List.of("m1", "m2", "m4"), eligible);
        List<String> c = MapSelector.candidates(eligible, List.of("m1"), 2, new SplittableRandom(1));
        assertEquals(2, c.size());
        assertFalse(c.contains("m1"), "recently played map avoided while alternatives exist");
        assertEquals(3, MapSelector.candidates(eligible, List.of("m1", "m2", "m4"), 3, new SplittableRandom(1)).size());
    }

    @Test
    void selectorIsUnbiased() {
        List<String> eligible = List.of("a", "b", "c", "d", "e");
        Map<String, Integer> counts = new HashMap<>();
        SplittableRandom r = new SplittableRandom(7);
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            for (String id : MapSelector.candidates(eligible, List.of(), 3, r)) counts.merge(id, 1, Integer::sum);
        }
        for (String id : eligible) {
            double share = counts.get(id) / (double) n;
            assertEquals(0.6, share, 0.02, id + " offered " + share);
        }
    }

    @Test
    void regionBasics() {
        Region r = Region.of(new BlockPos(5, 5, 5), new BlockPos(0, 0, 0));
        assertEquals(new BlockPos(0, 0, 0), r.min());
        assertEquals(216, r.volume());
        assertTrue(r.contains(5.9, 0.1, 3));
        assertFalse(r.contains(6.0, 0, 0));
        int n = 0;
        for (BlockPos ignored : r) n++;
        assertEquals(216, n);
    }
}
