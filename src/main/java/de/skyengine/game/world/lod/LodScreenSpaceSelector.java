package de.skyengine.game.world.lod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Projektionsbasierte Auswahl der Volumenhierarchie. Anders als feste Distanzringe reagiert
 * sie automatisch auf Aufloesung und Zoom: ein groesser projizierter Knoten wird verfeinert.
 * Fehlen Kinder, bleibt der Elternknoten sichtbar (lochfreier Streaming-Fallback).
 */
public final class LodScreenSpaceSelector {

    @FunctionalInterface
    public interface Resolver { LodVoxelSection resolve(LodVolumeHierarchy.Key key); }

    public record Selection(List<LodVoxelSection> nodes, int refinements, int missingChildren) {}

    public static Selection select(Collection<LodVoxelSection> roots, Resolver resolver,
                                   double cameraX, double cameraY, double cameraZ,
                                   int viewportHeight, double verticalFovRadians,
                                   double pixelThreshold, int refinementBudget) {
        if (viewportHeight <= 0 || verticalFovRadians <= 0 || verticalFovRadians >= Math.PI
                || pixelThreshold <= 0 || refinementBudget < 0) {
            throw new IllegalArgumentException("Ungueltige Screen-Space-Parameter");
        }
        ArrayDeque<LodVoxelSection> queue = new ArrayDeque<>(roots);
        ArrayList<LodVoxelSection> selected = new ArrayList<>();
        int refinements = 0, missing = 0;
        double focalPixels = viewportHeight / (2.0 * Math.tan(verticalFovRadians * 0.5));
        while (!queue.isEmpty()) {
            LodVoxelSection node = queue.removeFirst();
            double distance = distanceToNode(node, cameraX, cameraY, cameraZ);
            double projectedPixels = node.extent() * focalPixels / Math.max(distance, 0.01);
            if (node.level == 0 || projectedPixels <= pixelThreshold || refinements >= refinementBudget) {
                selected.add(node);
                continue;
            }
            LodVoxelSection[] children = children(node, resolver);
            boolean complete = true;
            for (LodVoxelSection child : children) complete &= child != null;
            if (!complete) {
                selected.add(node);
                missing++;
                continue;
            }
            refinements++;
            for (LodVoxelSection child : children) queue.addLast(child);
        }
        return new Selection(List.copyOf(selected), refinements, missing);
    }

    private static LodVoxelSection[] children(LodVoxelSection parent, Resolver resolver) {
        LodVoxelSection[] children = new LodVoxelSection[8];
        for (int i = 0; i < 8; i++) {
            children[i] = resolver.resolve(new LodVolumeHierarchy.Key(
                    parent.nodeX * 2 + (i & 1), parent.nodeY * 2 + (i >>> 2 & 1),
                    parent.nodeZ * 2 + (i >>> 1 & 1), parent.level - 1));
        }
        return children;
    }

    private static double distanceToNode(LodVoxelSection node, double x, double y, double z) {
        int extent = node.extent();
        double minX = (double) node.nodeX * extent;
        double minY = (double) node.nodeY * extent;
        double minZ = (double) node.nodeZ * extent;
        double dx = axisDistance(x, minX, minX + extent);
        double dy = axisDistance(y, minY, minY + extent);
        double dz = axisDistance(z, minZ, minZ + extent);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0.0;
    }

    /** Langsam reagierender Regler fuer das optionale 3-ms-GPU-Budget. */
    public static final class AutoQuality {
        private double smoothedMs = Double.NaN;

        public int update(int currentThreshold, double gpuMs, double targetMs) {
            if (!Double.isFinite(gpuMs) || gpuMs < 0 || targetMs <= 0) return currentThreshold;
            this.smoothedMs = Double.isNaN(this.smoothedMs) ? gpuMs : this.smoothedMs * 0.9 + gpuMs * 0.1;
            int threshold = currentThreshold;
            if (this.smoothedMs > targetMs * 1.10) threshold += 8;
            else if (this.smoothedMs < targetMs * 0.75) threshold -= 8;
            return Math.clamp(threshold, 32, 256);
        }

        public double smoothedMs() { return this.smoothedMs; }
    }

    private LodScreenSpaceSelector() {}
}
