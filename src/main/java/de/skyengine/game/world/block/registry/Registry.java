package de.skyengine.game.world.block.registry;

import de.skyengine.game.world.block.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generische, Identifier-gekeyte Registry. Einheitlicher Erweiterungspunkt für
 * alle Inhalte (Blöcke, Archetypen, BlockEntity-Typen, ...). Nach dem Bootstrap
 * wird die Registry {@link #freeze() eingefroren}; spätere Registrierungen werfen.
 *
 * <p>Insertion-Order bleibt erhalten (LinkedHashMap) für deterministische
 * Reihenfolgen (z.B. sequenzielle Runtime-IDs beim Block-Bake).
 */
public final class Registry<T> {

    private final String name;
    private final Map<Identifier, T> entries = new LinkedHashMap<>();
    private boolean frozen;

    public Registry(String name) {
        this.name = name;
    }

    public <V extends T> V register(Identifier id, V value) {
        if (this.frozen) {
            throw new IllegalStateException("Registry '" + this.name + "' ist eingefroren: " + id);
        }
        if (this.entries.containsKey(id)) {
            throw new IllegalStateException("Doppelte Registrierung in '" + this.name + "': " + id);
        }
        this.entries.put(id, value);
        return value;
    }

    public T get(Identifier id) {
        return this.entries.get(id);
    }

    public boolean contains(Identifier id) {
        return this.entries.containsKey(id);
    }

    public Collection<T> values() {
        return this.entries.values();
    }

    public int size() {
        return this.entries.size();
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public void freeze() {
        this.frozen = true;
    }

    public String name() {
        return this.name;
    }
}
