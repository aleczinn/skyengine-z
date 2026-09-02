package de.skyengine.server.world;

import de.skyengine.server.network.PlayerSession;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkPosition;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.transport.BatchTransport;

/** Tick-owned prioritized streaming queues with bounded asynchronous snapshot work. */
public final class ChunkReplicationService {
    private static final int MAX_IN_FLIGHT_PER_PLAYER = 32;
    private static final int MAX_OUTBOUND_PACKETS_PER_PLAYER = 256;
    private static final int MAX_SENT_AWAITING_ACK_PER_PLAYER = 2_048;

    private record RequestKey(String dimension, int x, int z) { }
    private record ActiveRequest(ChunkInterestManager.ChunkRequest request, ChunkSnapshotTicket ticket) { }
    private record SentBatch(RequestKey key, long revision) { }
    private record Completed(String sessionId, RequestKey key, ChunkSnapshotTicket ticket,
                             Optional<ChunkColumnSnapshot> snapshot, Throwable failure) {}
    private static final class StreamState {
        final PriorityQueue<ChunkInterestManager.ChunkRequest> pending = new PriorityQueue<>(
                Comparator.comparingInt(ChunkInterestManager.ChunkRequest::distanceSquared)
                        .thenComparing(Comparator.comparingInt(
                                ChunkInterestManager.ChunkRequest::forwardScore).reversed()));
        final ArrayDeque<ChunkColumnSnapshot> ready = new ArrayDeque<>();
        final Map<RequestKey, ActiveRequest> inFlight = new HashMap<>();
        final Map<Long, SentBatch> sentAwaitingAck = new HashMap<>();
        final java.util.Set<RequestKey> applied = new java.util.HashSet<>();
        long nextBatchId = 1;
    }

    private final ServerWorldRuntime world;
    private final ChunkInterestManager interests = new ChunkInterestManager();
    private final Map<String, StreamState> streams = new HashMap<>();
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();

    public ChunkReplicationService(ServerWorldRuntime world) { this.world = world; }

    public void updateInterest(PlayerSession session, String dimension, int centerChunkX, int centerChunkZ,
                               int viewDistance, float motionX, float motionZ) {
        ChunkInterestManager.InterestDelta delta = this.interests.update(session.connection().id(), dimension,
                centerChunkX, centerChunkZ, viewDistance, motionX, motionZ);
        StreamState stream = this.streams.computeIfAbsent(session.connection().id(), ignored -> new StreamState());
        stream.pending.removeIf(request -> !this.interests.tracks(session.connection().id(), request.dimension(),
                request.chunkX(), request.chunkZ()));
        stream.ready.removeIf(snapshot -> !this.interests.tracks(session.connection().id(), snapshot.dimension(),
                snapshot.chunkX(), snapshot.chunkZ()));
        var active = stream.inFlight.entrySet().iterator();
        while (active.hasNext()) {
            var request = active.next();
            if (this.interests.tracks(session.connection().id(), request.getKey().dimension(),
                    request.getKey().x(), request.getKey().z())) continue;
            request.getValue().ticket().cancel();
            active.remove();
        }
        stream.applied.removeIf(key -> !this.interests.tracks(session.connection().id(),
                key.dimension(), key.x(), key.z()));
        // Already-written TCP batches must remain acknowledgeable even if an interest move has
        // queued an UnloadChunk behind them. acknowledge(...) deliberately does not re-apply an
        // untracked chunk, but treating this ordered late ACK as malformed would disconnect a
        // perfectly healthy fast-moving client.
        stream.pending.addAll(delta.entered());
        for (ChunkPosition left : delta.left()) {
            session.send(new CorePackets.UnloadChunk(delta.leftDimension(), left.x(), left.z()));
        }
    }

