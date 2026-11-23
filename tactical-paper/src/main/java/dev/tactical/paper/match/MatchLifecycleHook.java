package dev.tactical.paper.match;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Lets other subsystems (maps/arenas now; voice, HUD, tablist later) take part in a match's lifecycle without
 * MatchService depending on them. All methods run on the main thread.
 */
public interface MatchLifecycleHook {

    /** Rejects a request before anything is reserved (e.g. unknown or unpublished map). */
    default Optional<String> validate(MatchService.MatchRequest request) {
        return Optional.empty();
    }

    /** Restricts the kit pool for this match (e.g. map kit restrictions). */
    default List<String> kits(MatchService.MatchRequest request, List<String> pool) {
        return pool;
    }

    /** Extra MATCH_HEADER fields (e.g. map id and layout version). */
    default Map<String, Object> header(MatchService.MatchRequest request) {
        return Map.of();
    }

    /** Prepares the match (e.g. loads the map instance). The match only starts once every future completes. */
    default CompletableFuture<Void> prepare(GameMatch match, MatchService.MatchRequest request) {
        return CompletableFuture.completedFuture(null);
    }

    /** Releases everything the hook created for the match. Must not throw. */
    default void teardown(GameMatch match) {
    }
}
