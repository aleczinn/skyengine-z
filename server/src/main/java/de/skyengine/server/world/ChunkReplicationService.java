package de.skyengine.server.world;

import de.skyengine.server.network.PlayerSession;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkPosition;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.transport.BatchTransport;

/** Tick-owned prioritized streaming queues with bounded asynchronous snapshot work. */
public final class ChunkReplicationService {
    public record StreamStats(int pending, int snapshotInFlight, int delayedRetries,
                              int readyToSend, int awaitingAck, int applied,
                              long readyBytes, long awaitingAckBytes,
                              long snapshotRetries, long acknowledgementTimeouts,
                              long resyncRequests) { }
    private static final int OUTBOUND_PACKET_HIGH_WATERMARK = 1024;
    private static final long MIN_READY_BYTES_PER_PLAYER = 32L * 1024 * 1024;
    /* Admission must happen before freeze work. Until the exact packed size is known, charge a
       conservative column credit; the actual retained bytes replace it after completion. */
    private static final long ESTIMATED_SNAPSHOT_BYTES = 512L * 1024;
    private static final long DEFAULT_ACK_TIMEOUT_NANOS = 30_000_000_000L;

    private record RequestKey(String dimension, int x, int z) { }
    private record ActiveRequest(ChunkInterestManager.ChunkRequest request, ChunkSnapshotTicket ticket,
                                 int attempt) { }
    private record DelayedRetry(RequestKey key, ChunkInterestManager.ChunkRequest request,
                                int attempt, long readyNanos) { }
    private record ReadySnapshot(ChunkInterestManager.ChunkRequest request,
                                 ChunkColumnSnapshot snapshot) { }
    private record SentBatch(RequestKey key, long revision, long interestGeneration,
                             long retainedBytes, long budgetLeaseId, long sentNanos) {
        SentBatch withoutCreditsAndBudgetLease() {
            return retainedBytes == 0 && budgetLeaseId == 0 ? this
                    : new SentBatch(key, revision, interestGeneration, 0, 0, sentNanos);
        }
    }
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
        final PriorityQueue<DelayedRetry> retries = new PriorityQueue<>(
                Comparator.comparingLong(DelayedRetry::readyNanos));
        final Map<RequestKey, DelayedRetry> retryByKey = new HashMap<>();
        final Map<Long, SentBatch> sentAwaitingAck = new HashMap<>();
        final java.util.Set<RequestKey> applied = new java.util.HashSet<>();
        final Map<RequestKey, Long> interestGenerations = new HashMap<>();
        long readyBytes;
        long sentAwaitingAckBytes;
        long viewEpoch;
        CorePackets.ChunkViewUpdate pendingView;
        long nextBatchId = 1;
        long nextInterestGeneration = 1;
        String priorityDimension;
        int priorityCenterX;
        int priorityCenterZ;
        float priorityX;
        float priorityZ;
    }

    private final ServerWorldRuntime world;
    private final LongSupplier nanoTime;
    private final long ackTimeoutNanos;
    private final ChunkInterestManager interests = new ChunkInterestManager();
    private final Map<String, StreamState> streams = new HashMap<>();
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    private long snapshotRetries;
    private long acknowledgementTimeouts;
    private long resyncRequests;

    public ChunkReplicationService(ServerWorldRuntime world) {
        this(world, System::nanoTime, DEFAULT_ACK_TIMEOUT_NANOS);
    }

    /** Clock/timeout injection keeps transfer-lifetime behaviour deterministic in tests. */
    public ChunkReplicationService(ServerWorldRuntime world, LongSupplier nanoTime,
                                   long ackTimeoutNanos) {
        this.world = java.util.Objects.requireNonNull(world);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
        if (ackTimeoutNanos <= 0) throw new IllegalArgumentException("ACK timeout must be positive");
        this.ackTimeoutNanos = ackTimeoutNanos;
    }

    public void updateInterest(PlayerSession session, String dimension, int centerChunkX, int centerChunkZ,
                               int viewDistance, int meshHalo, float motionX, float motionZ) {
        ChunkInterestManager.InterestDelta delta = this.interests.update(session.connection().id(), dimension,
                centerChunkX, centerChunkZ, viewDistance, meshHalo, motionX, motionZ);
        StreamState stream = this.streams.computeIfAbsent(session.connection().id(), ignored -> new StreamState());
        stream.viewEpoch++;
        stream.pendingView = new CorePackets.ChunkViewUpdate(dimension, centerChunkX, centerChunkZ,
                viewDistance, meshHalo, stream.viewEpoch);
        stream.pending.removeIf(request -> !this.interests.tracks(session.connection().id(), request.dimension(),
                request.chunkX(), request.chunkZ()));
        stream.ready.removeIf(ready -> !this.interests.tracks(session.connection().id(),
                ready.snapshot().dimension(), ready.snapshot().chunkX(), ready.snapshot().chunkZ()));
        stream.readyBytes = retainedBytes(stream.ready);
        stream.retries.removeIf(retry -> !this.interests.tracks(session.connection().id(),
                retry.key().dimension(), retry.key().x(), retry.key().z()));
        stream.retryByKey.entrySet().removeIf(retry -> !this.interests.tracks(
                session.connection().id(), retry.getKey().dimension(), retry.getKey().x(), retry.getKey().z()));
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
        // Already-written TCP batches remain acknowledgeable after an interest move.
        // ChunkViewUpdate is the authoritative bulk-unload boundary; acknowledge(...) does not
        // re-apply an untracked chunk, but an ordered late ACK is still a valid transfer ACK.
        for (ChunkInterestManager.ChunkRequest entered : delta.entered()) {
            RequestKey key = new RequestKey(entered.dimension(), entered.chunkX(), entered.chunkZ());
            removeRetry(stream, key);
            stream.interestGenerations.put(key, stream.nextInterestGeneration++);
            stream.pending.add(entered);
        }
        stream.priorityDimension = dimension;
        stream.priorityCenterX = centerChunkX;
        stream.priorityCenterZ = centerChunkZ;
        stream.priorityX = motionX;
        stream.priorityZ = motionZ;
        rescoreQueued(stream, dimension, centerChunkX, centerChunkZ, motionX, motionZ);
        for (ChunkPosition left : delta.left()) {
            RequestKey key = new RequestKey(delta.leftDimension(), left.x(), left.z());
            if (session.connection() instanceof BatchTransport batches) {
                var sent = stream.sentAwaitingAck.entrySet().iterator();
                while (sent.hasNext()) {
                    Map.Entry<Long, SentBatch> batch = sent.next();
                    if (!batch.getValue().key().equals(key) || !batches.cancelBatch(batch.getKey())) continue;
                    stream.sentAwaitingAckBytes -= batch.getValue().retainedBytes();
                    releaseBudgetLease(batch.getValue().budgetLeaseId());
                    sent.remove();
                }
            }
            /* Once this coordinate leaves interest, its immutable revision need no longer be
               pinned by replication accounting. Keep only the tiny ACK metadata for remote TCP
               frames which may already be on the wire. */
            for (Map.Entry<Long, SentBatch> batch : stream.sentAwaitingAck.entrySet()) {
                SentBatch sent = batch.getValue();
                if (!sent.key().equals(key)) continue;
                stream.sentAwaitingAckBytes -= sent.retainedBytes();
                releaseBudgetLease(sent.budgetLeaseId());
                batch.setValue(sent.withoutCreditsAndBudgetLease());
            }
            removeRetry(stream, key);
            stream.interestGenerations.remove(key);
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
        stream.priorityDimension = dimension;
        stream.priorityCenterX = centerChunkX;
        stream.priorityCenterZ = centerChunkZ;
        stream.priorityX = motionX;
        stream.priorityZ = motionZ;
    }

    public void tick(Map<String, PlayerSession> sessions) {
        long nowNanos = this.nanoTime.getAsLong();
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
                removeRetry(stream, result.key());
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
                    stream.readyBytes += snapshot.retainedBytes();
                }
            } else if (this.interests.tracks(result.sessionId(), result.key().dimension(),
                    result.key().x(), result.key().z())) {
                scheduleRetry(stream, result.key(), active.request(), active.attempt() + 1, nowNanos);
            }
        }
        for (Map.Entry<String, StreamState> entry : this.streams.entrySet()) {
            PlayerSession session = sessions.get(entry.getKey());
            if (session == null || session.state() != de.skyengine.shared.network.ConnectionState.PLAY) continue;
            StreamState stream = entry.getValue();
            if (expireAckTimeout(session, stream, nowNanos)) continue;
            promoteRetries(stream, entry.getKey(), nowNanos);
            if (stream.pendingView != null && session.send(stream.pendingView)) stream.pendingView = null;
            if (stream.pendingView != null) continue;
            int maxInFlight = maxInFlightSnapshots();
            long readyByteLimit = readyByteLimit();
            while (stream.inFlight.size() < maxInFlight && !stream.pending.isEmpty()
                    && stream.readyBytes + stream.sentAwaitingAckBytes
                    + (long) stream.inFlight.size() * ESTIMATED_SNAPSHOT_BYTES < readyByteLimit) {
                ChunkInterestManager.ChunkRequest request = stream.pending.poll();
                RequestKey key = new RequestKey(request.dimension(), request.chunkX(), request.chunkZ());
                if (stream.inFlight.containsKey(key)) continue;
                ChunkSnapshotTicket ticket = this.world.requestChunkSnapshot(
                        request.dimension(), request.chunkX(), request.chunkZ());
                DelayedRetry retry = stream.retryByKey.get(key);
                stream.inFlight.put(key, new ActiveRequest(request, ticket,
                        retry == null ? 0 : retry.attempt()));
                ticket.result().whenComplete((snapshot, failure) -> this.completed.add(new Completed(
                        entry.getKey(), key, ticket, snapshot == null ? Optional.empty() : snapshot, failure)));
            }
            while (!stream.ready.isEmpty()
                    && activeAwaitingAckCount(stream) < maxAwaitingAck()
                    && session.connection().outboundSize() < OUTBOUND_PACKET_HIGH_WATERMARK) {
                ReadySnapshot ready = stream.ready.poll();
                ChunkColumnSnapshot snapshot = ready.snapshot();
                stream.readyBytes -= snapshot.retainedBytes();
                if (!this.interests.tracks(entry.getKey(), snapshot.dimension(),
                        snapshot.chunkX(), snapshot.chunkZ())) continue;
                long batchId = stream.nextBatchId++;
                RequestKey key = new RequestKey(snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ());
                long leaseId = stream.interestGenerations.getOrDefault(key, -1L);
                if (leaseId < 0) continue;
                CorePackets.ChunkBatchStart start = new CorePackets.ChunkBatchStart(batchId, leaseId,
                        stream.viewEpoch,
                        snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ(), 1);
                CorePackets.ChunkColumnData data = new CorePackets.ChunkColumnData(batchId, snapshot);
                CorePackets.ChunkBatchEnd end = new CorePackets.ChunkBatchEnd(batchId);
                long retainedBytes = snapshot.retainedBytes();
                long budgetLeaseId = tryAcquireBudgetLease(entry.getKey(), snapshot, retainedBytes);
                if (this.world.replicationCacheBudget() != null && budgetLeaseId == 0) {
                    stream.ready.add(ready);
                    stream.readyBytes += retainedBytes;
                    stream.nextBatchId--;
                    break;
                }
                if (!(session.connection() instanceof BatchTransport batches)) {
                    releaseBudgetLease(budgetLeaseId);
                    stream.ready.add(ready);
                    stream.readyBytes += snapshot.retainedBytes();
                    stream.nextBatchId--;
                    session.connection().disconnect(DisconnectReason.INTERNAL_ERROR,
                            "Chunk streaming requires atomic batch transport");
                    break;
                }
                if (!batches.sendBatch(List.of(new PacketEnvelope(start), new PacketEnvelope(data),
                        new PacketEnvelope(end)))) {
                    releaseBudgetLease(budgetLeaseId);
                    stream.ready.add(ready);
                    stream.readyBytes += snapshot.retainedBytes();
                    stream.nextBatchId--;
                    break;
                }
                stream.sentAwaitingAck.put(batchId,
                        new SentBatch(key, snapshot.revision(), leaseId, retainedBytes, budgetLeaseId,
                                nowNanos));
                stream.sentAwaitingAckBytes += retainedBytes;
            }
        }
    }

    public void remove(PlayerSession session) {
        this.interests.remove(session.connection().id());
        StreamState removed = this.streams.remove(session.connection().id());
        if (removed != null) {
            removed.inFlight.values().forEach(active -> active.ticket().cancel());
            removed.sentAwaitingAck.values().forEach(sent -> releaseBudgetLease(sent.budgetLeaseId()));
            removed.retries.clear();
            removed.retryByKey.clear();
        }
    }

    public int trackedChunks(PlayerSession session) {
        return this.interests.trackedChunks(session.connection().id());
    }

    public StreamStats stats() {
        int pending = 0, inFlight = 0, retries = 0, ready = 0, awaitingAck = 0, applied = 0;
        long readyBytes = 0, awaitingAckBytes = 0;
        for (StreamState stream : this.streams.values()) {
            pending += stream.pending.size();
            inFlight += stream.inFlight.size();
            retries += stream.retryByKey.size();
            ready += stream.ready.size();
            awaitingAck += stream.sentAwaitingAck.size();
            applied += stream.applied.size();
            readyBytes += stream.readyBytes;
            awaitingAckBytes += stream.sentAwaitingAckBytes;
        }
        return new StreamStats(pending, inFlight, retries, ready, awaitingAck, applied,
                readyBytes, awaitingAckBytes, this.snapshotRetries, this.acknowledgementTimeouts,
                this.resyncRequests);
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
        stream.sentAwaitingAckBytes -= sent.retainedBytes();
        releaseBudgetLease(sent.budgetLeaseId());
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

    private int maxInFlightSnapshots() {
        return Math.max(64, this.world.workerStats().workers() * 4);
    }

    private int maxAwaitingAck() {
        return Math.max(64, this.world.workerStats().workers() * 2);
    }

    private long readyByteLimit() {
        return Math.max(MIN_READY_BYTES_PER_PLAYER,
                (long) Math.max(1, this.world.workerStats().workers()) * 2L * 1024 * 1024);
    }

    private static long retainedBytes(Iterable<ReadySnapshot> snapshots) {
        long bytes = 0;
        for (ReadySnapshot ready : snapshots) bytes += ready.snapshot().retainedBytes();
        return bytes;
    }

    private long tryAcquireBudgetLease(String owner, ChunkColumnSnapshot revision, long bytes) {
        ReplicationCacheBudget budget = this.world.replicationCacheBudget();
        return budget == null ? 0 : budget.tryAcquireLease(owner, revision, bytes);
    }

    private void releaseBudgetLease(long leaseId) {
        ReplicationCacheBudget budget = this.world.replicationCacheBudget();
        if (budget != null && leaseId != 0) budget.releaseLease(leaseId);
    }

    public void requestResync(PlayerSession session, String dimension, int chunkX, int chunkZ) {
        String sessionId = session.connection().id();
        if (!this.interests.tracks(sessionId, dimension, chunkX, chunkZ)) return;
        this.resyncRequests++;
        StreamState stream = this.streams.computeIfAbsent(sessionId, ignored -> new StreamState());
        stream.pending.removeIf(request -> request.dimension().equals(dimension)
                && request.chunkX() == chunkX && request.chunkZ() == chunkZ);
        stream.ready.removeIf(ready -> ready.snapshot().dimension().equals(dimension)
                && ready.snapshot().chunkX() == chunkX && ready.snapshot().chunkZ() == chunkZ);
        stream.readyBytes = retainedBytes(stream.ready);
        RequestKey key = new RequestKey(dimension, chunkX, chunkZ);
        removeRetry(stream, key);
        ActiveRequest active = stream.inFlight.remove(key);
        if (active != null) active.ticket().cancel();
        stream.applied.remove(key);
        stream.interestGenerations.put(key, stream.nextInterestGeneration++);
        for (Map.Entry<Long, SentBatch> batch : stream.sentAwaitingAck.entrySet()) {
            SentBatch sent = batch.getValue();
            if (!sent.key().equals(key)) continue;
            stream.sentAwaitingAckBytes -= sent.retainedBytes();
            releaseBudgetLease(sent.budgetLeaseId());
            batch.setValue(sent.withoutCreditsAndBudgetLease());
        }
        stream.pending.add(new ChunkInterestManager.ChunkRequest(dimension, chunkX, chunkZ, -1,
                Integer.MAX_VALUE));
    }

    private boolean expireAckTimeout(PlayerSession session, StreamState stream, long nowNanos) {
        boolean activeTransferExpired = false;
        var iterator = stream.sentAwaitingAck.entrySet().iterator();
        while (iterator.hasNext()) {
            SentBatch sent = iterator.next().getValue();
            if (nowNanos - sent.sentNanos() < this.ackTimeoutNanos) continue;
            if (sent.retainedBytes() == 0 && sent.budgetLeaseId() == 0) {
                // A no-longer-interested frame may have been written before cancellation.
                // Its tiny late-ACK tombstone expires silently and must not punish a healthy
                // fast-moving client which correctly discarded the obsolete view epoch.
                iterator.remove();
            } else {
                activeTransferExpired = true;
                break;
            }
        }
        if (!activeTransferExpired) return false;
        this.acknowledgementTimeouts++;
        stream.sentAwaitingAck.values().forEach(sent -> releaseBudgetLease(sent.budgetLeaseId()));
        stream.sentAwaitingAck.clear();
        stream.sentAwaitingAckBytes = 0;
        stream.inFlight.values().forEach(active -> active.ticket().cancel());
        stream.inFlight.clear();
        stream.pending.clear();
        stream.ready.clear();
        stream.readyBytes = 0;
        stream.retries.clear();
        stream.retryByKey.clear();
        session.connection().disconnect(DisconnectReason.TIMEOUT,
                "Chunk installation acknowledgement timed out");
        return true;
    }

    private void scheduleRetry(StreamState stream, RequestKey key,
                               ChunkInterestManager.ChunkRequest request, int attempt,
                               long nowNanos) {
        removeRetry(stream, key);
        int shift = Math.min(5, Math.max(0, attempt - 1));
        long delay = Math.min(2_000_000_000L, 50_000_000L << shift);
        DelayedRetry retry = new DelayedRetry(key, request, attempt, nowNanos + delay);
        this.snapshotRetries++;
        stream.retryByKey.put(key, retry);
        stream.retries.add(retry);
    }

    private void promoteRetries(StreamState stream, String sessionId, long nowNanos) {
        while (!stream.retries.isEmpty() && stream.retries.peek().readyNanos() <= nowNanos) {
            DelayedRetry retry = stream.retries.poll();
            if (stream.retryByKey.get(retry.key()) != retry) continue;
            if (!this.interests.tracks(sessionId, retry.key().dimension(),
                    retry.key().x(), retry.key().z())) {
                stream.retryByKey.remove(retry.key(), retry);
                continue;
            }
            stream.pending.add(retry.request());
        }
    }

    private static void removeRetry(StreamState stream, RequestKey key) {
        DelayedRetry retry = stream.retryByKey.remove(key);
        if (retry != null) stream.retries.remove(retry);
    }

    private static int activeAwaitingAckCount(StreamState stream) {
        int result = 0;
        for (SentBatch sent : stream.sentAwaitingAck.values()) {
            if (sent.retainedBytes() > 0) result++;
        }
        return result;
    }
}
