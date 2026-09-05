package de.skyengine.server.network;

import de.skyengine.server.world.ReplicationCacheBudget;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ImmutableByteArray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.LongAdder;
import com.github.luben.zstd.Zstd;
import de.skyengine.graphics.PerformanceProfiler;

/** Shared server-wide canonical wire encoding cache; never exposes mutable send cursors. */
final class ReplicationPayloadCache implements AutoCloseable {
    static final class EncodedPayload {
        private final ImmutableByteArray bytes;
        private EncodedPayload(byte[] bytes) {
            this.bytes = ImmutableByteArray.takeOwnership(bytes);
        }
        int length() { return this.bytes.length(); }
        byte[] copy() { return this.bytes.copy(); }
        ImmutableByteArray slice(int offset, int length) { return this.bytes.slice(offset, length); }
    }
    record PreparedPayload(EncodedPayload payload, int decodedLength) { }
    record Metrics(long requests, long creates, long hits, long reuseCount,
                   long bytesSaved, long encodeTimeSavedNanos,
                   long compressionRequests, long compressionCreates, long compressionHits,
                   long compressionTimeSavedNanos) { }
    private record Key(ChunkColumnSnapshot revision) { }
    private record CompressionKey(ChunkColumnSnapshot revision, String algorithm,
                                  int threshold, int maximumBytes, int level) { }
    private record Entry(EncodedPayload payload, long encodeNanos) { }
    private record CompressedEntry(PreparedPayload payload, long compressionNanos) { }

    private final ReplicationCacheBudget budget;
    private final Map<Key, Entry> cache = new LinkedHashMap<>(128, 0.75F, true);
    private final Map<CompressionKey, CompressedEntry> compressedCache =
            new LinkedHashMap<>(128, 0.75F, true);
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompressionKey, CompletableFuture<CompressedEntry>> compressionInFlight =
            new ConcurrentHashMap<>();
    private final LongAdder requests = new LongAdder();
    private final LongAdder creates = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder bytesSaved = new LongAdder();
    private final LongAdder encodeTimeSaved = new LongAdder();
    private final LongAdder compressionRequests = new LongAdder();
    private final LongAdder compressionCreates = new LongAdder();
    private final LongAdder compressionHits = new LongAdder();
    private final LongAdder compressionTimeSaved = new LongAdder();

    ReplicationPayloadCache(ReplicationCacheBudget budget) {
        this.budget = budget;
    }

