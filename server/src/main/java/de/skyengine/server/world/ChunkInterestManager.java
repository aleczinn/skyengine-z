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
        return update(sessionId, dimension, centerX, centerZ, viewDistance, 0, motionX, motionZ);
    }

    /**
     * Computes the visible circular interest plus an exact Chebyshev source halo. A plain
     * radius+1 circle is insufficient: the diagonal neighbour of (viewDistance, 0) lies just
     * outside it and would permanently prevent that boundary column from meshing.
     */
    public InterestDelta update(String sessionId, String dimension, int centerX, int centerZ,
                                int viewDistance, int meshHalo, float motionX, float motionZ) {
        if (viewDistance < 0 || viewDistance > 32 || meshHalo < 0 || meshHalo > 1) {
            throw new IllegalArgumentException("Invalid view distance or mesh halo");
        }
        View old = this.views.computeIfAbsent(sessionId, ignored -> new View());
        int extent = viewDistance + meshHalo;
        LongHashSet next = new LongHashSet((extent * 2 + 1) * (extent * 2 + 1));
        List<ChunkRequest> entered = new ArrayList<>();
        boolean sameDimension = dimension.equals(old.dimension);
        for (int dz = -extent; dz <= extent; dz++) {
            for (int dx = -extent; dx <= extent; dx++) {
                int visibleDx = Math.max(0, Math.abs(dx) - meshHalo);
                int visibleDz = Math.max(0, Math.abs(dz) - meshHalo);
                if (visibleDx * visibleDx + visibleDz * visibleDz > viewDistance * viewDistance) continue;
                int chunkX = centerX + dx, chunkZ = centerZ + dz;
                long key = ChunkPosition.pack(chunkX, chunkZ);
                next.add(key);
                if (!sameDimension || !old.chunks.contains(key)) {
                    int forward = forwardScore(dx, dz, motionX, motionZ);
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

    private static int forwardScore(int dx, int dz, float motionX, float motionZ) {
        double motionLength = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (motionLength < 1.0E-6 || distance < 1.0E-6) return 1024;
        return (int) Math.round((dx * motionX + dz * motionZ) / (distance * motionLength) * 1024.0);
    }
}
