package dev.tactical.engine.map;

import dev.tactical.engine.geo.Region;
import dev.tactical.engine.geo.SpawnPoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable map configuration ({@code maps/<id>.yml}). The editor produces modified copies via the {@code with*}
 * methods; every saved change increments {@link #version()} (recorded in match logs so a recording always knows
 * the exact layout it was played on) and resets PUBLISHED/VALIDATED maps back to DRAFT until re-validated.
 */
public record MapDefinition(
        String id,
        String name,
        String author,
        boolean enabled,
        int version,
        MapStatus status,
        Set<String> modes,
        Region bounds,
        List<SpawnPoint> attackerSpawns,
        List<SpawnPoint> defenderSpawns,
        Map<String, BombSite> sites,
        List<Barrier> barriers,
        List<Region> constructionRegions,
        KitRestriction kits,
        String shop,
        int voidY,
        long updatedAt
) {
    public static final int SCHEMA_VERSION = 1;

    public MapDefinition {
        modes = Set.copyOf(modes);
        attackerSpawns = List.copyOf(attackerSpawns);
        defenderSpawns = List.copyOf(defenderSpawns);
        sites = Map.copyOf(sites);
        barriers = List.copyOf(barriers);
        constructionRegions = List.copyOf(constructionRegions);
    }

    public static MapDefinition blank(String id, String author, long now) {
        return new MapDefinition(id, id, author, false, 1, MapStatus.DRAFT, Set.of("quickplay", "competitive"),
                null, List.of(), List.of(), Map.of(), List.of(), List.of(), KitRestriction.ALL, "default", -64, now);
    }

    /** Template world key path ({@code tactical:map_<id>}). */
    public String templateKey() {
        return "map_" + id;
    }

    public boolean supportsMode(String mode) {
        return modes.contains(mode) || mode.equals("test");
    }

    // ----- edit helpers: every edit bumps the version and demotes the map to DRAFT
    private MapDefinition edited(String name, boolean enabled, Set<String> modes, Region bounds, List<SpawnPoint> atk,
                                 List<SpawnPoint> def, Map<String, BombSite> sites, List<Barrier> barriers,
                                 List<Region> construction, KitRestriction kits, String shop, int voidY, long now) {
        return new MapDefinition(id, name, author, enabled, version + 1, MapStatus.DRAFT, modes, bounds, atk, def,
                sites, barriers, construction, kits, shop, voidY, now);
    }

    public MapDefinition withName(String n, long now) {
        return edited(n, enabled, modes, bounds, attackerSpawns, defenderSpawns, sites, barriers, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withModes(Set<String> m, long now) {
        return edited(name, enabled, m, bounds, attackerSpawns, defenderSpawns, sites, barriers, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withBounds(Region b, long now) {
        return edited(name, enabled, modes, b, attackerSpawns, defenderSpawns, sites, barriers, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withSpawns(List<SpawnPoint> atk, List<SpawnPoint> def, long now) {
        return edited(name, enabled, modes, bounds, atk, def, sites, barriers, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withSites(Map<String, BombSite> s, long now) {
        return edited(name, enabled, modes, bounds, attackerSpawns, defenderSpawns, s, barriers, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withBarriers(List<Barrier> b, long now) {
        return edited(name, enabled, modes, bounds, attackerSpawns, defenderSpawns, sites, b, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withConstructionRegions(List<Region> c, long now) {
        return edited(name, enabled, modes, bounds, attackerSpawns, defenderSpawns, sites, barriers, c, kits, shop, voidY, now);
    }

    public MapDefinition withKits(KitRestriction k, long now) {
        return edited(name, enabled, modes, bounds, attackerSpawns, defenderSpawns, sites, barriers, constructionRegions, k, shop, voidY, now);
    }

    public MapDefinition withVoidY(int y, long now) {
        return edited(name, enabled, modes, bounds, attackerSpawns, defenderSpawns, sites, barriers, constructionRegions, kits, shop, y, now);
    }

    /** Enabling/disabling and status changes do NOT bump the layout version. */
    public MapDefinition withEnabled(boolean e, long now) {
        return new MapDefinition(id, name, author, e, version, status, modes, bounds, attackerSpawns, defenderSpawns,
                sites, barriers, constructionRegions, kits, shop, voidY, now);
    }

    public MapDefinition withStatus(MapStatus s, long now) {
        return new MapDefinition(id, name, author, enabled, version, s, modes, bounds, attackerSpawns, defenderSpawns,
                sites, barriers, constructionRegions, kits, shop, voidY, now);
    }
}
