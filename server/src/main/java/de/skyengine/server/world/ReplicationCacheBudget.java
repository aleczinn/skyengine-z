package de.skyengine.server.world;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** One byte budget shared by logical snapshots and every remote wire-cache layer. */
public final class ReplicationCacheBudget {
    public enum Layer { LOGICAL_SNAPSHOT, ENCODED_PAYLOAD, COMPRESSED_PAYLOAD }
    public record Metrics(long limitBytes, long logicalSnapshotBytes, long encodedCacheBytes,
                          long compressedCacheBytes, long replicationCacheBytesTotal,
                          long cacheEvictions, long cachePinnedBytes, long activeSnapshotLeases,
                          long oldestLeaseAgeNanos, long pinnedRevisionCount,
                          long snapshotRequests, long snapshotCacheHits,
                          long snapshotCreates, long encodeRequests, long encodeCacheHits,
                          long encodeCreates, long compressionRequests, long compressionCacheHits,
                          long compressionCreates) { }
    public record TrafficMetrics(long snapshotBytesAllocated, long bytesCopied,
                                 long wireBytesProduced, long directBufferBytes,
                                 long encodedBytesSaved, long compressedBytesSaved) { }
    private record Lease(long bytes, long acquiredNanos, Object revision, String owner) { }
    /** Development-only diagnostic entry; it deliberately does not expose the revision object. */
    public record LeaseDebugInfo(long id, String owner, long bytes, long ageNanos) { }
    private static final class PinnedRevision {
        final long bytes;
        int references;
        PinnedRevision(long bytes) { this.bytes = bytes; this.references = 1; }
    }

    private final long limitBytes;
    private final EnumMap<Layer, Long> bytes = new EnumMap<>(Layer.class);
    private final EnumMap<Layer, Long> requests = new EnumMap<>(Layer.class);
    private final EnumMap<Layer, Long> hits = new EnumMap<>(Layer.class);
    private final EnumMap<Layer, Long> creates = new EnumMap<>(Layer.class);
    private final Map<Long, Lease> leases = new HashMap<>();
    private final java.util.IdentityHashMap<Object, PinnedRevision> pinnedRevisions =
            new java.util.IdentityHashMap<>();
    private final AtomicLong leaseIds = new AtomicLong();
    private long totalBytes;
    private long evictions;
    private long pinnedBytes;
    private long snapshotBytesAllocated;
    private long bytesCopied;
    private long wireBytesProduced;
    private long directBufferBytes;
    private long encodedBytesSaved;
    private long compressedBytesSaved;

    public ReplicationCacheBudget(long limitBytes) {
        if (limitBytes < 0) throw new IllegalArgumentException("Negative replication cache budget");
        this.limitBytes = limitBytes;
        for (Layer layer : Layer.values()) {
            this.bytes.put(layer, 0L);
            this.requests.put(layer, 0L);
            this.hits.put(layer, 0L);
            this.creates.put(layer, 0L);
        }
    }

    public synchronized boolean reserve(Layer layer, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative cache reservation");
        if (amount > this.limitBytes - this.totalBytes) return false;
        this.bytes.put(layer, this.bytes.get(layer) + amount);
        this.totalBytes += amount;
        return true;
    }

    public synchronized void release(Layer layer, long amount) {
        long released = Math.min(Math.max(0, amount), this.bytes.get(layer));
        this.bytes.put(layer, this.bytes.get(layer) - released);
        this.totalBytes -= released;
    }

    public synchronized void request(Layer layer, boolean hit) {
        this.requests.put(layer, this.requests.get(layer) + 1);
        if (hit) this.hits.put(layer, this.hits.get(layer) + 1);
    }

    public synchronized void eviction() { this.evictions++; }
    public synchronized void created(Layer layer) {
        this.creates.put(layer, this.creates.get(layer) + 1);
    }

    /** Tracks transfer ownership. Heap references remain safe even when the cache evicts. */
    public synchronized long acquireLease(long retainedBytes) {
        return acquireLease("unspecified", null, retainedBytes);
    }

    /** Tracks one transfer owner and the immutable revision keeping its storage alive. */
    public synchronized long acquireLease(String owner, Object revision, long retainedBytes) {
        return acquireLeaseUnchecked(owner, revision, retainedBytes);
    }

    /**
     * Bounded transfer admission before an expensive snapshot is put on a transport queue.
     * Another recipient of the same immutable revision adds a lease but no physical pin bytes.
     */
    public synchronized long tryAcquireLease(String owner, Object revision, long retainedBytes) {
        long bytes = Math.max(0, retainedBytes);
        boolean alreadyPinned = revision != null && this.pinnedRevisions.containsKey(revision);
        if (!alreadyPinned && bytes > this.limitBytes - this.pinnedBytes) return 0;
        return acquireLeaseUnchecked(owner, revision, bytes);
    }

