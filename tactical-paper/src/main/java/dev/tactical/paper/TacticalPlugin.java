package dev.tactical.paper;

import dev.tactical.paper.audit.AuditService;
import dev.tactical.paper.command.GameCommand;
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
        MatchService matches = services.register(MatchService.class,
                new MatchService(this, config, db, recorders, players, messages, audit, tasks, getLogger()));
        services.register(MatchListener.class, new MatchListener(this, matches));
        try {
            services.enableAll();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Tactical failed to start; disabling to protect server state", t);
            services.disableAll();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        GameCommand command = new GameCommand(config, matches, recorders, audit, messages, tasks, getPluginMeta().getVersion());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command.build(), "Tactical shooter commands", List.of("tactical")));
        getLogger().info("Tactical enabled in " + (System.currentTimeMillis() - start) + " ms");
    }

    @Override
    public void onDisable() {
        if (services != null) services.disableAll();
    }
}
