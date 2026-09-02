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
    private static final int MAX_IN_FLIGHT_PER_PLAYER = 8;
    private static final int MAX_CHUNKS_SENT_PER_TICK = 4;

    private record Completed(String sessionId, ChunkInterestManager.ChunkRequest request,
                             Optional<ChunkColumnSnapshot> snapshot, Throwable failure) {}
    private static final class StreamState {
        final PriorityQueue<ChunkInterestManager.ChunkRequest> pending = new PriorityQueue<>(
                Comparator.comparingInt(ChunkInterestManager.ChunkRequest::distanceSquared)
                        .thenComparing(Comparator.comparingInt(
                                ChunkInterestManager.ChunkRequest::forwardScore).reversed()));
        final ArrayDeque<ChunkColumnSnapshot> ready = new ArrayDeque<>();
        int inFlight;
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
            stream.inFlight = Math.max(0, stream.inFlight - 1);
            if (result.failure() == null && result.snapshot().isPresent()
                    && this.interests.tracks(result.sessionId(), result.request().dimension(),
                            result.request().chunkX(), result.request().chunkZ())) {
                ChunkColumnSnapshot snapshot = result.snapshot().get();
                if (snapshot.dimension().equals(result.request().dimension())
                        && snapshot.chunkX() == result.request().chunkX()
                        && snapshot.chunkZ() == result.request().chunkZ()) stream.ready.add(snapshot);
            }
        }
        for (Map.Entry<String, StreamState> entry : this.streams.entrySet()) {
            PlayerSession session = sessions.get(entry.getKey());
            if (session == null || session.state() != de.skyengine.shared.network.ConnectionState.PLAY) continue;
            StreamState stream = entry.getValue();
            while (stream.inFlight < MAX_IN_FLIGHT_PER_PLAYER && !stream.pending.isEmpty()) {
                ChunkInterestManager.ChunkRequest request = stream.pending.poll();
                stream.inFlight++;
                this.world.requestChunkSnapshot(request.dimension(), request.chunkX(), request.chunkZ())
                        .whenComplete((snapshot, failure) -> this.completed.add(new Completed(entry.getKey(), request,
                                snapshot == null ? Optional.empty() : snapshot, failure)));
            }
            int sent = 0;
            while (sent++ < MAX_CHUNKS_SENT_PER_TICK && !stream.ready.isEmpty()) {
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
            }
        }
    }

    public void remove(PlayerSession session) {
        this.interests.remove(session.connection().id());
        this.streams.remove(session.connection().id());
    }

    public int trackedChunks(PlayerSession session) {
        return this.interests.trackedChunks(session.connection().id());
    }

    public boolean tracks(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        return this.interests.tracks(session.connection().id(), dimension, chunkX, chunkZ);
    }
}
