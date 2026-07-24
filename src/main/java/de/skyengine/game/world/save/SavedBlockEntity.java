package de.skyengine.game.world.save;

import de.skyengine.game.world.block.entity.DataTag;

/**
 * Tick-Thread-Snapshot einer BlockEntity für den Save: der fertig serialisierte {@link DataTag}
 * plus gepackte Lokal-Position und Typ-Id. Der IO-Thread schreibt nur diese Kopie und ruft
 * {@code be.save()} NICHT selbst auf — sonst läse er den Live-Zustand (z.B. das Truhen-Inventar)
 * parallel zu einer GUI-Mutation auf dem Tick-Thread (ConcurrentModificationException / torn Save).
 *
 * <p>Erzeugt in {@link WorldStorage#enqueueSave} über {@link ChunkSerializer#snapshotBlockEntities}
 * (Tick-Thread), geschrieben in {@link ChunkSerializer#serialize} (IO-Thread).
 */
public record SavedBlockEntity(int packedLocalPos, String typeId, DataTag tag) {
}
