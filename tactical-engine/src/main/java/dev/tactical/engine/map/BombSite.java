package dev.tactical.engine.map;

import dev.tactical.engine.geo.Region;

public record BombSite(String id, String displayName, Region region) {
}
