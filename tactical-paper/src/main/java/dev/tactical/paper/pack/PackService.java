package dev.tactical.paper.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.tactical.engine.config.ConfigIssue;
import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.engine.pack.CustomBlock;
import dev.tactical.engine.pack.PackBuilder;
import dev.tactical.engine.pack.PackIssue;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.core.Tasks;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Builds, hosts and distributes the server resource pack, and owns the custom-block registry
 * ({@code blocks/*.yml}) plus the exact-state placement items of the {@code /game blocks} palette.
 */
public final class PackService implements Service, Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("tactical-resource-pack".getBytes(StandardCharsets.UTF_8));

    private final Plugin plugin;
    private final ConfigService config;
    private final Tasks tasks;
    private final Logger log;
    private final NamespacedKey blockKey;
    private volatile PackBuilder.Result current;
    private volatile List<PackIssue> lastIssues = List.of();
    private volatile List<ConfigIssue> lastBlockIssues = List.of();
    private volatile Map<String, CustomBlock> blocks = Map.of();
    private HttpServer http;
    private int formatMajor = 97;
    private int formatMinor = 1;
    private Set<String> knownBlocks = Set.of();

    public PackService(Plugin plugin, ConfigService config, Tasks tasks, Logger log) {
        this.plugin = plugin;
        this.config = config;
        this.tasks = tasks;
        this.log = log;
        this.blockKey = new NamespacedKey(plugin, "custom_block");
    }

    @Override
    public void enable() throws IOException {
        Path root = sourceRoot();
        Files.createDirectories(root.resolve("assets/tactical/textures/block"));
        Files.createDirectories(root.resolve("assets/tactical/models/block"));
        Files.createDirectories(root.resolve("assets/minecraft/textures/block"));
        Files.createDirectories(root.resolve("overrides"));
        copyDefault("resourcepack/README.txt");
        copyDefault("blocks/example.yml");
        Files.createDirectories(root.resolve("fonts"));
        for (String f : List.of("pinch.ttf", "pinch.yml", "pinch.LICENSE.txt")) copyDefault("resourcepack/fonts/" + f);
        detectPackFormat();
        Set<String> known = new HashSet<>();
        for (var b : Registry.BLOCK) {
            NamespacedKey k = b.getKey();
            if (k.getNamespace().equals("minecraft")) known.add(k.getKey());
        }
        knownBlocks = Set.copyOf(known);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (config.general().packEnabled() && isBuiltin()) startHttp();
        rebuild();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (http != null) http.stop(0);
    }

    public Path sourceRoot() {
        return config.root().resolve("resourcepack");
    }

    // ------------------------------------------------------------------ build

    /** Reloads blocks/*.yml and rebuilds the pack off the main thread; sends it to everyone if it changed. */
    public CompletableFuture<PackBuilder.Result> rebuild() {
        ConfigReport report = new ConfigReport();
        Map<String, CustomBlock> parsed = new LinkedHashMap<>();
        Path dir = config.root().resolve("blocks");
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                YamlLoader.load(f, "blocks/" + f.getFileName(), report).ifPresent(node -> {
                    for (CustomBlock b : CustomBlock.parseFile(node)) {
                        if (parsed.putIfAbsent(b.id(), b) != null) {
                            report.error("blocks/" + f.getFileName(), 0, "blocks." + b.id(), "duplicate block id (also defined in another file)");
                        }
                    }
                });
            }
        } catch (IOException e) {
            report.error("blocks", 0, "", e.getMessage());
        }
        lastBlockIssues = report.issues();
        report.issues().forEach(i -> log.warning(i.toString()));
        blocks = Map.copyOf(parsed);
        List<CustomBlock> list = new ArrayList<>(parsed.values());
        String old = current == null ? null : current.sha1();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new PackBuilder(formatMajor, formatMinor, "Tactical server pack", config.hud().layout()).build(sourceRoot(), list, knownBlocks);
            } catch (IOException e) {
                throw new RuntimeException("Pack build failed: " + e.getMessage(), e);
            }
        }).thenApplyAsync(result -> {
            lastIssues = result.issues();
            for (PackIssue i : result.issues()) {
                if (i.error()) log.severe("[pack] " + i);
                else log.warning("[pack] " + i);
            }
            current = result;
            writeZip(result);
            exportArt(result);
            log.info("[pack] built " + result.stats().get("files") + " files, " + result.zip().length / 1024 + " KiB, sha1 "
                    + result.sha1().substring(0, 12) + (result.hasErrors() ? " (WITH ERRORS, see above)" : ""));
            if (config.general().packEnabled() && !isBuiltin()) {
                checkExternal(result);
            } else if (!result.sha1().equals(old) && config.general().packEnabled()) {
                for (Player p : Bukkit.getOnlinePlayers()) send(p);
            }
            return result;
        }, tasks.main());
    }

    public boolean isBuiltin() {
        return config.general().packHosting().equals("builtin");
    }

    /** The built pack as a file, for external hosting (and backups). */
    public Path zipFile() {
        return sourceRoot().resolve("build/tactical-pack.zip");
    }

    private void writeZip(PackBuilder.Result r) {
        try {
            Path f = zipFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("tactical-pack.zip.tmp");
            Files.write(tmp, r.zip());
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(f.resolveSibling("tactical-pack.sha1"), r.sha1() + "\n");
        } catch (IOException e) {
            log.warning("[pack] could not write " + zipFile() + ": " + e.getMessage());
        }
    }

    /** Generated rectangle pieces (plain colour, tinted in code): not worth editing, so not exported. */
    private static final java.util.regex.Pattern RECT_PIECE =
            java.util.regex.Pattern.compile(".*/(bar_strip|bar_panel|bar_score|action_panel)_\\d+\\.png");

    /**
     * Copies the art the pack currently uses (HUD and chat icons, construction block) to
     * {@code resourcepack/generated/}, mirroring pack paths, so admins can see and edit it. That folder is rewritten
     * on every build and never read: to replace a texture, copy it to the same path under {@code resourcepack/}.
     */
    private void exportArt(PackBuilder.Result r) {
        Path out = sourceRoot().resolve("generated");
        try {
            if (Files.exists(out)) {
                try (Stream<Path> walk = Files.walk(out)) {
                    for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
                }
            }
            int n = 0;
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(r.zip()))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    String name = e.getName();
                    boolean art = (name.startsWith("assets/tactical/textures/hud/") || name.equals(PackBuilder.CONSTRUCTION_TEXTURE))
                            && name.endsWith(".png") && !RECT_PIECE.matcher(name).matches();
                    if (!art) continue;
                    Path f = out.resolve(name);
                    Files.createDirectories(f.getParent());
                    Files.write(f, zip.readAllBytes());
                    n++;
                }
            }
            Files.writeString(out.resolve("README.txt"), """
                    These are the textures the resource pack currently uses (generated art, or your replacement if you
                    made one). This folder is rewritten on every /game pack rebuild and is NOT read by the plugin.

                    To change a texture: copy it to the SAME path under plugins/Tactical/resourcepack/ (i.e. drop the
                    "generated/" part), edit it there, keep the same size, then run /game pack rebuild.
                      generated/assets/tactical/textures/hud/chat_kill.png
                      -> resourcepack/assets/tactical/textures/hud/chat_kill.png

                    The plain backgrounds (team strip, timer panel, score boxes, hint box) are drawn from coloured
                    pieces and tinted in code, so they are not listed here. Ask for a texture option if you want to
                    replace them with art.
                    """);
            log.fine("[pack] exported " + n + " textures to " + out);
        } catch (IOException e) {
            log.warning("[pack] could not export textures to " + out + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ external hosting

    /** SHA-1 of the file actually downloadable at external-url (what clients will receive), or null if unknown. */
    private volatile String externalSha1;
    private volatile String externalStatus = "not checked";

    public String externalStatus() {
        return externalStatus;
    }

    /**
     * Downloads the uploaded pack once (off the main thread) and compares it with the freshly built one. Players are
     * sent the hash of what is really online, so an outdated upload still works (old content) instead of failing.
     */
    public CompletableFuture<Void> checkExternal(PackBuilder.Result built) {
        String url = config.general().packExternalUrl();
        if (url == null || url.isBlank()) {
            externalStatus = "external-url is empty: upload " + zipFile() + " and set resource-pack.external-url";
            log.warning("[pack] " + externalStatus);
            return CompletableFuture.completedFuture(null);
        }
        String before = externalSha1;
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(java.time.Duration.ofSeconds(10)).build();
                java.net.http.HttpResponse<byte[]> resp = client.send(java.net.http.HttpRequest.newBuilder(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(60)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) return "HTTP " + resp.statusCode();
                byte[] body = resp.body();
                if (body.length < 4 || body[0] != 'P' || body[1] != 'K') return "NOT_ZIP";
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(body));
            } catch (Exception e) {
                return "ERROR " + e.getMessage();
            }
        }).thenAcceptAsync(res -> {
            if (res.startsWith("HTTP") || res.startsWith("ERROR") || res.equals("NOT_ZIP")) {
                externalStatus = res.equals("NOT_ZIP") ? "external-url does not return a .zip file (is it a direct download link?)"
                        : "cannot download external-url (" + res + ")";
                log.warning("[pack] " + externalStatus);
                return;
            }
            externalSha1 = res;
            if (res.equals(built.sha1())) {
                externalStatus = "up to date";
                log.info("[pack] external pack is up to date");
            } else {
                externalStatus = "OUTDATED: upload the new " + zipFile().getFileName() + " (players still get the old upload)";
                log.warning("[pack] " + externalStatus);
            }
            if (!res.equals(before)) for (Player p : Bukkit.getOnlinePlayers()) send(p);
        }, tasks.main());
    }

    public Optional<PackBuilder.Result> current() {
        return Optional.ofNullable(current);
    }

    public List<PackIssue> lastIssues() {
        return lastIssues;
    }

    public List<ConfigIssue> lastBlockIssues() {
        return lastBlockIssues;
    }

    public String packUrl() {
        if (!isBuiltin()) return config.general().packExternalUrl();
        String base = config.general().packPublicUrl();
        if (base == null || base.isBlank()) {
            String ip = Bukkit.getIp();
            base = "http://" + (ip == null || ip.isBlank() ? "127.0.0.1" : ip) + ":" + config.general().packPort();
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + (current == null ? "none" : current.sha1()) + ".zip";
    }

    public void send(Player p) {
        PackBuilder.Result r = current;
        if (r == null || !config.general().packEnabled()) return;
        String hash = r.sha1();
        if (!isBuiltin()) {
            if (externalSha1 == null) return; // not uploaded or not reachable yet: sending would only fail
            hash = externalSha1;
        }
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(packUrl()), hash);
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(config.general().packRequired())
                .replace(true)
                .prompt(MiniMessage.miniMessage().deserialize(config.general().packPrompt()))
                .build());
    }

    private void startHttp() throws IOException {
        int port = config.general().packPort();
        http = HttpServer.create(new InetSocketAddress(port), 16);
        http.setExecutor(Executors.newFixedThreadPool(2, r -> Thread.ofPlatform().name("Tactical-PackHTTP").daemon(true).unstarted(r)));
        http.createContext("/", exchange -> {
            try (exchange) {
                PackBuilder.Result r = current;
                String path = exchange.getRequestURI().getPath();
                if (r == null || !path.equals("/" + r.sha1() + ".zip") || !exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, r.zip().length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(r.zip());
                }
            }
        });
        http.start();
        log.info("[pack] serving the resource pack on port " + port);
    }

    private void detectPackFormat() {
        for (ClassLoader cl : List.of(Bukkit.class.getClassLoader(), Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader())) {
            if (cl == null) continue;
            try (InputStream in = cl.getResourceAsStream("version.json")) {
                if (in == null) continue;
                JsonObject v = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonObject("pack_version");
                formatMajor = v.get("resource_major").getAsInt();
                formatMinor = v.has("resource_minor") ? v.get("resource_minor").getAsInt() : 0;
                log.info("[pack] resource pack format " + formatMajor + "." + formatMinor + " (from the server's version.json)");
                return;
            } catch (IOException | RuntimeException e) {
                // try next loader
            }
        }
        log.warning("[pack] could not read version.json; assuming resource pack format 97.1 (Minecraft 26.3)");
    }

    private void copyDefault(String rel) throws IOException {
        Path target = config.root().resolve(rel);
        if (Files.exists(target)) return;
        try (InputStream in = plugin.getResource("defaults/" + rel)) {
            if (in == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
    }

    // ------------------------------------------------------------------ custom block palette

    public Map<String, CustomBlock> blocks() {
        return blocks;
    }

    public Optional<ItemStack> paletteItem(String id, int amount) {
        CustomBlock b = blocks.get(id);
        if (b == null) return Optional.empty();
        Material m = Material.matchMaterial("minecraft:" + b.blockId());
        if (m == null || !m.isItem()) return Optional.empty();
        ItemStack item = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        item.editMeta(meta -> {
            meta.displayName(net.kyori.adventure.text.Component.text(b.name(), net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(net.kyori.adventure.text.Component.text("Custom block '" + b.id() + "'", net.kyori.adventure.text.format.NamedTextColor.GRAY),
                    net.kyori.adventure.text.Component.text("Places " + b.stateKey(), net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)));
            meta.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, b.id());
        });
        return Optional.of(item);
    }

    /** Palette items place their exact state, regardless of where the player looks or which half was clicked. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (!item.hasItemMeta()) return;
        String id = item.getItemMeta().getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
        CustomBlock b = id == null ? null : blocks.get(id);
        if (b == null) return;
        var data = e.getBlockPlaced().getBlockData();
        switch (b.kind()) {
            case DOUBLE_SLAB -> {
                if (data instanceof Slab slab) {
                    slab.setType(Slab.Type.DOUBLE);
                    slab.setWaterlogged(false);
                    e.getBlockPlaced().setBlockData(slab, false);
                }
            }
            case GLAZED_FACING -> {
                if (data instanceof Directional d) {
                    d.setFacing(BlockFace.valueOf(b.facing().toUpperCase(Locale.ROOT)));
                    e.getBlockPlaced().setBlockData(d, false);
                }
            }
            case GLAZED_ROTATING -> { }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        send(e.getPlayer());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        switch (e.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    log.warning("[pack] " + e.getPlayer().getName() + ": " + e.getStatus() + " (url " + packUrl() + ")");
            default -> { }
        }
    }
}
