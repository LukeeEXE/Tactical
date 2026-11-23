package dev.tactical.paper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Holds every service. Lookups are by interface/class so modules depend on contracts, not on construction order. */
public final class ServiceRegistry {

    private final Map<Class<?>, Service> services = new LinkedHashMap<>();
    private final List<Service> enabled = new ArrayList<>();
    private final Logger logger;

    public ServiceRegistry(Logger logger) {
        this.logger = logger;
    }

    public <T extends Service> T register(Class<? super T> key, T service) {
        if (services.putIfAbsent(key, service) != null) {
            throw new IllegalStateException("Service already registered: " + key.getName());
        }
        return service;
    }

    public <T> T get(Class<T> key) {
        Service s = services.get(key);
        if (s == null) {
            throw new IllegalStateException("Service not registered: " + key.getName());
        }
        return key.cast(s);
    }

    /** Enables all services in order. Fails fast: a failing service aborts startup (the plugin then disables). */
    public void enableAll() throws Exception {
        for (Service s : services.values()) {
            long start = System.nanoTime();
            s.enable();
            enabled.add(s);
            logger.fine(() -> "Enabled " + s.name() + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        }
    }

    /** Disables in reverse order; one failing service never prevents the others from shutting down. */
    public void disableAll() {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            Service s = enabled.get(i);
            try {
                s.disable();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Error while disabling " + s.name(), t);
            }
        }
        enabled.clear();
    }
}
