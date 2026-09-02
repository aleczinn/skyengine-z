package de.skyengine.server.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Registration is thread-safe; gameplay events are posted by the authoritative tick thread. */
public final class ServerEventBus {
    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    public <E> AutoCloseable register(Class<E> type, Consumer<E> listener) {
        CopyOnWriteArrayList<Consumer<?>> list = this.listeners.computeIfAbsent(type,
                ignored -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    @SuppressWarnings("unchecked")
    public <E> E post(E event) {
        for (Consumer<?> listener : this.listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>())) {
            ((Consumer<E>) listener).accept(event);
        }
        return event;
    }
}
