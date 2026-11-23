package dev.tactical.engine.pack;

import dev.tactical.engine.config.ConfigNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A custom block that re-uses a stable vanilla block state (architecture §5.11.1):
 * <ul>
 *   <li>{@code minecraft:<x>_slab[type=double]}: a new full block; single slabs and the real full block keep their look.</li>
 *   <li>{@code minecraft:<color>_glazed_terracotta[facing=<dir>]}: one independent block per facing (no rotation).</li>
 *   <li>{@code minecraft:<color>_glazed_terracotta} (no facing): replaces the texture but keeps vanilla rotation.</li>
 * </ul>
 *
 * @param texture texture path relative to {@code resourcepack/assets/tactical/textures/} (e.g. {@code block/panel.png}), or null
 * @param model   model path relative to {@code resourcepack/assets/tactical/models/} (e.g. {@code block/panel.json}), or null
 */
public record CustomBlock(String id, String name, String blockId, Kind kind, String facing, String texture, String model) {

    public enum Kind { DOUBLE_SLAB, GLAZED_FACING, GLAZED_ROTATING }

    public static final List<String> FACINGS = List.of("north", "south", "east", "west");
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern STATE = Pattern.compile("(?:minecraft:)?([a-z0-9_]+)(?:\\[([a-z_]+)=([a-z_]+)])?");

    /** The vanilla state key this block occupies, used to detect two blocks claiming the same state. */
    public String stateKey() {
        return switch (kind) {
            case DOUBLE_SLAB -> blockId + "[type=double]";
            case GLAZED_FACING -> blockId + "[facing=" + facing + "]";
            case GLAZED_ROTATING -> blockId + "[*]";
        };
    }

    public String modelRef() {
        return "tactical:block/" + id;
    }

    /** Parses blocks/*.yml ({@code blocks: {id: {...}}}). */
    public static List<CustomBlock> parseFile(ConfigNode root) {
        root.warnUnknownKeys(List.of("schema-version", "blocks"));
        List<CustomBlock> out = new ArrayList<>();
        for (Map.Entry<String, ConfigNode> e : root.child("blocks").children().entrySet()) {
            parse(e.getKey(), e.getValue()).ifPresent(out::add);
        }
        return out;
    }

    static Optional<CustomBlock> parse(String id, ConfigNode n) {
        int before = n.report().errors().size();
        n.warnUnknownKeys(List.of("state", "texture", "model", "name"));
        if (!ID.matcher(id).matches()) n.error("block id must be 1-40 chars of a-z, 0-9, _");
        String state = n.requireString("state");
        String texture = n.string("texture", null);
        String model = n.string("model", null);
        String name = n.string("name", id);
        if (texture == null && model == null) n.error("needs a 'texture' or a 'model'");
        if (texture != null && model != null) n.error("use either 'texture' or 'model', not both");
        if (texture != null && !texture.endsWith(".png")) n.child("texture").error("must be a .png path, e.g. block/" + id + ".png");
        if (model != null && !model.endsWith(".json")) n.child("model").error("must be a .json path, e.g. block/" + id + ".json");
        Kind kind = null;
        String blockId = null;
        String facing = null;
        if (state != null) {
            Matcher m = STATE.matcher(state.trim().toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                n.child("state").error("must look like minecraft:stone_brick_slab[type=double] or minecraft:cyan_glazed_terracotta[facing=north]");
            } else {
                blockId = m.group(1);
                String key = m.group(2), value = m.group(3);
                if (blockId.endsWith("_slab")) {
                    if (!"type".equals(key) || !"double".equals(value)) n.child("state").error("slabs can only use [type=double]");
                    else if (!SlabModels.supported(blockId)) n.child("state").error(blockId + " is not supported (" + SlabModels.reason(blockId) + ")");
                    else kind = Kind.DOUBLE_SLAB;
                } else if (blockId.endsWith("_glazed_terracotta")) {
                    if (key == null) {
                        kind = Kind.GLAZED_ROTATING;
                    } else if (!"facing".equals(key) || !FACINGS.contains(value)) {
                        n.child("state").error("glazed terracotta needs [facing=north|south|east|west] or no property");
                    } else {
                        kind = Kind.GLAZED_FACING;
                        facing = value;
                    }
                } else {
                    n.child("state").error("only double slabs and glazed terracotta are supported as custom blocks");
                }
            }
        }
        if (n.report().errors().size() > before) return Optional.empty();
        return Optional.of(new CustomBlock(id, name, blockId, kind, facing, texture, model));
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", stateKey());
        m.put("texture", texture);
        m.put("model", model);
        return m;
    }
}
