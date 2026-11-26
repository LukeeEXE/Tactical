package dev.tactical.engine.map;

import dev.tactical.engine.geo.Region;
import dev.tactical.engine.geo.SpawnPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Structural validation of a map definition (no world access). The Paper layer adds world checks on top
 * (template exists, spawns not inside blocks, sites standable, barrier reset round-trip).
 */
public final class MapValidator {

    public record Problem(boolean error, String message) {
        @Override
        public String toString() {
            return (error ? "ERROR: " : "WARNING: ") + message;
        }
    }

    private MapValidator() {
    }

    public static List<Problem> validate(MapDefinition m, Collection<String> knownKits, Collection<String> knownModes,
                                         int teamSize) {
        List<Problem> p = new ArrayList<>();
        if (m.bounds() == null) {
            p.add(err("No map boundaries set (/game map bounds). Players leaving the bounds are eliminated."));
        } else if (m.bounds().volume() < 64) {
            p.add(err("Map boundaries are implausibly small (" + m.bounds().volume() + " blocks)."));
        }
        checkSpawns(p, "attacker", m.attackerSpawns(), m, teamSize);
        checkSpawns(p, "defender", m.defenderSpawns(), m, teamSize);
        if (m.sites().isEmpty()) {
            p.add(err("No bomb sites (/game map site add <id>)."));
        } else if (m.sites().size() < 2) {
            p.add(warn("Only one bomb site; most tactical maps use two or three."));
        }
        List<BombSite> sites = List.copyOf(m.sites().values());
        for (int i = 0; i < sites.size(); i++) {
            BombSite s = sites.get(i);
            if (m.bounds() != null && !m.bounds().contains(s.region())) {
                p.add(err("Bomb site " + s.id() + " is not fully inside the map boundaries."));
            }
            if (s.region().volume() < 4) p.add(err("Bomb site " + s.id() + " is too small to plant in."));
            for (int j = i + 1; j < sites.size(); j++) {
                if (s.region().intersects(sites.get(j).region())) {
                    p.add(err("Bomb sites " + s.id() + " and " + sites.get(j).id() + " overlap."));
                }
            }
            for (SpawnPoint sp : concat(m.attackerSpawns(), m.defenderSpawns())) {
                if (s.region().contains(sp.x(), sp.y(), sp.z())) {
                    p.add(err("A spawn point is inside bomb site " + s.id() + "."));
                    break;
                }
            }
        }
        for (Barrier b : m.barriers()) {
            if (m.bounds() != null && !m.bounds().contains(b.region())) {
                p.add(err("Barrier '" + b.name() + "' is not inside the map boundaries."));
            }
            if (b.region().volume() > 20_000) p.add(err("Barrier '" + b.name() + "' is too large (max 20000 blocks)."));
        }
        for (Region r : m.constructionRegions()) {
            if (m.bounds() != null && !m.bounds().contains(r)) p.add(err("A construction region is outside the map boundaries."));
        }
        if (m.bombSpawn() == null) {
            p.add(warn("No bomb spawn set (/game map bombspawn): the bomb starts at the first attacker spawn."));
        } else if (m.bounds() != null && !m.bounds().contains(m.bombSpawn().x(), m.bombSpawn().y(), m.bombSpawn().z())) {
            p.add(err("The bomb spawn is outside the map boundaries."));
        }
        for (Pickup pk : m.pickups()) {
            if (m.bounds() != null && !m.bounds().contains(pk.x(), pk.y(), pk.z())) {
                p.add(err("Pickup '" + pk.item() + "' at " + Math.round(pk.x()) + "," + Math.round(pk.y()) + "," + Math.round(pk.z())
                        + " is outside the map boundaries."));
            }
        }
        if (m.modes().isEmpty()) p.add(err("The map supports no game modes."));
        for (String mode : m.modes()) {
            if (!knownModes.contains(mode)) p.add(warn("Unknown mode '" + mode + "'."));
        }
        for (String kit : m.kits().kits()) {
            if (!knownKits.contains(kit)) p.add(err("Kit restriction lists unknown kit '" + kit + "'."));
        }
        int selectable = m.kits().filter(List.copyOf(knownKits)).size();
        if (selectable < teamSize) {
            p.add(err("Only " + selectable + " kit(s) allowed on this map; each team needs " + teamSize + " distinct kits."));
        }
        if (m.bounds() != null && m.voidY() >= m.bounds().min().y()) {
            p.add(warn("void-y (" + m.voidY() + ") is not below the map boundaries."));
        }
        return p;
    }

    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(Problem::error);
    }

    private static void checkSpawns(List<Problem> p, String team, List<SpawnPoint> spawns, MapDefinition m, int teamSize) {
        if (spawns.isEmpty()) {
            p.add(err("No " + team + " spawn points (/game map spawn add " + team + "s)."));
            return;
        }
        if (spawns.size() < teamSize) {
            p.add(warn("Only " + spawns.size() + " " + team + " spawn point(s); players will share spawns."));
        }
        for (SpawnPoint s : spawns) {
            if (m.bounds() != null && !m.bounds().contains(s.x(), s.y(), s.z())) {
                p.add(err("A " + team + " spawn point (" + s.block() + ") is outside the map boundaries."));
            }
        }
    }

    private static List<SpawnPoint> concat(List<SpawnPoint> a, List<SpawnPoint> b) {
        List<SpawnPoint> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Problem err(String m) {
        return new Problem(true, m);
    }

    private static Problem warn(String m) {
        return new Problem(false, m);
    }
}
