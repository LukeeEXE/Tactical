package dev.tactical.paper.lobby;

import dev.tactical.engine.config.ConfigReport;
import dev.tactical.engine.config.ConfigNode;
import dev.tactical.engine.config.YamlLoader;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The shared lobby: spawn location (lobby/lobby.yml, set with /game lobby setspawn) and resetting players who
 * come back from a match or the editor. Lobby features (NPCs, queue items, leaderboards) arrive in Phase 4/5.
 */
public final class LobbyService implements Service {

    private final ConfigService config;
    private final Logger log;
    private Location spawn;

    public LobbyService(ConfigService config, Logger log) {
        this.config = config;
        this.log = log;
    }

    @Override
    public void enable() {
        load();
    }

    @Override
    public void disable() {
    }

    public Location spawn() {
        if (spawn == null || spawn.getWorld() == null) {
            World main = Bukkit.getWorlds().getFirst();
            return main.getSpawnLocation().toCenterLocation();
        }
        return spawn.clone();
    }

    /** Teleports to the lobby and resets match-related state (inventory, game mode, health, effects). */
    public void sendToLobby(Player p) {
        dev.tactical.paper.core.Teleports.to(p, spawn());
        resetPlayer(p, Bukkit.getDefaultGameMode() == GameMode.SURVIVAL ? GameMode.ADVENTURE : Bukkit.getDefaultGameMode());
        p.getInventory().clear();
    }

    public static void resetPlayer(Player p, GameMode mode) {
        p.setGameMode(mode);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max != null ? max.getValue() : 20.0);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setFreezeTicks(0);
        p.setVelocity(new org.bukkit.util.Vector());
        p.setFlying(false);
        p.setAllowFlight(mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
        for (PotionEffect e : p.getActivePotionEffects()) {
            if (!e.getType().equals(PotionEffectType.HUNGER)) p.removePotionEffect(e.getType());
        }
    }

    public void setSpawn(Location l) throws IOException {
        spawn = l.clone();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schema-version", 1);
        o.put("world", l.getWorld().getKey().asString());
        o.put("x", round(l.getX()));
        o.put("y", round(l.getY()));
        o.put("z", round(l.getZ()));
        o.put("yaw", (double) Math.round(l.getYaw()));
        o.put("pitch", (double) Math.round(l.getPitch()));
        DumperOptions d = new DumperOptions();
        d.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Path file = config.root().resolve("lobby/lobby.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# Lobby spawn. Set in-game with /game lobby setspawn\n" + new Yaml(d).dump(o), StandardCharsets.UTF_8);
    }

    private void load() {
        Path file = config.root().resolve("lobby/lobby.yml");
        if (!Files.exists(file)) return;
        ConfigReport r = new ConfigReport();
        ConfigNode n = YamlLoader.load(file, "lobby/lobby.yml", r).orElse(null);
        if (n == null) {
            r.issues().forEach(i -> log.severe(i.toString()));
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(n.string("world", "minecraft:overworld"));
        World w = key == null ? null : Bukkit.getWorld(key);
        if (w == null) {
            log.warning("Lobby world " + key + " is not loaded; using the main world spawn");
            return;
        }
        spawn = new Location(w, n.number("x", 0, -3e7, 3e7), n.number("y", 64, -2048, 2048), n.number("z", 0, -3e7, 3e7),
                (float) n.number("yaw", 0, -360, 360), (float) n.number("pitch", 0, -90, 90));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
