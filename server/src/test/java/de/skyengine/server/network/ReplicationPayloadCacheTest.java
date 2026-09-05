package de.skyengine.server.network;

import de.skyengine.server.world.ReplicationCacheBudget;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ImmutableChunkColumnData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplicationPayloadCacheTest {
    @Test
    void thirtyTwoConcurrentRecipientsCoalesceEncodingAndCompression() throws Exception {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(8 * 1024 * 1024);
        try (ReplicationPayloadCache cache = new ReplicationPayloadCache(budget);
             var executor = Executors.newFixedThreadPool(8)) {
            int[] column = new int[ChunkColumnSnapshot.COLUMN_CELLS];
            int[] tint = new int[ChunkColumnSnapshot.TINT_CORNERS];
            ImmutableChunkColumnData snapshot = ImmutableChunkColumnData.takeOwnership(
                    "skyengine:overworld", 4, -7, 42, List.of(), column, tint,
                    tint.clone(), column.clone(), List.of());

            @SuppressWarnings("unchecked")
            CompletableFuture<ReplicationPayloadCache.PreparedPayload>[] recipients =
                    new CompletableFuture[32];
            for (int i = 0; i < recipients.length; i++) {
                recipients[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        return cache.prepared(snapshot, "zstd", 0, 8 * 1024 * 1024, 3);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                }, executor);
            }
            CompletableFuture.allOf(recipients).join();
            ReplicationPayloadCache.PreparedPayload first = recipients[0].join();
            for (CompletableFuture<ReplicationPayloadCache.PreparedPayload> recipient : recipients) {
                assertSame(first, recipient.join());
            }

            assertEquals(1, cache.metrics().creates(), "logical revision must be encoded once");
            assertEquals(1, cache.metrics().compressionCreates(), "wire profile must be compressed once");
            assertEquals(31, cache.metrics().hits());
            assertEquals(31, cache.metrics().compressionHits());
        }
        assertEquals(0, budget.metrics().replicationCacheBytesTotal());
    }

    @Test
    void overlappingRecipientsReuseOneImmutableEncoding() throws Exception {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(1024 * 1024);
        try (ReplicationPayloadCache cache = new ReplicationPayloadCache(budget)) {
            int[] column = new int[ChunkColumnSnapshot.COLUMN_CELLS];
            int[] tint = new int[ChunkColumnSnapshot.TINT_CORNERS];
            ImmutableChunkColumnData snapshot = ImmutableChunkColumnData.takeOwnership(
                    "skyengine:overworld", 0, 0, 1, List.of(), column, tint,
                    tint.clone(), column.clone(), List.of());

            ReplicationPayloadCache.EncodedPayload first = cache.encoded(snapshot);
            ReplicationPayloadCache.EncodedPayload second = cache.encoded(snapshot);

            assertSame(first, second, "cached payload bytes are immutable and shared");
            assertEquals(1, cache.metrics().creates());
            assertEquals(1, cache.metrics().hits());
            assertTrue(budget.metrics().encodedCacheBytes() > 0);
            assertTrue(budget.trafficMetrics().wireBytesProduced() > 0);
            assertTrue(budget.trafficMetrics().encodedBytesSaved() > 0);
        }
        assertEquals(0, budget.metrics().encodedCacheBytes());
    }

    @Test
    void overlappingRecipientsReuseOneWholeSnapshotCompression() throws Exception {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(4 * 1024 * 1024);
        try (ReplicationPayloadCache cache = new ReplicationPayloadCache(budget)) {
            int[] column = new int[ChunkColumnSnapshot.COLUMN_CELLS];
            int[] tint = new int[ChunkColumnSnapshot.TINT_CORNERS];
            ImmutableChunkColumnData snapshot = ImmutableChunkColumnData.takeOwnership(
                    "skyengine:overworld", 0, 0, 7, List.of(), column, tint,
                    tint.clone(), column.clone(), List.of());

            ReplicationPayloadCache.PreparedPayload first = cache.prepared(
                    snapshot, "zstd", 0, 8 * 1024 * 1024, 3);
            ReplicationPayloadCache.PreparedPayload second = cache.prepared(
                    snapshot, "zstd", 0, 8 * 1024 * 1024, 3);

            assertSame(first, second);
            assertTrue(first.decodedLength() > first.payload().length());
            assertEquals(1, cache.metrics().compressionCreates());
            assertEquals(1, cache.metrics().compressionHits());
            assertTrue(budget.metrics().compressedCacheBytes() > 0);
            assertTrue(budget.trafficMetrics().bytesCopied() > 0);
            assertTrue(budget.trafficMetrics().compressedBytesSaved() > 0);
        }
        assertEquals(0, budget.metrics().replicationCacheBytesTotal());
    }
}
