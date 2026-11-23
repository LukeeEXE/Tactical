package dev.tactical.engine.pack;

import java.util.Set;

/**
 * Vanilla model names used by slab blockstates, so a generated blockstate can replace ONLY the
 * {@code type=double} variant and keep bottom/top on their vanilla models.
 * Rule: bottom = {@code block/<slab>}, top = {@code block/<slab>_top}; waxed copper slabs reuse the unwaxed models.
 * Slabs whose vanilla models do not follow the rule are refused rather than risk breaking their look.
 */
public final class SlabModels {

    /** Slabs with irregular vanilla model names (verified exceptions are refused). */
    private static final Set<String> UNSUPPORTED = Set.of("petrified_oak_slab");

    private SlabModels() {
    }

    public static boolean supported(String slab) {
        return slab.endsWith("_slab") && !UNSUPPORTED.contains(slab);
    }

    public static String reason(String slab) {
        return UNSUPPORTED.contains(slab) ? "its vanilla models are irregular" : "not a slab";
    }

    public static String modelBase(String slab) {
        return slab.startsWith("waxed_") ? slab.substring("waxed_".length()) : slab;
    }

    public static String bottom(String slab) {
        return "minecraft:block/" + modelBase(slab);
    }

    public static String top(String slab) {
        return "minecraft:block/" + modelBase(slab) + "_top";
    }
}
