package dev.tactical.paper.core;

/**
 * A plugin subsystem with an explicit lifecycle. Services are enabled in registration order and disabled in
 * reverse, so a service may rely on everything registered before it.
 */
public interface Service {

    default String name() {
        return getClass().getSimpleName();
    }

    void enable() throws Exception;

    void disable() throws Exception;
}
