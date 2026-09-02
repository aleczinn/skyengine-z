package de.skyengine.server.world;

import de.skyengine.shared.world.ChunkPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-session L0 interest diff; its work is bounded by the configured view area, never world size. */
public final class ChunkInterestManager {
    public record ChunkRequest(String dimension, int chunkX, int chunkZ, int distanceSquared,
                               int forwardScore) {}
    public record InterestDelta(List<ChunkRequest> entered, String leftDimension, List<ChunkPosition> left) {
        public InterestDelta { entered = List.copyOf(entered); left = List.copyOf(left); }
    }

    private static final class View {
        String dimension;
        LongHashSet chunks = new LongHashSet(16);
    }

    private final Map<String, View> views = new HashMap<>();

    public InterestDelta update(String sessionId, String dimension, int centerX, int centerZ,
                                int viewDistance, float motionX, float motionZ) {
        if (viewDistance < 0 || viewDistance > 32) throw new IllegalArgumentException("Invalid view distance");
        View old = this.views.computeIfAbsent(sessionId, ignored -> new View());
        LongHashSet next = new LongHashSet((viewDistance * 2 + 1) * (viewDistance * 2 + 1));
        List<ChunkRequest> entered = new ArrayList<>();
        boolean sameDimension = dimension.equals(old.dimension);
        for (int dz = -viewDistance; dz <= viewDistance; dz++) {
            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                int chunkX = centerX + dx, chunkZ = centerZ + dz;
                long key = ChunkPosition.pack(chunkX, chunkZ);
                next.add(key);
                if (!sameDimension || !old.chunks.contains(key)) {
                    int forward = Math.round((dx * motionX + dz * motionZ) * 1024.0f);
                    entered.add(new ChunkRequest(dimension, chunkX, chunkZ, dx * dx + dz * dz, forward));
                }
            }
        }
        List<ChunkPosition> left = new ArrayList<>();
        old.chunks.forEach(key -> {
            if (!sameDimension || !next.contains(key)) left.add(ChunkPosition.unpack(key));
        });
        entered.sort(Comparator.comparingInt(ChunkRequest::distanceSquared)
                .thenComparing(Comparator.comparingInt(ChunkRequest::forwardScore).reversed())
                .thenComparingInt(ChunkRequest::chunkZ).thenComparingInt(ChunkRequest::chunkX));
        String leftDimension = old.dimension;
        old.dimension = dimension;
        old.chunks = next;
        return new InterestDelta(entered, leftDimension, left);
    }

    public List<ChunkPosition> remove(String sessionId) {
        View view = this.views.remove(sessionId);
        if (view == null) return List.of();
        List<ChunkPosition> result = new ArrayList<>(view.chunks.size());
        view.chunks.forEach(key -> result.add(ChunkPosition.unpack(key)));
        return List.copyOf(result);
    }

    public int trackedChunks(String sessionId) {
        View view = this.views.get(sessionId);
        return view == null ? 0 : view.chunks.size();
    }

    public boolean tracks(String sessionId, String dimension, int chunkX, int chunkZ) {
        View view = this.views.get(sessionId);
        return view != null && dimension.equals(view.dimension)
                && view.chunks.contains(ChunkPosition.pack(chunkX, chunkZ));
    }
}