    public void tick(Map<String, PlayerSession> sessions) {
        Completed result;
        while ((result = this.completed.poll()) != null) {
            StreamState stream = this.streams.get(result.sessionId());
            if (stream == null) continue;
            ActiveRequest active = stream.inFlight.get(result.key());
            if (active == null || active.ticket() != result.ticket()) continue;
            stream.inFlight.remove(result.key());
            if (result.failure() == null && result.snapshot().isPresent()
                    && this.interests.tracks(result.sessionId(), result.key().dimension(),
                            result.key().x(), result.key().z())) {
                ChunkColumnSnapshot snapshot = result.snapshot().get();
                if (snapshot.dimension().equals(result.key().dimension())
                        && snapshot.chunkX() == result.key().x()
                        && snapshot.chunkZ() == result.key().z()) stream.ready.add(snapshot);
            }
        }
        for (Map.Entry<String, StreamState> entry : this.streams.entrySet()) {
            PlayerSession session = sessions.get(entry.getKey());
            if (session == null || session.state() != de.skyengine.shared.network.ConnectionState.PLAY) continue;
            StreamState stream = entry.getValue();
            while (stream.inFlight.size() < MAX_IN_FLIGHT_PER_PLAYER && !stream.pending.isEmpty()) {
                ChunkInterestManager.ChunkRequest request = stream.pending.poll();
                RequestKey key = new RequestKey(request.dimension(), request.chunkX(), request.chunkZ());
                if (stream.inFlight.containsKey(key)) continue;
                ChunkSnapshotTicket ticket = this.world.requestChunkSnapshot(
                        request.dimension(), request.chunkX(), request.chunkZ());
                stream.inFlight.put(key, new ActiveRequest(request, ticket));
                ticket.result().whenComplete((snapshot, failure) -> this.completed.add(new Completed(
                        entry.getKey(), key, ticket, snapshot == null ? Optional.empty() : snapshot, failure)));
            }
            while (!stream.ready.isEmpty()
                    && stream.sentAwaitingAck.size() < MAX_SENT_AWAITING_ACK_PER_PLAYER
                    && session.connection().outboundSize() < MAX_OUTBOUND_PACKETS_PER_PLAYER) {
                ChunkColumnSnapshot snapshot = stream.ready.poll();
                if (!this.interests.tracks(entry.getKey(), snapshot.dimension(),
                        snapshot.chunkX(), snapshot.chunkZ())) continue;
                long batchId = stream.nextBatchId++;
                CorePackets.ChunkBatchStart start = new CorePackets.ChunkBatchStart(batchId,
                        snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ(), 1);
                CorePackets.ChunkColumnData data = new CorePackets.ChunkColumnData(batchId, snapshot);
                CorePackets.ChunkBatchEnd end = new CorePackets.ChunkBatchEnd(batchId);
                if (session.connection() instanceof BatchTransport batches) {
                    if (!batches.sendBatch(List.of(new PacketEnvelope(start), new PacketEnvelope(data),
                            new PacketEnvelope(end)))) {
                        stream.ready.addFirst(snapshot);
                        stream.nextBatchId--;
                        break;
                    }
                } else {
                    session.send(start);
                    session.send(data);
                    session.send(end);
                }
                stream.sentAwaitingAck.put(batchId, new SentBatch(new RequestKey(snapshot.dimension(),
                        snapshot.chunkX(), snapshot.chunkZ()), snapshot.revision()));
            }
        }
    }

    public void remove(PlayerSession session) {
        this.interests.remove(session.connection().id());
        StreamState removed = this.streams.remove(session.connection().id());
        if (removed != null) removed.inFlight.values().forEach(active -> active.ticket().cancel());
    }

    public int trackedChunks(PlayerSession session) {
        return this.interests.trackedChunks(session.connection().id());
    }

    public boolean tracks(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        return this.interests.tracks(session.connection().id(), dimension, chunkX, chunkZ);
    }

    /** Validates an ordered client acknowledgement and records CPU-cache residency. */
    public boolean acknowledge(PlayerSession session, long batchId) {
        StreamState stream = this.streams.get(session.connection().id());
        if (stream == null) return false;
        SentBatch sent = stream.sentAwaitingAck.remove(batchId);
        if (sent == null) return false;
        if (this.interests.tracks(session.connection().id(), sent.key().dimension(),
                sent.key().x(), sent.key().z())) stream.applied.add(sent.key());
        return true;
    }

    /** Movement frontier: data must be installed, GPU upload is deliberately irrelevant. */
    public boolean isCollisionAreaApplied(PlayerSession session, String dimension,
                                          double playerX, double playerZ) {
        StreamState stream = this.streams.get(session.connection().id());
        if (stream == null) return false;
        int centerX = (int) Math.floor(playerX) >> 5;
        int centerZ = (int) Math.floor(playerZ) >> 5;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx * dx + dz * dz > 4) continue;
                if (!stream.applied.contains(new RequestKey(dimension, centerX + dx, centerZ + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    public void requestResync(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        String sessionId = session.connection().id();
        if (!this.interests.tracks(sessionId, dimension, chunkX, chunkZ)) return;
        StreamState stream = this.streams.computeIfAbsent(sessionId, ignored -> new StreamState());
        stream.pending.removeIf(request -> request.dimension().equals(dimension)
                && request.chunkX() == chunkX && request.chunkZ() == chunkZ);
        stream.ready.removeIf(snapshot -> snapshot.dimension().equals(dimension)
                && snapshot.chunkX() == chunkX && snapshot.chunkZ() == chunkZ);
        RequestKey key = new RequestKey(dimension, chunkX, chunkZ);
        ActiveRequest active = stream.inFlight.remove(key);
        if (active != null) active.ticket().cancel();
        stream.pending.add(new ChunkInterestManager.ChunkRequest(dimension, chunkX, chunkZ, -1,
                Integer.MAX_VALUE));
    }
}
