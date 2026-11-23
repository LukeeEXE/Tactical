package dev.tactical.engine.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Small typed, synchronous event bus. One instance per match (and one global instance for server-wide events).
 * Handlers registered for a supertype/interface receive subtypes too. A throwing handler never prevents other
 * handlers from running: the exception is passed to the error handler (which logs it with the match id).
 */
public final class EventBus {

    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Consumer<Object>>> resolved = new ConcurrentHashMap<>();
    private final BiConsumer<Object, Throwable> errorHandler;

    public EventBus(BiConsumer<Object, Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }

    /** Registers a handler. Returns a handle that unregisters it. */
    @SuppressWarnings("unchecked")
    public <T> Subscription subscribe(Class<T> type, Consumer<? super T> handler) {
        Consumer<Object> h = o -> handler.accept((T) o);
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(h);
        resolved.clear();
        return () -> {
            List<Consumer<Object>> list = handlers.get(type);
            if (list != null) list.remove(h);
            resolved.clear();
        };
    }

    public void publish(Object event) {
        for (Consumer<Object> h : handlersFor(event.getClass())) {
            try {
                h.accept(event);
            } catch (Throwable t) {
                errorHandler.accept(event, t);
            }
        }
    }

    public void clear() {
        handlers.clear();
        resolved.clear();
    }

    private List<Consumer<Object>> handlersFor(Class<?> type) {
        return resolved.computeIfAbsent(type, t -> {
            List<Consumer<Object>> out = new ArrayList<>();
            for (Map.Entry<Class<?>, List<Consumer<Object>>> e : handlers.entrySet()) {
                if (e.getKey().isAssignableFrom(t)) out.addAll(e.getValue());
            }
            return List.copyOf(out);
        });
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
