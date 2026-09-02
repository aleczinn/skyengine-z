package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.PacketEnvelope;

import java.util.List;

/** Optional transport capability for atomically queued, order-preserving packet batches. */
public interface BatchTransport {
    boolean sendBatch(List<PacketEnvelope> packets);
}
