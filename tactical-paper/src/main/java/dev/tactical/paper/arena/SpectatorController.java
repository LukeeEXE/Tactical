package dev.tactical.paper.arena;

import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.match.Participant;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dead participants watch alive TEAMMATES in first person (vanilla spectator camera). They can never pick an
 * enemy or roam freely: without a teammate to watch they are held at a fixed anchor until the round ends.
 * Switching teammates: {@link #cycle}; which client inputs trigger it is decided after the client test (Phase 4).
 */
public final class SpectatorController {

    private final GameMatch match;
    private final MatchFlow flow;
    private final Map<UUID, UUID> watching = new HashMap<>();
    private final Map<UUID, Location> anchors = new HashMap<>();

    SpectatorController(GameMatch match) {
        this.match = match;
        this.flow = match.flow();
    }

    /** Puts a freshly eliminated real player into team spectating. */
    void onEliminated(Player p, Location anchor) {
        p.setGameMode(GameMode.SPECTATOR);
        anchors.put(p.getUniqueId(), anchor.clone());
        retarget(p, 0);
        // Anyone who was watching this player moves to the next teammate.
        for (Map.Entry<UUID, UUID> e : List.copyOf(watching.entrySet())) {
            if (e.getValue().equals(p.getUniqueId())) {
                Player viewer = Bukkit.getPlayer(e.getKey());
                if (viewer != null) retarget(viewer, 0);
            }
        }
    }

    /** Called at round start: everyone alive again. */
    void reset() {
        watching.clear();
        anchors.clear();
    }

    boolean isSpectating(UUID id) {
        return anchors.containsKey(id);
    }

    Optional<UUID> target(UUID viewer) {
        return Optional.ofNullable(watching.get(viewer));
    }

    Optional<Location> anchor(UUID viewer) {
        return Optional.ofNullable(anchors.get(viewer));
    }

    /** @param direction +1 next teammate, -1 previous, 0 first available */
    void cycle(Player viewer, int direction) {
        retarget(viewer, direction);
    }

    /** Whether a viewer may spectate a given entity (alive teammate only). */
    boolean mayWatch(UUID viewer, UUID target) {
        MatchMember v = match.member(viewer).orElse(null);
        MatchMember t = match.member(target).orElse(null);
        return v != null && t != null && v.team() == t.team() && flow.participant(target).map(Participant::active).orElse(false);
    }

    private void retarget(Player viewer, int direction) {
        List<Player> mates = aliveTeammates(viewer.getUniqueId());
        if (mates.isEmpty()) {
            watching.remove(viewer.getUniqueId());
            viewer.setSpectatorTarget(null);
            Location a = anchors.get(viewer.getUniqueId());
            if (a != null) viewer.teleport(a);
            return;
        }
        UUID current = watching.get(viewer.getUniqueId());
        int idx = 0;
        if (current != null && direction != 0) {
            for (int i = 0; i < mates.size(); i++) {
                if (mates.get(i).getUniqueId().equals(current)) {
                    idx = Math.floorMod(i + direction, mates.size());
                    break;
                }
            }
        }
        Player target = mates.get(idx);
        watching.put(viewer.getUniqueId(), target.getUniqueId());
        if (!viewer.getWorld().equals(target.getWorld())) viewer.teleport(target.getLocation());
        viewer.setSpectatorTarget(target);
    }

    private List<Player> aliveTeammates(UUID viewer) {
        MatchMember v = match.member(viewer).orElse(null);
        List<Player> out = new ArrayList<>();
        if (v == null) return out;
        for (MatchMember m : match.members()) {
            if (m.virtual() || m.team() != v.team() || m.uuid().equals(viewer)) continue;
            if (!flow.participant(m.uuid()).map(Participant::active).orElse(false)) continue;
            Player p = Bukkit.getPlayer(m.uuid());
            if (p != null) out.add(p);
        }
        out.sort(Comparator.comparing(Player::getName));
        return out;
    }
}
