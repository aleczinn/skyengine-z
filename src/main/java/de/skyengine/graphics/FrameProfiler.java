package de.skyengine.graphics;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.Arrays;

/** Render-Thread-Fassade fuer bestehende CPU-/GPU-Messpunkte. */
public final class FrameProfiler {
    public enum Gpu {
        SOLID("solid", PerformanceProfiler.GpuSection.L0_OPAQUE),
        LOD_OPAQUE("lodO", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        LOD_O_L1("lodO1", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        LOD_O_L2("lodO2", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        LOD_O_L3("lodO3", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        LOD_O_L4("lodO4", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        LOD_O_L5("lodO5", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        CUTOUT("cut", PerformanceProfiler.GpuSection.L0_CUTOUT),
        CULL_P1("cull1", PerformanceProfiler.GpuSection.CULL_HIZ),
        HIZ("hiz", PerformanceProfiler.GpuSection.CULL_HIZ),
        CULL_P2("cull2", PerformanceProfiler.GpuSection.CULL_HIZ),
        SOLID_P2("solid2", PerformanceProfiler.GpuSection.L0_OPAQUE),
        LOD_P2("lodO2p", PerformanceProfiler.GpuSection.LOD_OPAQUE),
        CUTOUT_P2("cut2", PerformanceProfiler.GpuSection.L0_CUTOUT),
        TRANSLUCENT("trans", PerformanceProfiler.GpuSection.L0_TRANSLUCENT),
        LOD_TRANSLUCENT("lodT", PerformanceProfiler.GpuSection.LOD_TRANSLUCENT),
        BLOCK_ENTITIES("blockEntities", PerformanceProfiler.GpuSection.BLOCK_ENTITIES),
        ENTITIES("entities", PerformanceProfiler.GpuSection.ENTITIES),
        PARTICLES_OPAQUE("particlesO", PerformanceProfiler.GpuSection.PARTICLES),
        PARTICLES_TRANSLUCENT("particlesT", PerformanceProfiler.GpuSection.PARTICLES),
        OVERLAYS("overlays", PerformanceProfiler.GpuSection.HAND_OVERLAYS),
        GUI("gui", PerformanceProfiler.GpuSection.GUI),
        BLIT("resolve/post", PerformanceProfiler.GpuSection.POSTPROCESSING);

        final String label;
        final PerformanceProfiler.GpuSection target;
        Gpu(String label, PerformanceProfiler.GpuSection target) { this.label = label; this.target = target; }
    }

    public enum Cpu {
        TICK("tick", null), CLEAR("clear", null), ANIM("anim", PerformanceProfiler.CpuSection.UPLOAD),
        SYNC("sync", PerformanceProfiler.CpuSection.SUBMISSION),
        UPLOAD("upload", PerformanceProfiler.CpuSection.UPLOAD),
        CULL("cull", PerformanceProfiler.CpuSection.CULL),
        WRITE("write", PerformanceProfiler.CpuSection.COMMAND_BUILD),
        GLSUB("glsub", PerformanceProfiler.CpuSection.SUBMISSION),
        REMESH("remesh", PerformanceProfiler.CpuSection.UPLOAD),
        BE("be", PerformanceProfiler.CpuSection.BLOCK_ENTITIES),
        ENT("ent", PerformanceProfiler.CpuSection.ENTITIES),
        PARTICLES("particles", PerformanceProfiler.CpuSection.PARTICLES),
        SORT("sort", PerformanceProfiler.CpuSection.SORT),
        OVL("ovl", PerformanceProfiler.CpuSection.OVERLAYS),
        GUI("gui", PerformanceProfiler.CpuSection.GUI),
        PROFILER_UI("profiler", PerformanceProfiler.CpuSection.PROFILER_UI),
        SWAP("swap", PerformanceProfiler.CpuSection.SWAP),
        FRAME("frame", PerformanceProfiler.CpuSection.FRAME);

        final String label;
        final PerformanceProfiler.CpuSection target;
        Cpu(String label, PerformanceProfiler.CpuSection target) { this.label = label; this.target = target; }
    }

    private static final int SLOTS = 8;
    private static final int[][] queries = new int[SLOTS][Gpu.values().length];
    private static final int[][] timestamps = new int[SLOTS][2];
    private static final boolean[][] used = new boolean[SLOTS][Gpu.values().length];
    private static final boolean[] pending = new boolean[SLOTS];
    private static final long[] queryGeneration = new long[SLOTS];
    private static final long[] cpuStart = new long[Cpu.values().length];
    private static final long[] cpuFrame = new long[Cpu.values().length];
    private static final long SPIKE_THRESHOLD_NANOS = 25_000_000L;
    private static boolean glInitialized;
    private static int activeSlot = -1;
    private static long lastLoopNanos;

    private FrameProfiler() {}

    public static boolean isEnabled() { return PerformanceProfiler.get().isEnabled(); }
    public static void setEnabled(boolean enabled) {
        PerformanceProfiler.get().setEnabled(enabled);
        if (!enabled) { Arrays.fill(cpuStart, 0); Arrays.fill(cpuFrame, 0); }
    }

    /** Frame-Anfang: alte CPU-Werte publizieren, fertige GPU-Sets abholen und freien Slot suchen. */
    public static void newFrame() {
        PerformanceProfiler profiler = PerformanceProfiler.get();
        if (!profiler.isEnabled()) { activeSlot = -1; return; }
        if (!glInitialized) initQueries();
        flushCpuFrame(profiler);
        collectAvailable(profiler);
        activeSlot = findFreeSlot();
        if (activeSlot >= 0) GL33.glQueryCounter(timestamps[activeSlot][0], GL33.GL_TIMESTAMP);
        profiler.publishSnapshot();
    }

    public static void gpuFrameEnd() {
        if (!isEnabled() || activeSlot < 0) return;
        GL33.glQueryCounter(timestamps[activeSlot][1], GL33.GL_TIMESTAMP);
        queryGeneration[activeSlot] = PerformanceProfiler.get().snapshot().generation();
        pending[activeSlot] = true;
        activeSlot = -1;
    }

    public static void gpuBegin(Gpu section) {
        if (!isEnabled() || activeSlot < 0) return;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[activeSlot][section.ordinal()]);
    }

    public static void gpuEnd(Gpu section) {
        if (!isEnabled() || activeSlot < 0) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        used[activeSlot][section.ordinal()] = true;
    }

    public static void cpuStart(Cpu section) {
        if (!isEnabled()) return;
        cpuStart[section.ordinal()] = System.nanoTime();
    }

    public static void cpuStop(Cpu section) {
        if (!isEnabled()) return;
        long started = cpuStart[section.ordinal()];
        if (started == 0) return;
        cpuFrame[section.ordinal()] += System.nanoTime() - started;
        cpuStart[section.ordinal()] = 0;
    }

    public static String loopEndSpikeLine() {
        if (!isEnabled()) return null;
        long loop = cpuFrame[Cpu.TICK.ordinal()] + cpuFrame[Cpu.FRAME.ordinal()];
        lastLoopNanos = Math.max(lastLoopNanos, loop);
        if (loop < SPIKE_THRESHOLD_NANOS) return null;
        StringBuilder line = new StringBuilder("SPIKE[us] loop=").append(loop / 1_000);
        for (Cpu section : Cpu.values()) line.append(' ').append(section.label).append('=').append(cpuFrame[section.ordinal()] / 1_000);
        return line.toString();
    }

    /** Bestehender Ein-Sekunden-Konsolenvertrag, jetzt aus dem letzten Snapshot. */
    public static String statusLineAndReset() {
        if (!isEnabled()) return null;
        PerformanceProfiler.ProfilerSnapshot snapshot = PerformanceProfiler.get().publishSnapshot();
        StringBuilder line = new StringBuilder("PROF CPU[ms]");
        for (PerformanceProfiler.CpuSection section : PerformanceProfiler.CpuSection.values()) {
            PerformanceProfiler.TimingStats stats = snapshot.cpu().get(section);
            if (stats != null && stats.samples() > 0) line.append(' ').append(section.name().toLowerCase()).append('=')
                    .append(String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f/%.2f",
                            stats.currentMillis(), stats.meanMillis(), stats.p95Millis(), stats.maxMillis()));
        }
        line.append(" maxLoop=").append(lastLoopNanos / 1_000_000.0);
        lastLoopNanos = 0;
        return line.toString();
    }

    /** Weltwechsel/Reload: Historien entwerten; GL-Abfragen werden beim naechsten Frame verworfen. */
    public static void reset() {
        PerformanceProfiler.get().reset();
        Arrays.fill(cpuStart, 0); Arrays.fill(cpuFrame, 0);
    }

    /** Gibt Query-Objekte frei; darf nur mit aktuellem GL-Kontext beim Engine-Shutdown laufen. */
    public static void dispose() {
        if (!glInitialized) return;
        for (int slot = 0; slot < SLOTS; slot++) {
            for (int query : queries[slot]) GL15.glDeleteQueries(query);
            GL15.glDeleteQueries(timestamps[slot][0]);
            GL15.glDeleteQueries(timestamps[slot][1]);
        }
        glInitialized = false;
        activeSlot = -1;
        Arrays.fill(pending, false);
        for (boolean[] row : used) Arrays.fill(row, false);
    }

    private static void flushCpuFrame(PerformanceProfiler profiler) {
        long[] totals = new long[PerformanceProfiler.CpuSection.values().length];
        long attributed = 0;
        for (Cpu section : Cpu.values()) {
            long nanos = cpuFrame[section.ordinal()];
            if (nanos > 0 && section.target != null) {
                totals[section.target.ordinal()] += nanos;
                if (section != Cpu.FRAME) attributed += nanos;
            }
        }
        for (PerformanceProfiler.CpuSection section : PerformanceProfiler.CpuSection.values()) {
            long nanos = totals[section.ordinal()];
            if (nanos > 0) profiler.record(section, nanos);
        }
        long frame = cpuFrame[Cpu.FRAME.ordinal()];
        if (frame > 0) profiler.record(PerformanceProfiler.CpuSection.REST, Math.max(0, frame - attributed));
        Arrays.fill(cpuFrame, 0);
    }

    private static void initQueries() {
        for (int slot = 0; slot < SLOTS; slot++) {
            for (int query = 0; query < Gpu.values().length; query++) queries[slot][query] = GL15.glGenQueries();
            timestamps[slot][0] = GL15.glGenQueries(); timestamps[slot][1] = GL15.glGenQueries();
        }
        glInitialized = true;
    }

    private static void collectAvailable(PerformanceProfiler profiler) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!pending[slot] || GL15.glGetQueryObjecti(timestamps[slot][1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            boolean complete = true;
            for (int query = 0; query < Gpu.values().length; query++) {
                if (used[slot][query] && GL15.glGetQueryObjecti(queries[slot][query], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) { complete = false; break; }
            }
            if (!complete) continue;
            boolean currentGeneration = queryGeneration[slot] == profiler.snapshot().generation();
            long[] totals = new long[PerformanceProfiler.GpuSection.values().length];
            for (Gpu section : Gpu.values()) {
                int query = section.ordinal();
                if (!used[slot][query]) continue;
                long nanos = GL33.glGetQueryObjectui64(queries[slot][query], GL15.GL_QUERY_RESULT);
                totals[section.target.ordinal()] += nanos;
                used[slot][query] = false;
            }
            if (currentGeneration) {
                for (PerformanceProfiler.GpuSection section : PerformanceProfiler.GpuSection.values()) {
                    long nanos = totals[section.ordinal()];
                    if (nanos > 0) profiler.record(section, nanos);
                }
            }
            long start = GL33.glGetQueryObjectui64(timestamps[slot][0], GL15.GL_QUERY_RESULT);
            long end = GL33.glGetQueryObjectui64(timestamps[slot][1], GL15.GL_QUERY_RESULT);
            if (currentGeneration) profiler.record(PerformanceProfiler.GpuSection.FRAME_SPAN, Math.max(0, end - start));
            pending[slot] = false;
        }
    }

    private static int findFreeSlot() {
        for (int slot = 0; slot < SLOTS; slot++) if (!pending[slot]) return slot;
        return -1;
    }

}
