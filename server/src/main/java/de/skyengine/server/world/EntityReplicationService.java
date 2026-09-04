package de.skyengine.server.world;

import de.skyengine.server.network.PlayerSession;
import de.skyengine.shared.network.packets.CorePackets;

import java.util.List;
import java.util.Map;

/** Converts tick-dirty entity updates and spatial interest deltas into protocol packets. */
public final class EntityReplicationService {
    private final EntityInterestIndex index = new EntityInterestIndex();

    public void updateInterest(PlayerSession session, String dimension, int centerChunkX, int centerChunkZ,
                               int viewDistance) {
        EntityInterestIndex.EntityDelta delta = this.index.updateInterest(session.connection().id(), dimension,
                centerChunkX, centerChunkZ, viewDistance);
        for (var entity : delta.entered()) session.send(new CorePackets.EntitySpawn(entity));
        for (int networkId : delta.left()) session.send(new CorePackets.EntityDespawn(networkId, 0));
    }

    public void apply(List<EntityReplicationUpdate> updates, Map<String, PlayerSession> sessions, long serverTick) {
        for (EntityReplicationUpdate update : updates) {
            if (update instanceof EntityReplicationUpdate.Upsert upsert) {
                var change = this.index.upsert(upsert.snapshot());
                for (String sessionId : change.enteredSessions()) {
                    send(sessions, sessionId, new CorePackets.EntitySpawn(upsert.snapshot()));
                }
                for (String sessionId : change.retainedSessions()) {
                    send(sessions, sessionId, new CorePackets.EntityState(serverTick, upsert.snapshot()));
                }
                for (String sessionId : change.leftSessions()) {
                    send(sessions, sessionId, new CorePackets.EntityDespawn(upsert.snapshot().networkId(), 0));
                }
            } else if (update instanceof EntityReplicationUpdate.Despawn despawn) {
                List<String> tracked = this.index.trackingSessions(despawn.networkId());
                if (this.index.remove(despawn.networkId()) == null) continue;
                for (String sessionId : tracked) {
                    send(sessions, sessionId, new CorePackets.EntityDespawn(despawn.networkId(), despawn.reason()));
                }
            } else if (update instanceof EntityReplicationUpdate.Metadata metadata) {
                CorePackets.EntityMetadata packet = new CorePackets.EntityMetadata(metadata.networkId(),
                        metadata.revision(), metadata.payload());
                for (String sessionId : this.index.trackingSessions(metadata.networkId())) {
                    send(sessions, sessionId, packet);
                }
            } else if (update instanceof EntityReplicationUpdate.Event event) {
                CorePackets.EntityEvent packet = new CorePackets.EntityEvent(event.networkId(),
                        event.eventId(), event.data());
                for (String sessionId : this.index.trackingSessions(event.networkId())) {
                    send(sessions, sessionId, packet);
                }
            }
        }
    }

    public void removeSession(PlayerSession session) {
        this.index.removeSession(session.connection().id());
    }

    public int entityCount() { return this.index.entityCount(); }

    private static void send(Map<String, PlayerSession> sessions, String sessionId,
                             de.skyengine.shared.network.Packet packet) {
        PlayerSession session = sessions.get(sessionId);
        if (session != null && session.state() == de.skyengine.shared.network.ConnectionState.PLAY) {
            session.send(packet);
        }
    }
}
