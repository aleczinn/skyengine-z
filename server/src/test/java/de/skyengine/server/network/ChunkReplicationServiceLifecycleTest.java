package de.skyengine.server.network;

import de.skyengine.server.world.ChunkReplicationService;
import de.skyengine.server.world.ChunkSnapshotTicket;
import de.skyengine.server.world.ReplicationCacheBudget;
import de.skyengine.server.world.ServerWorldRuntime;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.LocalTransport;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ImmutableChunkColumnData;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkReplicationServiceLifecycleTest {
    @Test
    void ackInterestExitAndLateAckReleaseExactlyOneLease() {
        AtomicLong clock = new AtomicLong(1);
        SnapshotRuntime runtime = new SnapshotRuntime(false);
        LocalTransport.Pair pair = playPair();
        PlayerSession session = new PlayerSession(pair.server(), clock.get());
        ChunkReplicationService service = new ChunkReplicationService(
                runtime, clock::get, 1_000_000_000L);
        Map<String, PlayerSession> sessions = Map.of(pair.server().id(), session);

        service.updateInterest(session, "skyengine:overworld", 0, 0, 0, 0, 0, 0);
        pumpSnapshot(service, sessions);
        CorePackets.ChunkBatchStart first = pollBatchStart(pair.client());
        assertNotNull(first);
        assertEquals(1, runtime.budget.metrics().activeSnapshotLeases());
        assertFalse(service.acknowledge(session, first.batchId(), first.leaseId() + 1));
        assertEquals(1, runtime.budget.metrics().activeSnapshotLeases());
        assertTrue(service.acknowledge(session, first.batchId(), first.leaseId()));
        assertEquals(0, runtime.budget.metrics().activeSnapshotLeases());
        assertTrue(service.isApplied(session, "skyengine:overworld", 0, 0));

        service.updateInterest(session, "skyengine:overworld", 1, 0, 0, 0, 0, 0);
        pumpSnapshot(service, sessions);
        CorePackets.ChunkBatchStart second = pollBatchStart(pair.client());
        assertNotNull(second);
        assertEquals(1, runtime.budget.metrics().activeSnapshotLeases());
        service.updateInterest(session, "skyengine:overworld", 2, 0, 0, 0, 0, 0);
        assertEquals(0, runtime.budget.metrics().activeSnapshotLeases(),
                "leaving interest returns credits before a late ACK arrives");
        assertTrue(service.acknowledge(session, second.batchId(), second.leaseId()));
        assertFalse(service.isApplied(session, "skyengine:overworld", 1, 0));
    }

    @Test
    void missingChunkAckTimesOutAndCannotPinAnOldRevisionForever() {
        AtomicLong clock = new AtomicLong(1);
        SnapshotRuntime runtime = new SnapshotRuntime(false);
        LocalTransport.Pair pair = playPair();
        PlayerSession session = new PlayerSession(pair.server(), clock.get());
        ChunkReplicationService service = new ChunkReplicationService(
                runtime, clock::get, 100);
        Map<String, PlayerSession> sessions = Map.of(pair.server().id(), session);

        service.updateInterest(session, "skyengine:overworld", 0, 0, 0, 0, 0, 0);
        pumpSnapshot(service, sessions);
        assertEquals(1, runtime.budget.metrics().activeSnapshotLeases());
        clock.addAndGet(101);
        service.tick(sessions);

        assertEquals(0, runtime.budget.metrics().activeSnapshotLeases());
        assertFalse(pair.server().open());
    }

    @Test
    void expiredLateAckTombstoneDoesNotDisconnectFastMovingClient() {
        AtomicLong clock = new AtomicLong(1);
        SnapshotRuntime runtime = new SnapshotRuntime(false);
        LocalTransport.Pair pair = playPair();
        PlayerSession session = new PlayerSession(pair.server(), clock.get());
        ChunkReplicationService service = new ChunkReplicationService(runtime, clock::get, 100);
        Map<String, PlayerSession> sessions = Map.of(pair.server().id(), session);

        service.updateInterest(session, "skyengine:overworld", 0, 0, 0, 0, 0, 0);
        pumpSnapshot(service, sessions);
        assertEquals(1, runtime.budget.metrics().activeSnapshotLeases());
        service.updateInterest(session, "skyengine:overworld", 10, 0, 0, 0, 0, 0);
        assertEquals(0, runtime.budget.metrics().activeSnapshotLeases());

        clock.addAndGet(101);
        service.tick(sessions);

        assertTrue(pair.server().open());
        assertEquals(0, service.stats().acknowledgementTimeouts());
    }

    @Test
    void transientEmptySnapshotIsRetriedInsteadOfCreatingAPermanentHole() {
        AtomicLong clock = new AtomicLong(1);
        SnapshotRuntime runtime = new SnapshotRuntime(true);
        LocalTransport.Pair pair = playPair();
        PlayerSession session = new PlayerSession(pair.server(), clock.get());
        ChunkReplicationService service = new ChunkReplicationService(
                runtime, clock::get, 1_000_000_000L);
        Map<String, PlayerSession> sessions = Map.of(pair.server().id(), session);

        service.updateInterest(session, "skyengine:overworld", 0, 0, 0, 0, 0, 0);
        service.tick(sessions); // first request -> Optional.empty
        service.tick(sessions); // completion -> delayed retry
        assertEquals(0, service.stats().awaitingAck());
        clock.addAndGet(50_000_001L);
        service.tick(sessions); // retry admitted
        service.tick(sessions); // successful completion sent

        assertTrue(runtime.requests.get() >= 2);
        assertNotNull(pollBatchStart(pair.client()));
        assertEquals(1, runtime.budget.metrics().activeSnapshotLeases());
        service.remove(session);
        assertEquals(0, runtime.budget.metrics().activeSnapshotLeases());
    }

    @Test
    void viewEpochReplacesPerChunkUnloadFloods() {
        AtomicLong clock = new AtomicLong(1);
        SnapshotRuntime runtime = new SnapshotRuntime(false);
        LocalTransport.Pair pair = playPair();
        PlayerSession session = new PlayerSession(pair.server(), clock.get());
        ChunkReplicationService service = new ChunkReplicationService(
                runtime, clock::get, 1_000_000_000L);
        Map<String, PlayerSession> sessions = Map.of(pair.server().id(), session);

        service.updateInterest(session, "skyengine:overworld", 0, 0, 1, 0, 0, 0);
        service.tick(sessions);
        while (pair.client().pollInbound() != null) { }

        service.updateInterest(session, "skyengine:overworld", 100, 100, 1, 0, 0, 0);
        service.tick(sessions);
        PacketEnvelope envelope;
        boolean receivedNewView = false;
        while ((envelope = pair.client().pollInbound()) != null) {
            receivedNewView |= envelope.packet() instanceof CorePackets.ChunkViewUpdate;
            assertFalse(envelope.packet() instanceof CorePackets.UnloadChunk,
                    "view changes must not emit one unload packet per departed chunk");
        }
        assertTrue(receivedNewView);
    }

    private static void pumpSnapshot(ChunkReplicationService service,
                                     Map<String, PlayerSession> sessions) {
        service.tick(sessions);
        service.tick(sessions);
    }

    private static CorePackets.ChunkBatchStart pollBatchStart(TransportConnection connection) {
        PacketEnvelope packet;
        while ((packet = connection.pollInbound()) != null) {
            if (packet.packet() instanceof CorePackets.ChunkBatchStart start) return start;
        }
        return null;
    }

    private static LocalTransport.Pair playPair() {
        LocalTransport.Pair pair = LocalTransport.create();
        advanceToPlay(pair.server());
        advanceToPlay(pair.client());
        return pair;
    }

    private static void advanceToPlay(TransportConnection connection) {
        connection.transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
        connection.transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
        connection.transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
        connection.transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
    }

    private static final class SnapshotRuntime implements ServerWorldRuntime {
        final ReplicationCacheBudget budget = new ReplicationCacheBudget(4 * 1024 * 1024);
        final AtomicInteger requests = new AtomicInteger();
        final boolean emptyFirst;

        SnapshotRuntime(boolean emptyFirst) { this.emptyFirst = emptyFirst; }

        @Override public Path directory() { return Path.of("."); }
        @Override public void tick(long serverTick) { }
        @Override public void autosave(long serverTick) { }
        @Override public WorkerStats workerStats() { return new WorkerStats(2, 0, 0); }
        @Override public ReplicationCacheBudget replicationCacheBudget() { return this.budget; }
        @Override public ChunkSnapshotTicket requestChunkSnapshot(String dimension, int chunkX, int chunkZ) {
            int request = this.requests.incrementAndGet();
            if (this.emptyFirst && request == 1) return ChunkSnapshotTicket.completed(Optional.empty());
            return ChunkSnapshotTicket.completed(Optional.of(emptyChunk(dimension, chunkX, chunkZ)));
        }
        @Override public void close() { }
    }

    private static ChunkColumnSnapshot emptyChunk(String dimension, int chunkX, int chunkZ) {
        int[] column = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        int[] tint = new int[ChunkColumnSnapshot.TINT_CORNERS];
        return ImmutableChunkColumnData.takeOwnership(dimension, chunkX, chunkZ, 1, List.of(),
                column, tint, tint.clone(), column.clone(), List.of());
    }
}
