package dev.tactical.engine.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackBuilderTest {

    static final Set<String> KNOWN = Set.of("stone_brick_slab", "waxed_cut_copper_slab", "cyan_glazed_terracotta", "red_glazed_terracotta");

    static List<CustomBlock> blocks(String yaml, ConfigReport r) {
        return CustomBlock.parseFile(YamlLoader.parse(yaml, "blocks/test.yml", r).orElseThrow());
    }

    static void png(Path root, String rel, int w, int h) throws Exception {
        Path p = root.resolve("assets/tactical/textures/" + rel);
        Files.createDirectories(p.getParent());
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", p.toFile());
    }

    static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    @Test
    void generatesDoubleSlabAndGlazedFacingBlocks(@TempDir Path root) throws Exception {
        png(root, "block/panel.png", 16, 16);
        png(root, "block/tile_n.png", 16, 16);
        png(root, "block/copper.png", 32, 32);
        ConfigReport r = new ConfigReport();
        List<CustomBlock> b = blocks("""
                blocks:
                  panel: { state: "minecraft:stone_brick_slab[type=double]", texture: block/panel.png }
                  copper: { state: "minecraft:waxed_cut_copper_slab[type=double]", texture: block/copper.png }
                  tile_n: { state: "minecraft:cyan_glazed_terracotta[facing=north]", texture: block/tile_n.png }
                """, r);
        assertFalse(r.hasErrors(), r.issues().toString());
        PackBuilder.Result res = new PackBuilder(97, 1, "test").build(root, b, KNOWN);
        assertFalse(res.hasErrors(), res.issues().toString());
        Map<String, String> files = unzip(res.zip());

        JsonObject slab = JsonParser.parseString(files.get("assets/minecraft/blockstates/stone_brick_slab.json")).getAsJsonObject()
                .getAsJsonObject("variants");
        assertEquals("tactical:block/panel", slab.getAsJsonObject("type=double").get("model").getAsString());
        assertEquals("minecraft:block/stone_brick_slab", slab.getAsJsonObject("type=bottom").get("model").getAsString());
        assertEquals("minecraft:block/stone_brick_slab_top", slab.getAsJsonObject("type=top").get("model").getAsString());
        JsonObject waxed = JsonParser.parseString(files.get("assets/minecraft/blockstates/waxed_cut_copper_slab.json")).getAsJsonObject()
                .getAsJsonObject("variants");
        assertEquals("minecraft:block/cut_copper_slab", waxed.getAsJsonObject("type=bottom").get("model").getAsString(),
                "waxed slabs reuse the unwaxed vanilla models");

        JsonObject glazed = JsonParser.parseString(files.get("assets/minecraft/blockstates/cyan_glazed_terracotta.json")).getAsJsonObject()
                .getAsJsonObject("variants");
        assertEquals("tactical:block/tile_n", glazed.getAsJsonObject("facing=north").get("model").getAsString());
        assertFalse(glazed.getAsJsonObject("facing=north").has("y"), "custom facing blocks are not rotated");
        assertEquals("minecraft:block/cyan_glazed_terracotta", glazed.getAsJsonObject("facing=east").get("model").getAsString());
        assertEquals(270, glazed.getAsJsonObject("facing=east").get("y").getAsInt(), "untouched facings keep vanilla rotation");

        assertTrue(files.containsKey("assets/tactical/models/block/panel.json"));
        assertTrue(files.containsKey(PackBuilder.PROTECTED.iterator().next()));
        JsonObject meta = JsonParser.parseString(files.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
        assertEquals(97, meta.getAsJsonArray("min_format").get(0).getAsInt());
    }

    @Test
    void constructionBlockIsRetexturedInfestedStone(@TempDir Path root) throws Exception {
        PackBuilder.Result res = new PackBuilder(97, 1, "t").build(root, List.of(), KNOWN);
        Map<String, String> files = unzip(res.zip());
        JsonObject model = JsonParser.parseString(files.get("assets/minecraft/models/block/infested_stone.json")).getAsJsonObject();
        assertEquals("tactical:block/construction_block", model.getAsJsonObject("textures").get("all").getAsString());
        assertTrue(files.containsKey(PackBuilder.CONSTRUCTION_TEXTURE), "placeholder texture generated");
        assertFalse(res.hasErrors(), res.issues().toString());

        png(root, "block/construction_block.png", 16, 16); // admin texture replaces the placeholder silently
        PackBuilder.Result custom = new PackBuilder(97, 1, "t").build(root, List.of(), KNOWN);
        assertNotEquals(res.sha1(), custom.sha1());
        assertTrue(custom.issues().isEmpty(), custom.issues().toString());
    }

    @Test
    void includesHorizontalAndVerticalOffsetFonts(@TempDir Path root) throws Exception {
        PackBuilder.Result res = new PackBuilder(97, 1, "t").build(root, List.of(), KNOWN);
        assertFalse(res.hasErrors(), res.issues().toString());
        Map<String, String> files = unzip(res.zip());
        assertTrue(files.containsKey("assets/space/font/default.json"));
        assertTrue(files.containsKey("assets/space/lang/en_us.json"));
        assertTrue(files.containsKey("credits/negativespacefont/NOTICE.txt"), "attribution is shipped in the pack");
        String lang = new String(files.get("assets/space/lang/en_us.json").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(JsonParser.parseString(lang).getAsJsonObject().has("space.-11"));
        JsonObject down = JsonParser.parseString(files.get("assets/tactical/font/offset_down_10.json")).getAsJsonObject();
        JsonObject bitmap = down.getAsJsonArray("providers").get(1).getAsJsonObject();
        assertEquals(-3, bitmap.get("ascent").getAsInt());
        assertEquals(16, bitmap.getAsJsonArray("chars").size());
        assertEquals("0123456789:;<=>?", bitmap.getAsJsonArray("chars").get(3).getAsString());
        assertTrue(files.containsKey("assets/tactical/font/offset_up_1.json"));
        assertTrue(files.containsKey("assets/tactical/font/offset_down_128.json"));
    }

    @Test
    void trueTypeFontsGetVerticalVariants(@TempDir Path root) throws Exception {
        Path fonts = root.resolve("fonts");
        Files.createDirectories(fonts);
        byte[] fake = new byte[64];
        fake[1] = 1; // 00 01 00 00 = TrueType signature
        Files.write(fonts.resolve("Pinch.ttf"), fake);
        Files.writeString(fonts.resolve("Pinch.yml"), "size: 7\noversample: 4\nmax-offset: 3\n");
        Files.writeString(fonts.resolve("Pinch.LICENSE.txt"), "CC BY-SA 3.0");
        Files.write(fonts.resolve("broken.ttf"), new byte[]{1, 2, 3});
        PackBuilder.Result res = new PackBuilder(97, 1, "t").build(root, List.of(), KNOWN);
        Map<String, String> files = unzip(res.zip());
        assertTrue(files.containsKey("assets/tactical/font/pinch.ttf"));
        JsonObject ttf = JsonParser.parseString(files.get("assets/tactical/font/pinch_up_2.json")).getAsJsonObject()
                .getAsJsonArray("providers").get(0).getAsJsonObject();
        assertEquals("tactical:pinch.ttf", ttf.get("file").getAsString());
        assertEquals(7.0, ttf.get("size").getAsDouble());
        assertEquals(-2.0, ttf.getAsJsonArray("shift").get(1).getAsDouble());
        assertTrue(files.containsKey("assets/tactical/font/pinch.json"));
        assertTrue(files.containsKey("assets/tactical/font/pinch_down_3.json"));
        assertFalse(files.containsKey("assets/tactical/font/pinch_down_4.json"));
        assertTrue(files.containsKey("credits/fonts/Pinch.LICENSE.txt"));
        assertTrue(files.containsKey("assets/tactical/font/pinch_x2_down_3.json"));
        assertTrue(res.issues().toString().contains("not a TrueType"), res.issues().toString());
    }

    @Test
    void hudGlyphFontsUseLayout(@TempDir Path root) throws Exception {
        PackBuilder.Result res = new PackBuilder(97, 1, "t", new HudLayout(9, 9, 9, 8, 33, 19)).build(root, List.of(), KNOWN);
        assertFalse(res.hasErrors(), res.issues().toString());
        Map<String, String> files = unzip(res.zip());
        JsonObject money = JsonParser.parseString(files.get("assets/tactical/font/hud_money.json")).getAsJsonObject();
        var providers = money.getAsJsonArray("providers");
        assertEquals(2, providers.size());
        assertEquals(-24, providers.get(0).getAsJsonObject().get("ascent").getAsInt(), "panel 2 px above the digits");
        assertEquals(-26, providers.get(1).getAsJsonObject().get("ascent").getAsInt());
        assertTrue(files.containsKey("assets/tactical/font/hud_chat.json"));
        assertTrue(files.containsKey("assets/tactical/textures/hud/bar_box_128.png"));
        assertTrue(files.containsKey("assets/tactical/font/hud_actionbar.json"));
        assertFalse(files.containsKey("assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png"));
        assertTrue(files.containsKey("assets/minecraft/textures/gui/sprites/boss_bar/white_progress.png"));
        assertTrue(files.containsKey("assets/tactical/textures/hud/plant_half.png"));
        assertTrue(files.containsKey("assets/minecraft/particles/dust.json"));
        assertTrue(files.containsKey("assets/minecraft/post_effect/entity_outline.json"));
        JsonObject bar = JsonParser.parseString(files.get("assets/tactical/font/hud_bossbar.json")).getAsJsonObject();
        boolean timeRow = false;
        for (var e : bar.getAsJsonArray("providers")) {
            var o = e.getAsJsonObject();
            if (o.get("file").getAsString().endsWith("time_fill_5.png")) timeRow = o.get("ascent").getAsInt() == -1 && o.get("height").getAsInt() == 1;
        }
        assertTrue(timeRow, "timer line on row 8");
    }

    @Test
    void ttfMetricsMeasurePinch() throws Exception {
        byte[] font = Files.readAllBytes(Path.of("../tactical-paper/src/main/resources/defaults/resourcepack/fonts/pinch.ttf"));
        TtfMetrics m = TtfMetrics.parse(font);
        assertEquals(6.0, m.width("A", 7), 0.01);
        assertEquals(2 * 5 + 2 + 2 * 5, m.width("00:00", 7), 0.01);
        assertTrue(m.has('Z'));
    }

    @Test
    void deterministicOutput(@TempDir Path root) throws Exception {
        png(root, "block/panel.png", 16, 16);
        ConfigReport r = new ConfigReport();
        List<CustomBlock> b = blocks("blocks:\n  panel: { state: \"minecraft:stone_brick_slab[type=double]\", texture: block/panel.png }\n", r);
        String a = new PackBuilder(97, 1, "t").build(root, b, KNOWN).sha1();
        Thread.sleep(5);
        assertEquals(a, new PackBuilder(97, 1, "t").build(root, b, KNOWN).sha1(), "same inputs -> same SHA-1, no re-download");
    }

    @Test
    void reportsConflictsMissingFilesAndProtectedPaths(@TempDir Path root) throws Exception {
        png(root, "block/a.png", 16, 16);
        png(root, "block/wide.png", 32, 16);
        Path hud = root.resolve("assets/minecraft/textures/gui/sprites/hud/food_full_hunger.png");
        Files.createDirectories(hud.getParent());
        Files.write(hud, PackBuilder.transparentPng(9, 9));
        Path badJson = root.resolve("assets/minecraft/models/block/stone.json");
        Files.createDirectories(badJson.getParent());
        Files.writeString(badJson, "{ not json");
        ConfigReport r = new ConfigReport();
        List<CustomBlock> b = blocks("""
                blocks:
                  a: { state: "minecraft:stone_brick_slab[type=double]", texture: block/a.png }
                  b: { state: "minecraft:stone_brick_slab[type=double]", texture: block/a.png }
                  c: { state: "minecraft:red_glazed_terracotta[facing=west]", texture: block/missing.png }
                  d: { state: "minecraft:nonexistent_slab[type=double]", texture: block/a.png }
                  e: { state: "minecraft:cyan_glazed_terracotta[facing=up]", texture: block/a.png }
                  f: { state: "minecraft:stone_brick_slab[type=bottom]", texture: block/a.png }
                """, r);
        assertTrue(r.hasErrors(), "invalid facing / slab type are config errors");
        PackBuilder.Result res = new PackBuilder(97, 1, "t").build(root, b, KNOWN);
        String all = res.issues().toString();
        assertTrue(all.contains("already used by 'a'"), all);
        assertTrue(all.contains("texture not found"), all);
        assertTrue(all.contains("does not exist on this server version"), all);
        assertTrue(all.contains("hunger bar"), all);
        assertTrue(all.contains("invalid JSON"), all);
        assertTrue(all.contains("must be square"), all);
    }
}
