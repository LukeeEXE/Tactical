package dev.tactical.engine.map;

import dev.tactical.engine.geo.Region;

/**
 * A region of blocks (typically glass spawn walls) that is REMOVED when the buy phase ends and RESTORED when the
 * round resets. The blocks themselves are built in the map template; the region only says which ones to remove.
 *
 * @param onlyMaterials if non-empty, only blocks of these materials (e.g. minecraft:glass) inside the region are removed
 */
public record Barrier(String name, Region region, java.util.List<String> onlyMaterials) {
}
