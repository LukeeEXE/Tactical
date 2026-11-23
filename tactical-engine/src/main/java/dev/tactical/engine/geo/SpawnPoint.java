package dev.tactical.engine.geo;

/** A precise position with facing, used for spawns and spectator anchors. */
public record SpawnPoint(double x, double y, double z, float yaw, float pitch) {

    public BlockPos block() {
        return BlockPos.of(x, y, z);
    }
}
