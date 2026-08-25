package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Transiente Behavior-Daten pro Welt und Blockposition. Ein Eintrag gehört zusätzlich zum
 * konkreten Chunk-Objekt: nach Unload, F8-Reload oder Chunk-Ersatz kann ein neuer Chunk gleicher
 * Koordinate deshalb keinen Laufzeitzustand seines Vorgängers erben.
 *
 * <p>Die Behavior-Instanzen gehören zur globalen Block-Registry und leben länger als eine Welt.
 * Die schwachen Welt-Schlüssel verhindern, dass dieser Speicher eine verlassene Welt festhält.
 * Alle Zugriffe laufen seriell auf dem Tick-/Render-Thread.</p>
 */
public final class WorldScopedPositionMap<V> {

    private static final class Entry<V> {
        /* Darf den Chunk nicht stark halten: dessen BlockEntities können zur Welt zurückzeigen
           und damit den schwachen Welt-Schlüssel indirekt wieder stark erreichbar machen. */
        private final WeakReference<Chunk> chunk;
        private final V value;

        private Entry(Chunk chunk, V value) {
            this.chunk = new WeakReference<>(chunk);
            this.value = value;
        }
    }

    private static final class State<V> {
        private final Map<Long, Entry<V>> entries = new HashMap<>();
        private int removalVersion;

        private State(int removalVersion) {
            this.removalVersion = removalVersion;
        }
    }

    private final Map<Dimension, State<V>> worlds = new WeakHashMap<>();

    public V get(Dimension world, int x, int y, int z) {
        long position = BlockPos.asLong(x, y, z);
        State<V> state = this.state(world);
        Entry<V> entry = state.entries.get(position);
        if (entry == null) return null;
        Chunk current = world.getChunkManager().getChunk(
                x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (current == entry.chunk.get()) return entry.value;
        state.entries.remove(position);
        return null;
    }

    /** @return der vorherige Wert desselben Chunk-Objekts oder {@code null}. */
    public V put(Dimension world, int x, int y, int z, V value) {
        if (value == null) throw new IllegalArgumentException("null ist kein gültiger Behavior-Zustand");
        long position = BlockPos.asLong(x, y, z);
        State<V> state = this.state(world);
        Chunk chunk = world.getChunkManager().getChunk(
                x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) {
            state.entries.remove(position);
            return null;
        }
        Entry<V> previous = state.entries.put(position, new Entry<>(chunk, value));
        return previous != null && previous.chunk.get() == chunk ? previous.value : null;
    }

    public V computeIfAbsent(Dimension world, int x, int y, int z, Supplier<V> factory) {
        V value = this.get(world, x, y, z);
        if (value != null) return value;
        value = factory.get();
        this.put(world, x, y, z, value);
        return value;
    }

    public V remove(Dimension world, int x, int y, int z) {
        Entry<V> removed = this.state(world).entries.remove(BlockPos.asLong(x, y, z));
        return removed == null ? null : removed.value;
    }

    /** Wird von {@link Dimension} nur nach einer tatsächlichen Entfernung aus der Chunk-Map gerufen. */
    public void prune(Dimension world) {
        State<V> state = this.worlds.get(world);
        if (state == null) return;
        int removalVersion = world.getChunkManager().getChunkRemovalVersion();
        if (state.removalVersion == removalVersion) return;
        state.entries.entrySet().removeIf(mapEntry -> {
            long position = mapEntry.getKey();
            int x = BlockPos.unpackX(position);
            int z = BlockPos.unpackZ(position);
            Chunk current = world.getChunkManager().getChunk(
                    x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            return current != mapEntry.getValue().chunk.get();
        });
        state.removalVersion = removalVersion;
    }

    /**
     * Diagnose-Sicht der aktiven Welt. Die Werte sind absichtlich gekapselte Einträge;
     * {@code clear()} und {@code keySet()} bleiben für die bestehende Headless-Sonde nutzbar.
     */
    public Map<Long, ?> diagnosticEntries(Dimension world) {
        return this.state(world).entries;
    }

    private State<V> state(Dimension world) {
        State<V> state = this.worlds.get(world);
        if (state == null) {
            state = new State<>(world.getChunkManager().getChunkRemovalVersion());
            this.worlds.put(world, state);
            world.registerTransientPositionState(this);
        } else {
            this.prune(world);
        }
        return state;
    }
}
