package dev.tactical.paper.match;

import dev.tactical.engine.team.TeamId;

import java.util.UUID;

/**
 * A participant as the Paper layer knows it. {@code virtual} members are engine-only test players with no
 * Minecraft client (used for admin test matches and automated tests); they never count for statistics.
 */
public record MatchMember(UUID uuid, String name, TeamId team, boolean virtual) {
}
