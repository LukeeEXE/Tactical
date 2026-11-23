package dev.tactical.engine.geo;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Axis-aligned block region, inclusive on both corners. Always normalised so {@code min <= max}.
 * A point (x,y,z) is inside if its block lies inside the region.
 */
public record Region(BlockPos min, BlockPos max) implements Iterable<BlockPos> {

    /** Hard cap on regions iterated block-by-block (barriers, sites) to keep operations bounded. */
    public static final long MAX_ITERABLE_VOLUME = 1_000_000;

    public Region {
        BlockPos a = min, b = max;
        min = new BlockPos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        max = new BlockPos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public static Region of(BlockPos a, BlockPos b) {
        return new Region(a, b);
    }

    public boolean contains(double x, double y, double z) {
        return contains(BlockPos.of(x, y, z));
    }

    public boolean contains(BlockPos p) {
        return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    public boolean contains(Region other) {
        return contains(other.min) && contains(other.max);
    }

    public boolean intersects(Region o) {
        return min.x() <= o.max.x() && max.x() >= o.min.x() && min.y() <= o.max.y() && max.y() >= o.min.y()
                && min.z() <= o.max.z() && max.z() >= o.min.z();
    }

    public long volume() {
        return (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
    }

    public double centerX() {
        return (min.x() + max.x() + 1) / 2.0;
    }

    public double centerY() {
        return (min.y() + max.y() + 1) / 2.0;
    }

    public double centerZ() {
        return (min.z() + max.z() + 1) / 2.0;
    }

    @Override
    public Iterator<BlockPos> iterator() {
        if (volume() > MAX_ITERABLE_VOLUME) {
            throw new IllegalStateException("Region too large to iterate: " + volume() + " blocks");
        }
        return new Iterator<>() {
            int x = min.x(), y = min.y(), z = min.z();
            boolean done = false;

            public boolean hasNext() {
                return !done;
            }

            public BlockPos next() {
                if (done) throw new NoSuchElementException();
                BlockPos p = new BlockPos(x, y, z);
                if (++x > max.x()) {
                    x = min.x();
                    if (++z > max.z()) {
                        z = min.z();
                        if (++y > max.y()) done = true;
                    }
                }
                return p;
            }
        };
    }

    @Override
    public String toString() {
        return min + " -> " + max;
    }
}
