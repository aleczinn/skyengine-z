package de.skyengine.server.network;

import de.skyengine.server.world.EntityReplicationService;
import de.skyengine.server.world.EntityReplicationUpdate;
import de.skyengine.shared.entity.NetworkEntitySnapshot;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.LocalTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class EntityReplicationServiceTest {
    @Test void sendsSpatialSpawnStateAndDespawnWithoutGlobalEntityScan() {
        LocalTransport.Pair pair = LocalTransport.create();
        advanceToPlay(pair.server());
        advanceToPlay(pair.client());
        PlayerSession session = new PlayerSession(pair.server(), 1);
        EntityReplicationService service = new EntityReplicationService();
        Map<String, PlayerSession> sessions = Map.of(pair.server().id(), session);
        service.updateInterest(session, "skyengine:overworld", 0, 0, 2);

        NetworkEntitySnapshot near = entity(1, 0, 0, 1);
        service.apply(List.of(new EntityReplicationUpdate.Upsert(near)), sessions, 10);
        assertInstanceOf(CorePackets.EntitySpawn.class, pair.client().pollInbound().packet());

        service.apply(List.of(new EntityReplicationUpdate.Upsert(entity(1, 1, 0, 2))), sessions, 11);
        assertInstanceOf(CorePackets.EntityState.class, pair.client().pollInbound().packet());

        service.apply(List.of(new EntityReplicationUpdate.Upsert(entity(1, 1000, 1000, 3))), sessions, 12);
        assertInstanceOf(CorePackets.EntityDespawn.class, pair.client().pollInbound().packet());
        assertNull(pair.client().pollInbound());
    }

    private static NetworkEntitySnapshot entity(int id, double x, double z, long revision) {
        return new NetworkEntitySnapshot(id, 1, "skyengine:overworld", revision,
                x, 64, z, 0, 0, 0, 0, 0, null);
    }

    private static void advanceToPlay(de.skyengine.shared.network.transport.TransportConnection connection) {
        connection.transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
        connection.transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
        connection.transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
        connection.transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
    }
}
