package dev.tactical.paper.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executor;

/** Thread helpers. Gameplay state is only ever touched on the main thread; I/O runs elsewhere. */
public final class Tasks {

    private final Plugin plugin;
    private final Executor mainExecutor;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
        this.mainExecutor = r -> {
            if (Bukkit.isPrimaryThread()) r.run();
            else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
        };
    }

    /** Executor that runs on the server main thread (use with CompletableFuture#thenAcceptAsync). */
    public Executor main() {
        return mainExecutor;
    }

    public void requireMainThread(String what) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(what + " must be called on the main thread");
        }
    }

    public void requireAsync(String what) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(what + " must NOT be called on the main thread (blocking I/O)");
        }
    }

    public Plugin plugin() {
        return plugin;
    }
}
