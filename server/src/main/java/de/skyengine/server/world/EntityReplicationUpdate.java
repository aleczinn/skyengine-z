package de.skyengine.server.world;

import de.skyengine.shared.entity.NetworkEntitySnapshot;

import java.util.Objects;

/** Tick-owned dirty updates exported by a world runtime; never a full world scan. */
public sealed interface EntityReplicationUpdate {
    record Upsert(NetworkEntitySnapshot snapshot) implements EntityReplicationUpdate {
        public Upsert { Objects.requireNonNull(snapshot); }
    }
    record Despawn(int networkId, int reason) implements EntityReplicationUpdate {
        public Despawn { if (networkId <= 0) throw new IllegalArgumentException("Invalid network entity ID"); }
    }
    record Metadata(int networkId, long revision, byte[] payload) implements EntityReplicationUpdate {
        public Metadata {
            if (networkId <= 0 || revision < 0) throw new IllegalArgumentException("Invalid entity metadata update");
            payload = payload == null ? new byte[0] : payload.clone();
        }
        @Override public byte[] payload() { return this.payload.clone(); }
    }
    record Event(int networkId, int eventId, int data) implements EntityReplicationUpdate {
        public Event { if (networkId <= 0) throw new IllegalArgumentException("Invalid network entity ID"); }
    }
}