    private long acquireLeaseUnchecked(String owner, Object revision, long retainedBytes) {
        long id = this.leaseIds.incrementAndGet();
        long bytes = Math.max(0, retainedBytes);
        this.leases.put(id, new Lease(bytes, System.nanoTime(), revision,
                owner == null ? "unspecified" : owner));
        if (revision == null) {
            this.pinnedBytes += bytes;
        } else {
            PinnedRevision pinned = this.pinnedRevisions.get(revision);
            if (pinned == null) {
                this.pinnedRevisions.put(revision, new PinnedRevision(bytes));
                this.pinnedBytes += bytes;
            } else {
                pinned.references++;
            }
        }
        return id;
    }

    public synchronized void releaseLease(long id) {
        Lease lease = this.leases.remove(id);
        if (lease == null) return;
        if (lease.revision() == null) {
            this.pinnedBytes -= lease.bytes();
            return;
        }
        PinnedRevision pinned = this.pinnedRevisions.get(lease.revision());
        if (pinned == null) return;
        if (--pinned.references == 0) {
            this.pinnedRevisions.remove(lease.revision());
            this.pinnedBytes -= pinned.bytes;
        }
    }

    public synchronized Metrics metrics() {
        long oldest = 0;
        long now = System.nanoTime();
        for (Lease lease : this.leases.values()) oldest = Math.max(oldest, now - lease.acquiredNanos());
        long pinnedRevisionCount = this.pinnedRevisions.size();
        return new Metrics(this.limitBytes, this.bytes.get(Layer.LOGICAL_SNAPSHOT),
                this.bytes.get(Layer.ENCODED_PAYLOAD), this.bytes.get(Layer.COMPRESSED_PAYLOAD),
                this.totalBytes, this.evictions, this.pinnedBytes, this.leases.size(), oldest,
                pinnedRevisionCount, this.requests.get(Layer.LOGICAL_SNAPSHOT),
                this.hits.get(Layer.LOGICAL_SNAPSHOT), this.creates.get(Layer.LOGICAL_SNAPSHOT),
                this.requests.get(Layer.ENCODED_PAYLOAD), this.hits.get(Layer.ENCODED_PAYLOAD),
                this.creates.get(Layer.ENCODED_PAYLOAD), this.requests.get(Layer.COMPRESSED_PAYLOAD),
                this.hits.get(Layer.COMPRESSED_PAYLOAD), this.creates.get(Layer.COMPRESSED_PAYLOAD));
    }

    /** Snapshot of live owners used by diagnostics without extending any revision lifetime. */
    public synchronized java.util.List<LeaseDebugInfo> activeLeases() {
        long now = System.nanoTime();
        return this.leases.entrySet().stream()
                .map(entry -> new LeaseDebugInfo(entry.getKey(), entry.getValue().owner(),
                        entry.getValue().bytes(), Math.max(0, now - entry.getValue().acquiredNanos())))
                .sorted(java.util.Comparator.comparingLong(LeaseDebugInfo::ageNanos).reversed())
                .toList();
    }

    /** Optional development assertion. Production shutdown never pays for owner tracking output. */
    public synchronized void assertNoActiveLeases() {
        if (this.leases.isEmpty()) return;
        String owners = activeLeases().stream().limit(8)
                .map(lease -> lease.owner() + "=" + lease.bytes() + "B/"
                        + (lease.ageNanos() / 1_000_000) + "ms")
                .collect(java.util.stream.Collectors.joining(", "));
        throw new IllegalStateException("Replication snapshot leases still active: "
                + this.leases.size() + " [" + owners + "]");
    }

    public synchronized long bytes(Layer layer) { return this.bytes.get(layer); }
    public synchronized void snapshotAllocated(long amount) {
        this.snapshotBytesAllocated += Math.max(0, amount);
    }
    public synchronized void copied(long amount) { this.bytesCopied += Math.max(0, amount); }
    public synchronized void wireProduced(long amount) { this.wireBytesProduced += Math.max(0, amount); }
    public synchronized void directBufferBytes(long amount) {
        this.directBufferBytes = Math.max(0, amount);
    }
    public synchronized void encodedReuseSaved(long amount) {
        this.encodedBytesSaved += Math.max(0, amount);
    }
    public synchronized void compressedReuseSaved(long amount) {
        this.compressedBytesSaved += Math.max(0, amount);
    }
    public synchronized TrafficMetrics trafficMetrics() {
        return new TrafficMetrics(this.snapshotBytesAllocated, this.bytesCopied,
                this.wireBytesProduced, this.directBufferBytes,
                this.encodedBytesSaved, this.compressedBytesSaved);
    }
    public long limitBytes() { return this.limitBytes; }
}
