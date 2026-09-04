package de.skyengine.server.world;

import de.skyengine.server.network.PlayerSession;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkPosition;

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
    public record StreamStats(int pending, int snapshotInFlight, int readyToSend,
                              int awaitingAck, int applied) { }
    private static final int MAX_IN_FLIGHT_PER_PLAYER = 16;
    private static final int MAX_OUTBOUND_PACKETS_PER_PLAYER = 256;
    /* Keep the non-cancellable part of a TCP stream small. On loopback sixteen full columns
       still saturate encoding and the socket, but a fast player can no longer leave thousands
       of obsolete columns in front of the current view. */
    private static final int MAX_SENT_AWAITING_ACK_PER_PLAYER = 16;

    private record RequestKey(String dimension, int x, int z) { }
    private record ActiveRequest(ChunkInterestManager.ChunkRequest request, ChunkSnapshotTicket ticket) { }
    private record ReadySnapshot(ChunkInterestManager.ChunkRequest request,
                                 ChunkColumnSnapshot snapshot) { }
    private record SentBatch(RequestKey key, long revision, long interestGeneration) { }
    private static final Comparator<ChunkInterestManager.ChunkRequest> STREAM_PRIORITY =
            Comparator.comparingInt((ChunkInterestManager.ChunkRequest request) ->
                            request.distanceSquared() <= 4 ? 0 : 1)
                    .thenComparingInt(request -> request.forwardScore() >= 0 ? 0 : 1)
                    .thenComparingInt(ChunkInterestManager.ChunkRequest::distanceSquared)
                    .thenComparing(Comparator.comparingInt(
                            ChunkInterestManager.ChunkRequest::forwardScore).reversed())
                    .thenComparingInt(ChunkInterestManager.ChunkRequest::chunkZ)
                    .thenComparingInt(ChunkInterestManager.ChunkRequest::chunkX);
    private record Completed(String sessionId, RequestKey key, ChunkSnapshotTicket ticket,
                             Optional<ChunkColumnSnapshot> snapshot, Throwable failure) {}
    private static final class StreamState {
        final PriorityQueue<ChunkInterestManager.ChunkRequest> pending = new PriorityQueue<>(STREAM_PRIORITY);
        final PriorityQueue<ReadySnapshot> ready = new PriorityQueue<>(
                Comparator.comparing(ReadySnapshot::request, STREAM_PRIORITY));
        final Map<RequestKey, ActiveRequest> inFlight = new HashMap<>();
        final Map<Long, SentBatch> sentAwaitingAck = new HashMap<>();
        final java.util.Set<RequestKey> applied = new java.util.HashSet<>();
        final Map<RequestKey, Long> interestGenerations = new HashMap<>();
        long nextBatchId = 1;
        long nextInterestGeneration = 1;
        String priorityDimension;
        int priorityCenterX;
        int priorityCenterZ;
        float priorityX;
        float priorityZ;
    }

    private final ServerWorldRuntime world;
    private final ChunkInterestManager interests = new ChunkInterestManager();
    private final Map<String, StreamState> streams = new HashMap<>();
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();

    public ChunkReplicationService(ServerWorldRuntime world) { this.world = world; }

    public void updateInterest(PlayerSession session, String dimension, int centerChunkX, int centerChunkZ,
                               int viewDistance, int meshHalo, float motionX, float motionZ) {
        ChunkInterestManager.InterestDelta delta = this.interests.update(session.connection().id(), dimension,
                centerChunkX, centerChunkZ, viewDistance, meshHalo, motionX, motionZ);
        StreamState stream = this.streams.computeIfAbsent(session.connection().id(), ignored -> new StreamState());
        stream.pending.removeIf(request -> !this.interests.tracks(session.connection().id(), request.dimension(),
                request.chunkX(), request.chunkZ()));
        stream.ready.removeIf(ready -> !this.interests.tracks(session.connection().id(),
                ready.snapshot().dimension(), ready.snapshot().chunkX(), ready.snapshot().chunkZ()));
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
        for (ChunkInterestManager.ChunkRequest entered : delta.entered()) {
            RequestKey key = new RequestKey(entered.dimension(), entered.chunkX(), entered.chunkZ());
            stream.interestGenerations.put(key, stream.nextInterestGeneration++);
            stream.pending.add(entered);
        }
        stream.priorityDimension = dimension;
        stream.priorityCenterX = centerChunkX;
        stream.priorityCenterZ = centerChunkZ;
        stream.priorityX = motionX;
        stream.priorityZ = motionZ;
        rescoreQueued(stream, dimension, centerChunkX, centerChunkZ, motionX, motionZ);
        reprioritizeInFlight(stream, dimension, centerChunkX, centerChunkZ, motionX, motionZ);
        for (ChunkPosition left : delta.left()) {
            RequestKey key = new RequestKey(delta.leftDimension(), left.x(), left.z());
            long leaseId = stream.interestGenerations.getOrDefault(key, 0L);
            if (session.connection() instanceof BatchTransport batches) {
                var sent = stream.sentAwaitingAck.entrySet().iterator();
                while (sent.hasNext()) {
                    Map.Entry<Long, SentBatch> batch = sent.next();
                    if (!batch.getValue().key().equals(key) || !batches.cancelBatch(batch.getKey())) continue;
                    sent.remove();
                }
            }
            stream.interestGenerations.remove(key);
            session.send(new CorePackets.UnloadChunk(leaseId, delta.leftDimension(), left.x(), left.z()));
        }
    }

    /** Re-scores queued columns when the intended travel direction changes without a chunk crossing. */
    public void reprioritize(PlayerSession session, String dimension, int centerChunkX, int centerChunkZ,
                             float motionX, float motionZ) {
        StreamState stream = this.streams.get(session.connection().id());
        if (stream == null) return;
        float oldLength = (float) Math.sqrt(stream.priorityX * stream.priorityX + stream.priorityZ * stream.priorityZ);
        float newLength = (float) Math.sqrt(motionX * motionX + motionZ * motionZ);
        if (oldLength > 1.0E-4F && newLength > 1.0E-4F) {
            float dot = (stream.priorityX * motionX + stream.priorityZ * motionZ) / (oldLength * newLength);
            if (dot > 0.94F) return;
        } else if (oldLength <= 1.0E-4F && newLength <= 1.0E-4F) {
            return;
        }
        rescoreQueued(stream, dimension, centerChunkX, centerChunkZ, motionX, motionZ);
        reprioritizeInFlight(stream, dimension, centerChunkX, centerChunkZ, motionX, motionZ);
        stream.priorityDimension = dimension;
        stream.priorityCenterX = centerChunkX;
        stream.priorityCenterZ = centerChunkZ;
        stream.priorityX = motionX;
        stream.priorityZ = motionZ;
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
                        && snapshot.chunkZ() == result.key().z()) {
                    ChunkInterestManager.ChunkRequest priority = active.request();
                    if (priority.dimension().equals(stream.priorityDimension)) {
                        priority = rescore(priority, stream.priorityCenterX, stream.priorityCenterZ,
                                stream.priorityX, stream.priorityZ);
                    }
                    stream.ready.add(new ReadySnapshot(priority, snapshot));
                }
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
                ReadySnapshot ready = stream.ready.poll();
                ChunkColumnSnapshot snapshot = ready.snapshot();
                if (!this.interests.tracks(entry.getKey(), snapshot.dimension(),
                        snapshot.chunkX(), snapshot.chunkZ())) continue;
                long batchId = stream.nextBatchId++;
                RequestKey key = new RequestKey(snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ());
                long leaseId = stream.interestGenerations.getOrDefault(key, -1L);
                if (leaseId < 0) continue;
                CorePackets.ChunkBatchStart start = new CorePackets.ChunkBatchStart(batchId, leaseId,
                        snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ(), 1);
                CorePackets.ChunkColumnData data = new CorePackets.ChunkColumnData(batchId, snapshot);
                CorePackets.ChunkBatchEnd end = new CorePackets.ChunkBatchEnd(batchId);
                if (session.connection() instanceof BatchTransport batches) {
                    if (!batches.sendBatch(List.of(new PacketEnvelope(start), new PacketEnvelope(data),
                            new PacketEnvelope(end)))) {
                        stream.ready.add(ready);
                        stream.nextBatchId--;
                        break;
                    }
                } else {
                    session.send(start);
                    session.send(data);
                    session.send(end);
                }
                stream.sentAwaitingAck.put(batchId, new SentBatch(key, snapshot.revision(), leaseId));
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

    public StreamStats stats() {
        int pending = 0, inFlight = 0, ready = 0, awaitingAck = 0, applied = 0;
        for (StreamState stream : this.streams.values()) {
            pending += stream.pending.size();
            inFlight += stream.inFlight.size();
            ready += stream.ready.size();
            awaitingAck += stream.sentAwaitingAck.size();
            applied += stream.applied.size();
        }
        return new StreamStats(pending, inFlight, ready, awaitingAck, applied);
    }

    public boolean tracks(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        return this.interests.tracks(session.connection().id(), dimension, chunkX, chunkZ);
    }

    public boolean isApplied(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        StreamState stream = this.streams.get(session.connection().id());
        return stream != null && stream.applied.contains(new RequestKey(dimension, chunkX, chunkZ));
    }

    /** Validates an ordered client acknowledgement and records CPU-cache residency. */
    public boolean acknowledge(PlayerSession session, long batchId, long leaseId) {
        StreamState stream = this.streams.get(session.connection().id());
        if (stream == null) return false;
        SentBatch sent = stream.sentAwaitingAck.get(batchId);
        if (sent == null || sent.interestGeneration() != leaseId) return false;
        stream.sentAwaitingAck.remove(batchId);
        Long currentGeneration = stream.interestGenerations.get(sent.key());
        if (currentGeneration != null && currentGeneration == sent.interestGeneration()
                && this.interests.tracks(session.connection().id(), sent.key().dimension(),
                        sent.key().x(), sent.key().z())) stream.applied.add(sent.key());
        return true;
    }

    private static int forwardScore(int dx, int dz, float motionX, float motionZ) {
        double motionLength = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (motionLength < 1.0E-6 || distance < 1.0E-6) return 1024;
        return (int) Math.round((dx * motionX + dz * motionZ) / (distance * motionLength) * 1024.0);
    }

    private static ChunkInterestManager.ChunkRequest rescore(
            ChunkInterestManager.ChunkRequest request, int centerChunkX, int centerChunkZ,
            float motionX, float motionZ) {
        int dx = request.chunkX() - centerChunkX;
        int dz = request.chunkZ() - centerChunkZ;
        return new ChunkInterestManager.ChunkRequest(request.dimension(), request.chunkX(), request.chunkZ(),
                dx * dx + dz * dz, forwardScore(dx, dz, motionX, motionZ));
    }

    private static void rescoreQueued(StreamState stream, String dimension, int centerChunkX,
                                      int centerChunkZ, float motionX, float motionZ) {
        java.util.ArrayList<ChunkInterestManager.ChunkRequest> pending =
                new java.util.ArrayList<>(stream.pending.size());
        while (!stream.pending.isEmpty()) {
            ChunkInterestManager.ChunkRequest request = stream.pending.poll();
            pending.add(request.dimension().equals(dimension)
                    ? rescore(request, centerChunkX, centerChunkZ, motionX, motionZ) : request);
        }
        stream.pending.addAll(pending);

        java.util.ArrayList<ReadySnapshot> ready = new java.util.ArrayList<>(stream.ready.size());
        while (!stream.ready.isEmpty()) {
            ReadySnapshot snapshot = stream.ready.poll();
            ready.add(snapshot.request().dimension().equals(dimension)
                    ? new ReadySnapshot(rescore(snapshot.request(), centerChunkX, centerChunkZ,
                    motionX, motionZ), snapshot.snapshot()) : snapshot);
        }
        stream.ready.addAll(ready);
    }

    /** Keeps only the best half of a moving player's snapshot window pinned in flight. */
    private static void reprioritizeInFlight(StreamState stream, String dimension, int centerChunkX,
                                             int centerChunkZ, float motionX, float motionZ) {
        if (stream.inFlight.size() <= MAX_IN_FLIGHT_PER_PLAYER / 2) return;
        java.util.ArrayList<Map.Entry<RequestKey, ActiveRequest>> ordered =
                new java.util.ArrayList<>(stream.inFlight.entrySet());
        ordered.sort(Map.Entry.comparingByValue(Comparator.comparing(
                active -> active.request().dimension().equals(dimension)
                        ? rescore(active.request(), centerChunkX, centerChunkZ, motionX, motionZ)
                        : active.request(), STREAM_PRIORITY)));
        for (int index = MAX_IN_FLIGHT_PER_PLAYER / 2; index < ordered.size(); index++) {
            Map.Entry<RequestKey, ActiveRequest> entry = ordered.get(index);
            ActiveRequest active = entry.getValue();
            if (!active.request().dimension().equals(dimension)
                    || !stream.inFlight.remove(entry.getKey(), active)) continue;
            active.ticket().cancel();
            stream.pending.add(rescore(active.request(), centerChunkX, centerChunkZ, motionX, motionZ));
        }
    }

    public void requestResync(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        String sessionId = session.connection().id();
        if (!this.interests.tracks(sessionId, dimension, chunkX, chunkZ)) return;
        StreamState stream = this.streams.computeIfAbsent(sessionId, ignored -> new StreamState());
        stream.pending.removeIf(request -> request.dimension().equals(dimension)
                && request.chunkX() == chunkX && request.chunkZ() == chunkZ);
        stream.ready.removeIf(ready -> ready.snapshot().dimension().equals(dimension)
                && ready.snapshot().chunkX() == chunkX && ready.snapshot().chunkZ() == chunkZ);
        RequestKey key = new RequestKey(dimension, chunkX, chunkZ);
        ActiveRequest active = stream.inFlight.remove(key);
        if (active != null) active.ticket().cancel();
        stream.pending.add(new ChunkInterestManager.ChunkRequest(dimension, chunkX, chunkZ, -1,
                Integer.MAX_VALUE));
    }
}
