package de.skyengine.server.world;

import de.skyengine.shared.entity.NetworkEntitySnapshot;
import de.skyengine.shared.world.ChunkPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chunk-bucketed entity index; player diffs inspect interested buckets, never every world entity. */
public final class EntityInterestIndex {
    public record EntityDelta(List<NetworkEntitySnapshot> entered, List<Integer> left) {
        public EntityDelta { entered = List.copyOf(entered); left = List.copyOf(left); }
    }
    private record BucketKey(String dimension, long chunk) {}
    private record Location(BucketKey bucket, NetworkEntitySnapshot snapshot) {}
    public record TrackingChange(boolean created, List<String> enteredSessions,
                                 List<String> retainedSessions, List<String> leftSessions) {
        public TrackingChange {
            enteredSessions = List.copyOf(enteredSessions);
            retainedSessions = List.copyOf(retainedSessions);
            leftSessions = List.copyOf(leftSessions);
        }
    }

    private final Map<BucketKey, Set<Integer>> buckets = new HashMap<>();
    private final Map<Integer, Location> entities = new HashMap<>();
    private final Map<String, Set<Integer>> trackedBySession = new HashMap<>();
    private final Map<Integer, Set<String>> sessionsByEntity = new HashMap<>();
    private final Map<BucketKey, Set<String>> sessionsByBucket = new HashMap<>();
    private final Map<String, Set<BucketKey>> bucketsBySession = new HashMap<>();

    public TrackingChange upsert(NetworkEntitySnapshot snapshot) {
        BucketKey nextBucket = bucket(snapshot.dimension(), snapshot.x(), snapshot.z());
        Location previous = this.entities.put(snapshot.networkId(), new Location(nextBucket, snapshot));
        if (previous != null && !previous.bucket().equals(nextBucket)) removeFromBucket(previous.bucket(), snapshot.networkId());
        this.buckets.computeIfAbsent(nextBucket, ignored -> new HashSet<>()).add(snapshot.networkId());

        Set<String> previousSessions = this.sessionsByEntity.get(snapshot.networkId());
        if (previousSessions == null) previousSessions = Set.of();
        Set<String> nextSessions = new HashSet<>(this.sessionsByBucket.getOrDefault(nextBucket, Set.of()));
        List<String> entered = new ArrayList<>();
        List<String> retained = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (String session : nextSessions) {
            if (previousSessions.contains(session)) retained.add(session);
            else {
                entered.add(session);
                this.trackedBySession.computeIfAbsent(session, ignored -> new HashSet<>()).add(snapshot.networkId());
            }
        }
        for (String session : previousSessions) {
            if (!nextSessions.contains(session)) {
                left.add(session);
                Set<Integer> tracked = this.trackedBySession.get(session);
                if (tracked != null) tracked.remove(snapshot.networkId());
            }
        }
        if (nextSessions.isEmpty()) this.sessionsByEntity.remove(snapshot.networkId());
        else this.sessionsByEntity.put(snapshot.networkId(), nextSessions);
        entered.sort(String::compareTo); retained.sort(String::compareTo); left.sort(String::compareTo);
        return new TrackingChange(previous == null, entered, retained, left);
    }

    public NetworkEntitySnapshot remove(int networkId) {
        Location removed = this.entities.remove(networkId);
        if (removed == null) return null;
        removeFromBucket(removed.bucket(), networkId);
        for (Set<Integer> tracked : this.trackedBySession.values()) tracked.remove(networkId);
        this.sessionsByEntity.remove(networkId);
        return removed.snapshot();
    }

    public EntityDelta updateInterest(String sessionId, String dimension, int centerChunkX, int centerChunkZ,
                                      int viewDistance) {
        Set<Integer> next = new HashSet<>();
        Set<BucketKey> nextBuckets = new HashSet<>();
        for (int dz = -viewDistance; dz <= viewDistance; dz++) {
            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                BucketKey bucketKey = new BucketKey(dimension,
                        ChunkPosition.pack(centerChunkX + dx, centerChunkZ + dz));
                nextBuckets.add(bucketKey);
                Set<Integer> bucket = this.buckets.get(bucketKey);
                if (bucket != null) next.addAll(bucket);
            }
        }
        updateSessionBuckets(sessionId, nextBuckets);
        Set<Integer> previous = this.trackedBySession.put(sessionId, next);
        if (previous == null) previous = Set.of();
        List<NetworkEntitySnapshot> entered = new ArrayList<>();
        for (int id : next) if (!previous.contains(id)) {
            entered.add(this.entities.get(id).snapshot());
            this.sessionsByEntity.computeIfAbsent(id, ignored -> new HashSet<>()).add(sessionId);
        }
        List<Integer> left = new ArrayList<>();
        for (int id : previous) if (!next.contains(id)) {
            left.add(id);
            Set<String> sessions = this.sessionsByEntity.get(id);
            if (sessions != null && sessions.remove(sessionId) && sessions.isEmpty()) this.sessionsByEntity.remove(id);
        }
        entered.sort(java.util.Comparator.comparingInt(NetworkEntitySnapshot::networkId));
        left.sort(Integer::compareTo);
        return new EntityDelta(entered, left);
    }

    public List<String> trackingSessions(int networkId) {
        Set<String> sessions = this.sessionsByEntity.get(networkId);
        return sessions == null ? List.of() : List.copyOf(sessions);
    }

    public void removeSession(String sessionId) {
        Set<Integer> tracked = this.trackedBySession.remove(sessionId);
        if (tracked != null) for (int id : tracked) {
                Set<String> sessions = this.sessionsByEntity.get(id);
                if (sessions != null && sessions.remove(sessionId) && sessions.isEmpty()) this.sessionsByEntity.remove(id);
            }
        Set<BucketKey> sessionBuckets = this.bucketsBySession.remove(sessionId);
        if (sessionBuckets != null) for (BucketKey bucket : sessionBuckets) {
            Set<String> sessions = this.sessionsByBucket.get(bucket);
            if (sessions != null && sessions.remove(sessionId) && sessions.isEmpty()) {
                this.sessionsByBucket.remove(bucket);
            }
        }
    }
    public int entityCount() { return this.entities.size(); }

    private void removeFromBucket(BucketKey bucket, int id) {
        Set<Integer> entities = this.buckets.get(bucket);
        if (entities != null && entities.remove(id) && entities.isEmpty()) this.buckets.remove(bucket);
    }

    private void updateSessionBuckets(String sessionId, Set<BucketKey> next) {
        Set<BucketKey> previous = this.bucketsBySession.put(sessionId, next);
        if (previous == null) previous = Set.of();
        for (BucketKey bucket : next) if (!previous.contains(bucket)) {
            this.sessionsByBucket.computeIfAbsent(bucket, ignored -> new HashSet<>()).add(sessionId);
        }
        for (BucketKey bucket : previous) if (!next.contains(bucket)) {
            Set<String> sessions = this.sessionsByBucket.get(bucket);
            if (sessions != null && sessions.remove(sessionId) && sessions.isEmpty()) {
                this.sessionsByBucket.remove(bucket);
            }
        }
    }

    private static BucketKey bucket(String dimension, double x, double z) {
        int chunkX = Math.floorDiv((int) Math.floor(x), 32);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 32);
        return new BucketKey(dimension, ChunkPosition.pack(chunkX, chunkZ));
    }
}
