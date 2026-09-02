package de.skyengine.client.network;

import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.packets.CorePackets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Revisioned authoritative container cache. Client-side clicks never mutate this state directly. */
public final class ReplicatedInventory {
    public record Container(int id, int revision, List<NetworkItemStack> slots, NetworkItemStack carried) {
        public Container { slots = List.copyOf(slots); }
    }
    private final Map<Integer, Container> containers = new HashMap<>();
    private final Map<Integer, int[]> data = new HashMap<>();

    public void accept(CorePackets.InventoryContent packet) {
        Container current = this.containers.get(packet.containerId());
        if (current == null || packet.revision() >= current.revision()) {
            this.containers.put(packet.containerId(), new Container(packet.containerId(), packet.revision(),
                    packet.stacks(), packet.carried()));
        }
    }

    public void accept(CorePackets.InventorySlotUpdate packet) throws ProtocolException {
        Container current = this.containers.get(packet.containerId());
        if (current == null) throw new ProtocolException("Slot update for unknown container");
        if (packet.revision() <= current.revision()) return;
        if (packet.revision() != current.revision() + 1) throw new ProtocolException("Inventory revision gap");
        if (packet.slot() < 0 || packet.slot() >= current.slots().size()) throw new ProtocolException("Invalid slot");
        List<NetworkItemStack> slots = new ArrayList<>(current.slots());
        slots.set(packet.slot(), packet.stack());
        this.containers.put(packet.containerId(), new Container(packet.containerId(), packet.revision(),
                slots, current.carried()));
    }

    public Container get(int containerId) { return this.containers.get(containerId); }
    public void accept(CorePackets.ContainerData packet) { this.data.put(packet.containerId(), packet.values()); }
    public int[] data(int containerId) {
        int[] values = this.data.get(containerId);
        return values == null ? new int[0] : values.clone();
    }
    public void remove(int containerId) { this.containers.remove(containerId); this.data.remove(containerId); }
}
