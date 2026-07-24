package de.skyengine.game.world.block.network;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.entity.Capability;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.entity.EnergyStorage;
import de.skyengine.game.world.block.entity.SimpleEnergyStorage;

import java.util.Optional;

/**
 * Energie-Kabel als BlockEntity: kleiner Puffer, der pro Tick aus benachbarten Quellen zieht
 * und an benachbarte Senken (inkl. weiterer Kabel) abgibt. Damit fließt Energie entlang einer
 * Kabelstrecke. Eine echte Netzwerk-Graph-Optimierung (ein {@code EnergyNetwork} pro Verbund)
 * ist der nächste Schritt — die Capability-Schnittstelle bleibt dieselbe.
 */
public final class CableBlockEntity extends BlockEntity {

    private static final int THROUGHPUT = 100;

    private final SimpleEnergyStorage buffer = new SimpleEnergyStorage(2000, THROUGHPUT);

    public CableBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        if (capability == Capabilities.ENERGY) return Optional.of((C) this.buffer);
        return Optional.empty();
    }

    @Override
    public void tick() {
        if (this.world == null) return;

        /* 1. Aus Nachbarn ziehen, die abgeben können. */
        for (Direction d : Direction.sharedValues()) {
            EnergyStorage src = neighborEnergy(d);
            if (src == null) continue;
            int room = this.buffer.receive(THROUGHPUT, true);
            int moved = src.extract(room, false);
            this.buffer.receive(moved, false);
        }

        /* 2. An Nachbarn abgeben, die aufnehmen können. */
        for (Direction d : Direction.sharedValues()) {
            if (this.buffer.getEnergy() <= 0) break;
            EnergyStorage sink = neighborEnergy(d);
            if (sink == null) continue;
            int avail = this.buffer.extract(THROUGHPUT, true);
            int accepted = sink.receive(avail, false);
            this.buffer.extract(accepted, false);
        }
    }

    private EnergyStorage neighborEnergy(Direction d) {
        BlockEntity nb = this.world.getBlockEntity(
                this.pos.x() + d.offsetX(), this.pos.y() + d.offsetY(), this.pos.z() + d.offsetZ());
        if (nb == null) return null;
        return nb.getCapability(Capabilities.ENERGY, d.opposite()).orElse(null);
    }

    @Override
    public void save(DataTag tag) {
        tag.putInt("energy", this.buffer.getEnergy());
    }

    @Override
    public void load(DataTag tag) {
        this.buffer.setEnergy(tag.getInt("energy", 0));
    }
}
