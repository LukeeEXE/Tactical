package dev.tactical.paper.map;

import dev.tactical.engine.geo.BlockPos;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the ORIGINAL state of every block changed by game mechanics (barrier removal, construction blocks,
 * round-start changes) so a round reset restores the map exactly, without reloading the world. Only the first
 * change of a position stores its original; restore applies originals in reverse order of first change.
 */
public final class BlockJournal {

    /** Hard limit so a bug can never make a round reset unbounded. */
    public static final int MAX_ENTRIES = 200_000;

    private final World world;
    private final Map<BlockPos, BlockData> originals = new LinkedHashMap<>();

    public BlockJournal(World world) {
        this.world = world;
    }

    /** Changes a block and journals its original state. Returns false if the journal is full. */
    public boolean set(BlockPos pos, BlockData data) {
        Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!originals.containsKey(pos)) {
            if (originals.size() >= MAX_ENTRIES) return false;
            originals.put(pos, b.getBlockData().clone());
        }
        b.setBlockData(data, false);
        return true;
    }

    /** Restores every journaled block. Returns how many blocks were restored. */
    public int restoreAll() {
        List<Map.Entry<BlockPos, BlockData>> entries = new ArrayList<>(originals.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            BlockPos p = entries.get(i).getKey();
            world.getBlockAt(p.x(), p.y(), p.z()).setBlockData(entries.get(i).getValue(), false);
        }
        int n = entries.size();
        originals.clear();
        return n;
    }

    public boolean isChanged(BlockPos pos) {
        return originals.containsKey(pos);
    }

    public int size() {
        return originals.size();
    }

    public World world() {
        return world;
    }
}
