package de.skyengine.client.network;

import de.skyengine.shared.entity.NetworkEntitySnapshot;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.packets.CorePackets;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Network-ID keyed entity replicas with stale revision rejection. */
public final class ReplicatedEntityCache {
    private final Map<Integer, NetworkEntitySnapshot> entities = new HashMap<>();

    public void spawn(CorePackets.EntitySpawn packet) throws ProtocolException {
        if (this.entities.putIfAbsent(packet.entity().networkId(), packet.entity()) != null) {
            throw new ProtocolException("Duplicate network entity ID");
        }
    }

    public void state(CorePackets.EntityState packet) {
        NetworkEntitySnapshot update = packet.entity();
        NetworkEntitySnapshot current = this.entities.get(update.networkId());
        if (current == null || update.revision() <= current.revision()) return;
        this.entities.put(update.networkId(), update);
    }

    public void metadata(CorePackets.EntityMetadata packet) {
        NetworkEntitySnapshot current = this.entities.get(packet.networkId());
        if (current == null || packet.revision() <= current.revision()) return;
        this.entities.put(packet.networkId(), new NetworkEntitySnapshot(current.networkId(), current.typeId(),
                current.dimension(), packet.revision(), current.x(), current.y(), current.z(),
                current.velocityX(), current.velocityY(), current.velocityZ(), current.yaw(), current.pitch(),
                packet.metadata()));
    }

    public void despawn(CorePackets.EntityDespawn packet) {
        this.entities.remove(packet.networkId());
    }

    public NetworkEntitySnapshot get(int networkId) { return this.entities.get(networkId); }
    public List<NetworkEntitySnapshot> snapshots() { return List.copyOf(this.entities.values()); }
    public int size() { return this.entities.size(); }
}
