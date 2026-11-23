package dev.tactical.engine.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Picks vote candidates from a pool without order bias: candidates are drawn uniformly at random from the
 * valid, enabled maps, preferring maps not in the recent history; GUI order is shuffled separately per viewer.
 */
public final class MapSelector {

    private MapSelector() {
    }

    /** Maps that may be offered: enabled in the pool, existing, enabled globally, published, supporting the mode. */
    public static List<String> eligible(MapPool pool, Map<String, MapDefinition> maps) {
        List<String> out = new ArrayList<>();
        if (!pool.enabled()) return out;
        for (Map.Entry<String, Boolean> e : pool.maps().entrySet()) {
            MapDefinition m = maps.get(e.getKey());
            if (e.getValue() && m != null && m.enabled() && m.status() == MapStatus.PUBLISHED && m.supportsMode(pool.mode())) {
                out.add(m.id());
            }
        }
        out.sort(null); // canonical order before random sampling, so config order can never bias selection
        return out;
    }

    /** Up to {@code count} candidates; maps in {@code recent} are only used if there are not enough others. */
    public static List<String> candidates(List<String> eligible, Collection<String> recent, int count, RandomGenerator random) {
        List<String> fresh = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String id : eligible) (recent.contains(id) ? stale : fresh).add(id);
        shuffle(fresh, random);
        shuffle(stale, random);
        List<String> out = new ArrayList<>();
        for (String id : fresh) if (out.size() < count) out.add(id);
        for (String id : stale) if (out.size() < count) out.add(id);
        return out;
    }

    /** Uniform random winner among the tied maps. */
    public static String breakTie(List<String> tied, RandomGenerator random) {
        return tied.get(random.nextInt(tied.size()));
    }

    private static <T> void shuffle(List<T> list, RandomGenerator r) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }
}
