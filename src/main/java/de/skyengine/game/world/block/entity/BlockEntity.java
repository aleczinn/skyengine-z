package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;

import java.util.Optional;

/**
 * Basis aller „lebenden" Blöcke (Maschinen, Energiespeicher, Pipes, Tanks). Hält Daten und
 * optionales Verhalten, getrennt vom {@link de.skyengine.game.world.block.state.BlockState}.
 * Persistenz über {@link #load}/{@link #save} ({@link DataTag}); Fähigkeiten über
 * {@link #getCapability} (Modding-Hook).
 */
public abstract class BlockEntity {

    protected final BlockEntityType<?> type;
    protected final BlockPos pos;
    protected Dimension world;

    protected BlockEntity(BlockEntityType<?> type, BlockPos pos) {
        this.type = type;
        this.pos = pos;
    }

    public final void setWorld(Dimension world) {
        this.world = world;
    }

    /** Welt, in der die BlockEntity liegt (null vor dem Einhängen in einen Chunk). */
    public final Dimension getWorld() {
        return this.world;
    }

    public final BlockEntityType<?> getType() {
        return type;
    }

    public final BlockPos getPos() {
        return pos;
    }

    /** Wird pro Tick aufgerufen, falls der Typ tickend ist. Default: nichts. */
    public void tick() {}

    public void load(DataTag tag) {}

    public void save(DataTag tag) {}

    /**
     * Serialisiert den replizierten Laufzeitzustand. Standardmaessig entspricht er dem
     * persistenten Zustand; BlockEntities mit rein visuellen/transienten Daten koennen den
     * Netzwerkzustand erweitern, ohne diese Daten ins Welt-Save zu schreiben.
     */
    public void saveNetwork(DataTag tag) {
        this.save(tag);
    }

    /** Gegenstueck zu {@link #saveNetwork(DataTag)} fuer Client-Replikate. */
    public void loadNetwork(DataTag tag) {
        this.load(tag);
    }

    /**
     * Markiert den eigenen Chunk als seit dem letzten Save verändert — von Unterklassen
     * nach persistenten Zustandsänderungen aufrufen (z.B. künftige tickende Maschinen).
     * Das Truhen-GUI markiert stattdessen beim Öffnen (GameContainer).
     */
    protected final void markDirty() {
        if (this.world != null) {
            this.world.markChunkModified(this.pos.x(), this.pos.z());
            this.world.markBlockEntityNetworkDirty(this.pos);
        }
    }

    /** Markiert persistente Inventar-/Maschinendaten als geändert. */
    public final void setChanged() {
        this.markDirty();
        if (this.world != null) {
            this.world.updateComparatorOutputs(this.pos.x(), this.pos.y(), this.pos.z());
        }
    }

    /** Liefert eine Fähigkeit für eine Seite (oder null), falls vorhanden. Default: leer. */
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        return Optional.empty();
    }
}
