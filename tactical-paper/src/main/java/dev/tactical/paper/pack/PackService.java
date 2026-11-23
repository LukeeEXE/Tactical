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
        detectPackFormat();
        Set<String> known = new HashSet<>();
        for (var b : Registry.BLOCK) {
            NamespacedKey k = b.getKey();
            if (k.getNamespace().equals("minecraft")) known.add(k.getKey());
        }
        knownBlocks = Set.copyOf(known);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (config.general().packEnabled()) startHttp();
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
                return new PackBuilder(formatMajor, formatMinor, "Tactical server pack").build(sourceRoot(), list, knownBlocks);
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
            log.info("[pack] built " + result.stats().get("files") + " files, " + result.zip().length / 1024 + " KiB, sha1 "
                    + result.sha1().substring(0, 12) + (result.hasErrors() ? " (WITH ERRORS, see above)" : ""));
            if (!result.sha1().equals(old) && config.general().packEnabled()) {
                for (Player p : Bukkit.getOnlinePlayers()) send(p);
            }
            return result;
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
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(packUrl()), r.sha1());
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
