package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.World;
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
    protected World world;

    protected BlockEntity(BlockEntityType<?> type, BlockPos pos) {
        this.type = type;
        this.pos = pos;
    }

    public final void setWorld(World world) {
        this.world = world;
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

    /** Liefert eine Fähigkeit für eine Seite (oder null), falls vorhanden. Default: leer. */
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        return Optional.empty();
    }
}
