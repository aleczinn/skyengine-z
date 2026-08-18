package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Deterministische Reduktion globaler Kindspalten auf höchstens vier Außenintervalle. */
public final class LodColumnReducer {

    private static final int WORLD_HEIGHT = 512;
    private static final int OPEN_AIR_DIVISOR = 4;   // 25 %
    private static final int LANDMARK_DIVISOR = 16; // 6,25 %

    private record Run(int state, int minY, int maxY, int coverage, int flags) {}

    private static final class Scratch {
        int[] endpoints = new int[34];
        int[] selected = new int[33];
        int[] coverage = new int[33];
        boolean[] landmark = new boolean[33];
        boolean[] terrain = new boolean[33];
        boolean[] skyOpen = new boolean[33];
        int[] states = new int[4];
        int[] counts = new int[4];
        int[] landmarkCounts = new int[4];
        int[] terrainCounts = new int[4];

        void ensure(int columns, int endpoints) {
            if (this.endpoints.length < endpoints) this.endpoints = new int[endpoints];
            int segments = endpoints - 1;
            if (this.selected.length < segments) {
                this.selected = new int[segments];
                this.coverage = new int[segments];
                this.landmark = new boolean[segments];
                this.terrain = new boolean[segments];
                this.skyOpen = new boolean[segments];
            }
            if (this.states.length < columns) {
                this.states = new int[columns];
                this.counts = new int[columns];
                this.landmarkCounts = new int[columns];
                this.terrainCounts = new int[columns];
            }
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    public static LodColumn reduce(LodColumn[] columns) {
        return reduce(columns, columns.length);
    }

    /**
     * Reduziert nur an tatsächlichen Intervallgrenzen. {@code parentArea} ist die durch
     * die Elternzelle repräsentierte L0-Fläche und hält die Schwellen levelunabhängig.
     */
    public static LodColumn reduce(LodColumn[] columns, int parentArea) {
        if (columns.length == 0 || parentArea <= 0) return LodColumn.EMPTY;

        boolean empty = true;
        int endpointCount = 2;
        int maxEndpoints = 2 + columns.length * LodColumn.MAX_INTERVALS * 2;
        Scratch scratch = SCRATCH.get();
        scratch.ensure(columns.length, maxEndpoints);
        int[] endpoints = scratch.endpoints;
        endpoints[0] = 0;
        endpoints[1] = WORLD_HEIGHT;
        for (LodColumn column : columns) {
            if (column.size() != 0) empty = false;
            for (int i = 0; i < column.size(); i++) {
                long interval = column.interval(i);
                endpoints[endpointCount++] = LodColumn.minY(interval);
                endpoints[endpointCount++] = LodColumn.maxY(interval);
            }
        }
        if (empty) return LodColumn.EMPTY;

        Arrays.sort(endpoints, 0, endpointCount);
        int uniqueCount = 1;
        for (int i = 1; i < endpointCount; i++) {
            if (endpoints[i] != endpoints[uniqueCount - 1]) endpoints[uniqueCount++] = endpoints[i];
        }

        int segmentCount = uniqueCount - 1;
        int[] selected = scratch.selected;
        int[] coverage = scratch.coverage;
        boolean[] landmark = scratch.landmark;
        boolean[] terrain = scratch.terrain;
        boolean[] skyOpen = scratch.skyOpen;
        int[] states = scratch.states;
        int[] counts = scratch.counts;
        int[] landmarkCounts = scratch.landmarkCounts;
        int[] terrainCounts = scratch.terrainCounts;
        Arrays.fill(selected, 0, segmentCount, Blocks.AIR);
        Arrays.fill(coverage, 0, segmentCount, 0);
        Arrays.fill(landmark, 0, segmentCount, false);
        Arrays.fill(terrain, 0, segmentCount, false);
        Arrays.fill(skyOpen, 0, segmentCount, false);
        int childArea = Math.max(1, parentArea / columns.length);

        for (int segment = 0; segment < segmentCount; segment++) {
            int y = endpoints[segment];
            int stateCount = 0;
            int openAir = 0;
            for (LodColumn column : columns) {
                long interval = intervalAt(column, y);
                if (interval == 0) {
                    if (openToSky(column, y)) openAir += childArea;
                    continue;
                }
                int state = LodColumn.state(interval);
                int index = 0;
                while (index < stateCount && states[index] != state) index++;
                if (index == stateCount) {
                    states[stateCount] = state;
                    counts[stateCount] = 0;
                    landmarkCounts[stateCount] = 0;
                    terrainCounts[stateCount] = 0;
                    stateCount++;
                }
                int weight = LodColumn.coverage(interval);
                counts[index] += weight;
                if (LodColumn.landmark(interval)) landmarkCounts[index] += weight;
                if (LodColumn.terrain(interval)) terrainCounts[index] += weight;
            }

            int landmarkIndex = bestEligibleLandmark(states, landmarkCounts, stateCount, parentArea);
            if (landmarkIndex >= 0) {
                selected[segment] = states[landmarkIndex];
                coverage[segment] = counts[landmarkIndex];
                landmark[segment] = true;
                terrain[segment] = terrainCounts[landmarkIndex] * 2 >= counts[landmarkIndex];
            } else if (openAir * OPEN_AIR_DIVISOR >= parentArea) {
                selected[segment] = Blocks.AIR;
                coverage[segment] = openAir;
                skyOpen[segment] = true;
            } else {
                int bestIndex = bestIndex(states, counts, stateCount);
                selected[segment] = bestIndex < 0 ? Blocks.AIR : states[bestIndex];
                coverage[segment] = bestIndex < 0 ? 0 : counts[bestIndex];
                terrain[segment] = bestIndex >= 0
                        && terrainCounts[bestIndex] * 2 >= counts[bestIndex];
            }
        }

        List<Run> runs = new ArrayList<>();
        for (int segment = 0; segment < segmentCount;) {
            int state = selected[segment];
            if (state == Blocks.AIR) {
                segment++;
                continue;
            }
            int first = segment;
            int maxCoverage = coverage[segment];
            boolean inheritedLandmark = landmark[segment];
            boolean inheritedTerrain = terrain[segment];
            while (++segment < segmentCount && selected[segment] == state) {
                maxCoverage = Math.max(maxCoverage, coverage[segment]);
                inheritedLandmark |= landmark[segment];
                inheritedTerrain |= terrain[segment];
            }
            int flags = 0;
            if (inheritedLandmark || maxCoverage * LANDMARK_DIVISOR >= parentArea
                    && separatedFromTerrain(selected, first)) {
                flags |= LodColumn.FLAG_LANDMARK;
            }
            if (inheritedTerrain) flags |= LodColumn.FLAG_TERRAIN;
            if (segment == segmentCount || skyOpen[segment]) flags |= LodColumn.FLAG_SKY_OPEN;
            runs.add(new Run(state, endpoints[first], endpoints[segment], maxCoverage, flags));
        }
        return limit(runs);
    }

    private static int bestEligibleLandmark(int[] states, int[] counts, int length, int area) {
        int best = -1;
        for (int i = 0; i < length; i++) {
            if (counts[i] * LANDMARK_DIVISOR < area) continue;
            if (best < 0 || counts[i] > counts[best]
                    || counts[i] == counts[best] && states[i] < states[best]) best = i;
        }
        return best;
    }

    private static int bestIndex(int[] states, int[] counts, int length) {
        int best = -1, bestCount = 0;
        for (int i = 0; i < length; i++) {
            int count = counts[i];
            if (count > bestCount || count == bestCount && count > 0
                    && (best < 0 || states[i] < states[best])) {
                best = i;
                bestCount = count;
            }
        }
        return best;
    }

    static LodColumn limitIntervals(List<Long> intervals) {
        List<Run> runs = new ArrayList<>(intervals.size());
        for (int i = 0; i < intervals.size(); i++) {
            long interval = intervals.get(i);
            int flags = LodColumn.flags(interval);
            /* Nur die oberste, vom Himmel sichtbare getrennte Silhouette ist eine Landmark.
               Innere Höhlendecken dürfen nicht bis in ferne Level hochvererbt werden. */
            boolean separated = i == 0
                    || LodColumn.maxY(intervals.get(i - 1)) < LodColumn.minY(interval);
            if (i == intervals.size() - 1 && LodColumn.minY(interval) > 0 && separated
                    && !LodColumn.terrain(interval)) {
                flags |= LodColumn.FLAG_LANDMARK;
            }
            runs.add(new Run(LodColumn.state(interval), LodColumn.minY(interval),
                    LodColumn.maxY(interval), LodColumn.coverage(interval), flags));
        }
        return limit(runs);
    }

    private static LodColumn limit(List<Run> runs) {
        if (runs.isEmpty()) return LodColumn.EMPTY;
        if (runs.size() > LodColumn.MAX_INTERVALS) {
            Run bottom = runs.getFirst();
            Run top = runs.getLast();
            List<Run> middle = new ArrayList<>(runs.subList(1, runs.size() - 1));
            middle.sort(Comparator
                    .comparing((Run run) -> (run.flags & LodColumn.FLAG_LANDMARK) == 0)
                    .thenComparingInt(run -> -(run.maxY - run.minY))
                    .thenComparingInt(Run::minY)
                    .thenComparingInt(Run::state));
            List<Run> kept = new ArrayList<>(LodColumn.MAX_INTERVALS);
            kept.add(bottom);
            for (Run run : middle) {
                if (kept.size() >= LodColumn.MAX_INTERVALS - 1) break;
                kept.add(run);
            }
            if (top != bottom) kept.add(top);
            kept.sort(Comparator.comparingInt(Run::minY));
            runs = kept;
        }
        long[] packed = new long[runs.size()];
        for (int i = 0; i < packed.length; i++) {
            Run run = runs.get(i);
            packed[i] = LodColumn.pack(run.state, run.minY, run.maxY, run.flags, run.coverage);
        }
        return LodColumn.owned(packed);
    }

    private static boolean separatedFromTerrain(int[] selected, int segment) {
        return segment > 0 && selected[segment - 1] == Blocks.AIR;
    }

    private static long intervalAt(LodColumn column, int y) {
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (y >= LodColumn.minY(interval) && y < LodColumn.maxY(interval)) return interval;
        }
        return 0;
    }

    private static boolean openToSky(LodColumn column, int y) {
        for (int i = 0; i < column.size(); i++) {
            if (LodColumn.minY(column.interval(i)) >= y) return false;
        }
        return true;
    }

    private LodColumnReducer() {}
}
