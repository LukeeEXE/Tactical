package dev.tactical.paper;

import dev.tactical.paper.audit.AuditService;
import dev.tactical.paper.arena.ArenaService;
import dev.tactical.paper.command.GameCommand;
import dev.tactical.paper.command.MapCommands;
import dev.tactical.paper.command.PackCommands;
import dev.tactical.paper.pack.PackService;
import dev.tactical.paper.gui.Icons;
import dev.tactical.paper.gui.MenuService;
import dev.tactical.paper.kit.HotbarItems;
import dev.tactical.paper.kit.KitSelectService;
import dev.tactical.paper.shop.ShopService;
import dev.tactical.paper.lobby.LobbyService;
import dev.tactical.paper.map.MapEditorService;
import dev.tactical.paper.map.MapService;
import dev.tactical.paper.vitals.VitalsService;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.ServiceRegistry;
import dev.tactical.paper.core.Tasks;
import dev.tactical.paper.match.MatchListener;
import dev.tactical.paper.match.MatchService;
import dev.tactical.paper.message.Messages;
import dev.tactical.paper.player.PlayerService;
import dev.tactical.paper.record.RecorderService;
import dev.tactical.paper.storage.Database;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * Entry point. Wires services in dependency order; they are enabled in this order and disabled in reverse.
 * If any service fails to start, the plugin disables itself instead of running half-initialised.
 */
public final class TacticalPlugin extends JavaPlugin {

    private ServiceRegistry services;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        services = new ServiceRegistry(getLogger());
        Tasks tasks = new Tasks(this);
        ConfigService config = services.register(ConfigService.class, new ConfigService(this));
        Messages messages = new Messages(config);
        Database db = services.register(Database.class, new Database(config, tasks, getLogger()));
        AuditService audit = services.register(AuditService.class, new AuditService(db, getLogger()));
        RecorderService recorders = services.register(RecorderService.class, new RecorderService(config, getLogger()));
        PlayerService players = services.register(PlayerService.class, new PlayerService(this, db, getLogger()));
        LobbyService lobby = services.register(LobbyService.class, new LobbyService(config, getLogger()));
        MapService maps = services.register(MapService.class, new MapService(config, tasks, getLogger()));
        MapEditorService editor = services.register(MapEditorService.class, new MapEditorService(this, maps));
        MatchService matches = services.register(MatchService.class,
                new MatchService(this, config, db, recorders, players, messages, audit, lobby, tasks, getLogger()));
        services.register(MatchListener.class, new MatchListener(this, matches));
        services.register(ArenaService.class, new ArenaService(this, config, maps, matches, lobby, getLogger()));
        PackService pack = services.register(PackService.class, new PackService(this, config, tasks, getLogger()));
        MenuService menus = services.register(MenuService.class, new MenuService(this, getLogger()));
        Icons icons = new Icons(this, config.root().resolve("gui/icons.yml"), getLogger());
        HotbarItems hotbar = new HotbarItems(this, icons);
        // Order matters: these hooks run after ArenaService's, so they act on the freshly placed players.
        services.register(KitSelectService.class, new KitSelectService(this, config, matches, menus, icons, hotbar));
        services.register(ShopService.class, new ShopService(this, config, maps, matches, menus, icons, hotbar, getLogger()));
        services.register(VitalsService.class, new VitalsService(this, config, p -> matches.matchOf(p.getUniqueId())
                .map(m -> !m.flow().isPaused() && m.flow().participant(p.getUniqueId()).map(x -> x.active()).orElse(false))
                .orElse(true)));
        try {
            services.enableAll();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Tactical failed to start; disabling to protect server state", t);
            services.disableAll();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MapCommands mapCommands = new MapCommands(maps, editor, config, audit, lobby, tasks);
        GameCommand command = new GameCommand(config, matches, recorders, audit, messages, tasks, getPluginMeta().getVersion(),
                maps, List.of(mapCommands::attach, new PackCommands(pack, audit, tasks)::attach));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command.build(), "Tactical shooter commands", List.of("tactical")));
        getLogger().info("Tactical enabled in " + (System.currentTimeMillis() - start) + " ms");
    }

    @Override
    public void onDisable() {
        if (services != null) services.disableAll();
    }
}