    EncodedPayload encoded(ChunkColumnSnapshot snapshot) throws ProtocolException {
        Key key = new Key(snapshot);
        this.requests.increment();
        Entry cached;
        synchronized (this.cache) { cached = this.cache.get(key); }
        if (cached != null) {
            recordHit(cached);
            return cached.payload();
        }
        CompletableFuture<Entry> created = new CompletableFuture<>();
        CompletableFuture<Entry> existing = this.inFlight.putIfAbsent(key, created);
        if (existing != null) {
            try {
                Entry shared = existing.join();
                recordHit(shared);
                return shared.payload();
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof ProtocolException protocol) throw protocol;
                throw new ProtocolException("Shared chunk encoding failed", cause);
            }
        }
        long started = System.nanoTime();
        try {
            Entry value = new Entry(new EncodedPayload(CoreProtocol.encodeChunkSnapshot(snapshot)),
                    System.nanoTime() - started);
            PerformanceProfiler.get().record(PerformanceProfiler.WorkerSection.L0_WIRE_ENCODE,
                    value.encodeNanos());
            this.creates.increment();
            if (this.budget != null) {
                this.budget.created(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD);
                this.budget.wireProduced(value.payload().length());
            }
            retain(key, value);
            created.complete(value);
            return value.payload();
        } catch (ProtocolException | RuntimeException failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            this.inFlight.remove(key, created);
        }
    }

    PreparedPayload prepared(ChunkColumnSnapshot snapshot, String algorithm, int threshold,
                             int maximumBytes, int level) throws ProtocolException {
        EncodedPayload encoded = encoded(snapshot);
        if (encoded.length() > maximumBytes) {
            throw new ProtocolException("Chunk payload exceeds negotiated decompression limit");
        }
        if (!"zstd".equals(algorithm) || encoded.length() < threshold) {
            return new PreparedPayload(encoded, 0);
        }
        CompressionKey key = new CompressionKey(snapshot, algorithm, threshold, maximumBytes, level);
        this.compressionRequests.increment();
        CompressedEntry cached;
        synchronized (this.compressedCache) { cached = this.compressedCache.get(key); }
        if (cached != null) {
            recordCompressionHit(cached);
            return cached.payload();
        }
        CompletableFuture<CompressedEntry> created = new CompletableFuture<>();
        CompletableFuture<CompressedEntry> existing = this.compressionInFlight.putIfAbsent(key, created);
        if (existing != null) {
            try {
                CompressedEntry shared = existing.join();
                recordCompressionHit(shared);
                return shared.payload();
            } catch (CompletionException failure) {
                throw new ProtocolException("Shared chunk compression failed", failure.getCause());
            }
        }
        if (this.budget != null) {
            this.budget.request(ReplicationCacheBudget.Layer.COMPRESSED_PAYLOAD, false);
        }
        long started = System.nanoTime();
        try {
            byte[] source = encoded.copy();
            if (this.budget != null) this.budget.copied(source.length);
            byte[] compressed = Zstd.compress(source, level);
            PreparedPayload payload = compressed.length < source.length
                    ? new PreparedPayload(new EncodedPayload(compressed), source.length)
                    : new PreparedPayload(encoded, 0);
            CompressedEntry value = new CompressedEntry(payload, System.nanoTime() - started);
            PerformanceProfiler.get().record(PerformanceProfiler.WorkerSection.L0_WIRE_COMPRESSION,
                    value.compressionNanos());
            this.compressionCreates.increment();
            if (this.budget != null) {
                this.budget.created(ReplicationCacheBudget.Layer.COMPRESSED_PAYLOAD);
                if (payload.decodedLength() != 0) this.budget.wireProduced(payload.payload().length());
            }
            if (payload.decodedLength() != 0) retainCompressed(key, value);
            created.complete(value);
            return payload;
        } catch (RuntimeException failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            this.compressionInFlight.remove(key, created);
        }
    }

    private void recordHit(Entry entry) {
        this.hits.increment();
        this.bytesSaved.add(entry.payload().length());
        this.encodeTimeSaved.add(entry.encodeNanos());
        if (this.budget != null) {
            this.budget.request(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD, true);
            this.budget.encodedReuseSaved(entry.payload().length());
        }
    }

    private void retain(Key key, Entry value) {
        if (this.budget == null) return;
        this.budget.request(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD, false);
        long bytes = value.payload().length();
        synchronized (this.cache) {
            while (!this.budget.reserve(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD, bytes)) {
                var iterator = this.cache.entrySet().iterator();
                if (!iterator.hasNext()) return;
                Entry evicted = iterator.next().getValue();
                iterator.remove();
                this.budget.release(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD,
                        evicted.payload().length());
                this.budget.eviction();
            }
            this.cache.put(key, value);
        }
    }

    private void recordCompressionHit(CompressedEntry entry) {
        this.compressionHits.increment();
        this.compressionTimeSaved.add(entry.compressionNanos());
        if (this.budget != null) {
            this.budget.request(ReplicationCacheBudget.Layer.COMPRESSED_PAYLOAD, true);
            this.budget.compressedReuseSaved(entry.payload().payload().length());
        }
    }

    private void retainCompressed(CompressionKey key, CompressedEntry value) {
        if (this.budget == null) return;
        long bytes = value.payload().payload().length();
        synchronized (this.compressedCache) {
            while (!this.budget.reserve(ReplicationCacheBudget.Layer.COMPRESSED_PAYLOAD, bytes)) {
                var iterator = this.compressedCache.entrySet().iterator();
                if (!iterator.hasNext()) return;
                CompressedEntry evicted = iterator.next().getValue();
                iterator.remove();
                this.budget.release(ReplicationCacheBudget.Layer.COMPRESSED_PAYLOAD,
                        evicted.payload().payload().length());
                this.budget.eviction();
            }
            this.compressedCache.put(key, value);
        }
    }

    Metrics metrics() {
        return new Metrics(this.requests.sum(), this.creates.sum(), this.hits.sum(), this.hits.sum(),
                this.bytesSaved.sum(), this.encodeTimeSaved.sum(), this.compressionRequests.sum(),
                this.compressionCreates.sum(), this.compressionHits.sum(), this.compressionTimeSaved.sum());
    }

    @Override public void close() {
        synchronized (this.cache) {
            long bytes = 0;
            for (Entry entry : this.cache.values()) bytes += entry.payload().length();
            this.cache.clear();
            if (this.budget != null) {
                this.budget.release(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD, bytes);
            }
        }
        synchronized (this.compressedCache) {
            long bytes = 0;
            for (CompressedEntry entry : this.compressedCache.values()) {
                bytes += entry.payload().payload().length();
            }
            this.compressedCache.clear();
            if (this.budget != null) {
                this.budget.release(ReplicationCacheBudget.Layer.COMPRESSED_PAYLOAD, bytes);
            }
        }
    }
}
