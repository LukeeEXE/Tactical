package dev.tactical.engine.kit;

import dev.tactical.engine.team.TeamId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One kit-selection round (match start, halftime or sudden death) for both teams.
 *
 * <p>Rules:
 * <ul>
 *   <li>Each player may only pick from their own option set.</li>
 *   <li>Teammates may not lock the same kit; opponents may.</li>
 *   <li>A lock is only accepted if every still-unlocked teammate can still be given one of their options
 *       (checked with bipartite matching), so the selection can never deadlock.</li>
 *   <li>{@link #autoFill} completes the selection when the timer runs out, honouring each player's
 *       hovered kit, then their listed preference order, while keeping the assignment valid.</li>
 * </ul>
 * If a team's options cannot be made duplicate-free at all (e.g. a test match with fewer kits than
 * players), duplicates are allowed for that team and {@link #duplicatesAllowed(TeamId)} reports it.
 */
public final class KitSelection {

    private final KitSelectionPhase phase;
    private final Map<UUID, TeamId> teams;
    private final Map<UUID, List<String>> options;   // ordered by preference (for auto fill)
    private final Map<UUID, String> hovered = new HashMap<>();
    private final Map<UUID, String> locked = new LinkedHashMap<>();
    private final Set<TeamId> duplicatesAllowed = new HashSet<>();
    private boolean closed;

    /**
     * @param teams   every participant and their original team
     * @param options each participant's allowed kits in preference order (used by auto fill)
     */
    public KitSelection(KitSelectionPhase phase, Map<UUID, TeamId> teams, Map<UUID, List<String>> options) {
        this.phase = Objects.requireNonNull(phase);
        this.teams = Map.copyOf(teams);
        Map<UUID, List<String>> copy = new HashMap<>();
        for (UUID id : teams.keySet()) {
            List<String> o = options.get(id);
            if (o == null || o.isEmpty()) {
                throw new IllegalArgumentException("Participant " + id + " has no kit options");
            }
            copy.put(id, List.copyOf(new LinkedHashSet<>(o)));
        }
        this.options = Collections.unmodifiableMap(copy);
        for (TeamId team : TeamId.values()) {
            if (!hasPerfectMatching(team, Map.of())) {
                duplicatesAllowed.add(team);
            }
        }
        // Players with exactly one option and no conflict are locked immediately (e.g. same kit both halves).
        for (UUID id : sortedIds()) {
            List<String> o = this.options.get(id);
            if (o.size() == 1) {
                lock(id, o.getFirst());
            }
        }
    }

    /** Builds the sudden-death selection: each player chooses between their two half kits. */
    public static KitSelection suddenDeath(Map<UUID, TeamId> teams, Map<UUID, String> firstHalf, Map<UUID, String> secondHalf) {
        Map<UUID, List<String>> options = new HashMap<>();
        for (UUID id : teams.keySet()) {
            List<String> o = new ArrayList<>(2);
            String second = secondHalf.get(id);
            String first = firstHalf.get(id);
            if (second != null) o.add(second);       // preference: second-half kit first
            if (first != null && !first.equals(second)) o.add(first);
            options.put(id, o);
        }
        return new KitSelection(KitSelectionPhase.SUDDEN_DEATH, teams, options);
    }

    /** Builds a free selection where every player may pick any of the given kits. */
    public static KitSelection open(KitSelectionPhase phase, Map<UUID, TeamId> teams, List<String> kits) {
        Map<UUID, List<String>> options = new HashMap<>();
        for (UUID id : teams.keySet()) {
            options.put(id, kits);
        }
        return new KitSelection(phase, teams, options);
    }

    public KitSelectionPhase phase() {
        return phase;
    }

    public List<String> optionsOf(UUID player) {
        return options.getOrDefault(player, List.of());
    }

    public Optional<String> hoveredBy(UUID player) {
        return Optional.ofNullable(hovered.get(player));
    }

    public Optional<String> lockedBy(UUID player) {
        return Optional.ofNullable(locked.get(player));
    }

    public Map<UUID, String> locks() {
        return Collections.unmodifiableMap(locked);
    }

    public boolean isLocked(UUID player) {
        return locked.containsKey(player);
    }

    public boolean duplicatesAllowed(TeamId team) {
        return duplicatesAllowed.contains(team);
    }

    public boolean allLocked() {
        return locked.size() == teams.size();
    }

    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Hover / preview a kit (visible to everyone, does not reserve it). */
    public KitSelectionResult select(UUID player, String kit) {
        KitSelectionResult base = basicChecks(player, kit);
        if (base != KitSelectionResult.OK) return base;
        hovered.put(player, kit);
        return KitSelectionResult.OK;
    }

    /** Whether {@code player} could lock {@code kit} right now; used to grey out GUI buttons. */
    public KitSelectionResult canLock(UUID player, String kit) {
        KitSelectionResult base = basicChecks(player, kit);
        if (base != KitSelectionResult.OK) return base;
        TeamId team = teams.get(player);
        if (duplicatesAllowed.contains(team)) return KitSelectionResult.OK;
        for (Map.Entry<UUID, String> e : locked.entrySet()) {
            if (teams.get(e.getKey()) == team && e.getValue().equals(kit)) {
                return KitSelectionResult.TAKEN_BY_TEAMMATE;
            }
        }
        if (!hasPerfectMatching(team, Map.of(player, kit))) {
            return KitSelectionResult.WOULD_STRAND_TEAMMATE;
        }
        return KitSelectionResult.OK;
    }

    public KitSelectionResult lock(UUID player, String kit) {
        KitSelectionResult result = canLock(player, kit);
        if (result == KitSelectionResult.OK) {
            locked.put(player, kit);
            hovered.put(player, kit);
        }
        return result;
    }

    /**
     * Locks every remaining player into a valid kit. Preference: hovered kit, then the player's option order.
     * Players are processed in a deterministic order (hovering players first, then by UUID).
     *
     * @return the kits that were auto-assigned (player → kit)
     */
    public Map<UUID, String> autoFill() {
        Map<UUID, String> assigned = new LinkedHashMap<>();
        List<UUID> pending = sortedIds().stream().filter(id -> !locked.containsKey(id))
                .sorted(Comparator.comparing((UUID id) -> !hovered.containsKey(id)).thenComparing(UUID::toString))
                .toList();
        for (UUID id : pending) {
            List<String> order = new ArrayList<>();
            String h = hovered.get(id);
            if (h != null) order.add(h);
            for (String o : options.get(id)) if (!order.contains(o)) order.add(o);
            boolean done = false;
            for (String kit : order) {
                if (lock(id, kit) == KitSelectionResult.OK) {
                    assigned.put(id, kit);
                    done = true;
                    break;
                }
            }
            if (!done) {
                // Cannot happen while the matching invariant holds; fall back to duplicates rather than stall.
                duplicatesAllowed.add(teams.get(id));
                String kit = order.getFirst();
                locked.put(id, kit);
                assigned.put(id, kit);
            }
        }
        return assigned;
    }

    private KitSelectionResult basicChecks(UUID player, String kit) {
        if (closed) return KitSelectionResult.SELECTION_CLOSED;
        if (!teams.containsKey(player)) return KitSelectionResult.NOT_A_PARTICIPANT;
        if (locked.containsKey(player)) return KitSelectionResult.ALREADY_LOCKED;
        if (!options.get(player).contains(kit)) return KitSelectionResult.KIT_NOT_AVAILABLE;
        return KitSelectionResult.OK;
    }

    private List<UUID> sortedIds() {
        return teams.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    /**
     * True if every unlocked member of {@code team} (excluding those in {@code extraLocks}) can be assigned a
     * distinct kit from their options that no locked teammate (including {@code extraLocks}) holds.
     * Kuhn's augmenting-path algorithm; teams have at most 5 players so this is trivially cheap.
     */
    private boolean hasPerfectMatching(TeamId team, Map<UUID, String> extraLocks) {
        Set<String> taken = new HashSet<>();
        for (Map.Entry<UUID, String> e : locked.entrySet()) {
            if (teams.get(e.getKey()) == team) taken.add(e.getValue());
        }
        for (Map.Entry<UUID, String> e : extraLocks.entrySet()) {
            if (taken.contains(e.getValue())) return false;
            taken.add(e.getValue());
        }
        List<UUID> free = new ArrayList<>();
        for (UUID id : sortedIds()) {
            if (teams.get(id) == team && !locked.containsKey(id) && !extraLocks.containsKey(id)) free.add(id);
        }
        Map<String, UUID> owner = new HashMap<>();
        for (UUID id : free) {
            if (!augment(id, taken, owner, new HashSet<>())) return false;
        }
        return true;
    }

    private boolean augment(UUID player, Set<String> taken, Map<String, UUID> owner, Set<String> visited) {
        for (String kit : options.get(player)) {
            if (taken.contains(kit) || !visited.add(kit)) continue;
            UUID current = owner.get(kit);
            if (current == null || augment(current, taken, owner, visited)) {
                owner.put(kit, player);
                return true;
            }
        }
        return false;
    }
}
