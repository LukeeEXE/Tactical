package dev.tactical.engine.geo;

/** Integer block coordinates. */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(double x, double y, double z) {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
