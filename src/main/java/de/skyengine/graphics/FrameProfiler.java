package de.skyengine.graphics;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Minimaler Frame-Profiler für die Bottleneck-Suche: misst pro Frame die GPU-Zeit der
 * disjunkten Draw-Abschnitte (GL_TIME_ELAPSED) und die CPU-Zeit ausgewählter Sektionen
 * (System.nanoTime). Nur aktiv bei DebugMode.FULL — sonst sind alle Methoden No-ops.
 *
 * GPU-Queries dürfen NICHT verschachteln (GL-Regel für GL_TIME_ELAPSED) — die Hooks
 * liegen deshalb um die disjunkten drawSegment-/Blit-Aufrufe, nicht um ganze Pässe.
 * Query-Ring mit 3 Sets (passend zum Triple-Buffering des ChunkRenderers): gelesen wird
 * ein Set erst bei seiner Wiederverwendung 3 Frames später — dann ist das Ergebnis
 * garantiert verfügbar und glGetQueryObjectui64 stallt nie.
 *
 * Alle Methoden laufen ausschließlich auf dem Render-Thread (GL-Kontext nötig).
 */
public final class FrameProfiler {

    /** Disjunkte GPU-Abschnitte (Reihenfolge = Ausgabe-Reihenfolge). */
    public enum Gpu {
        SOLID("solid"),
        LOD_OPAQUE("lodO"),
        CUTOUT("cut"),
        TRANSLUCENT("trans"),
        LOD_TRANSLUCENT("lodT"),
        BLIT("blit");

        final String label;

        Gpu(String label) {
            this.label = label;
        }
    }

    /**
     * CPU-Sektionen in Frame-Reihenfolge; FRAME = gesamtes onRender inkl. Swap.
     * Die Statuszeile weist zusätzlich rest = frame − Σ(übrige) aus, damit
     * unattributierte Zeit sofort sichtbar bleibt.
     */
    public enum Cpu {
        CLEAR("clear"),     // FBO-Bind + State-Enables + glClear
        ANIM("anim"),       // Textur-Animationen (glTexSubImage-Uploads)
        SYNC("sync"),       // Fence-Wait + Arena-Collect (beginFrame) + Fence-Create (endFrame)
        UPLOAD("upload"),   // Mesh-/LOD-Upload-Queues + Cleanup-Loops
        CULL("cull"),       // Frustum-Tests + Listenbau
        WRITE("write"),     // Command-/Offset-Segmente in den MappedRing
        GLSUB("glsub"),     // GL-Submission: Binds/Uniforms/MDI-Calls beider Chunk-Pässe
        REMESH("remesh"),   // ChunkManager.processRemeshes
        BE("be"),           // BlockEntity-Renderer (iteriert alle Chunks)
        ENT("ent"),         // Entity-Renderer (iteriert alle Chunks)
        SORT("sort"),       // Translucent: Section-Sort + Quad-Sort-Budget
        OVL("ovl"),         // Selection-Box + Crack + Fluid-Overlay
        GUI("gui"),         // GuiManager (HUD inkl. Item-Icons)
        SWAP("swap"),       // glfwSwapBuffers (kann im Treiber blocken)
        FRAME("frame");

        final String label;

        Cpu(String label) {
            this.label = label;
        }
    }

    private static final int SLOTS = 3;

    private static boolean initialized = false;
    private static boolean enabled = false;

    /* Query-Ring: [slot][sektion]; used markiert, ob im Slot-Frame eine Query lief */
    private static final int[][] queries = new int[SLOTS][Gpu.values().length];
    private static final boolean[][] used = new boolean[SLOTS][Gpu.values().length];
    private static long frame = 0;
    private static int slot = 0;

    /* Akkumulatoren seit der letzten Statuszeile (Nanosekunden) */
    private static final long[] gpuSum = new long[Gpu.values().length];
    private static final long[] cpuSum = new long[Cpu.values().length];
    private static final long[] cpuStart = new long[Cpu.values().length];
    private static int gpuFrames = 0;   // Frames mit ausgelesenen GPU-Ergebnissen
    private static int cpuFrames = 0;

    private FrameProfiler() {
    }

    /**
     * Einmal pro Frame am Frame-Anfang aufrufen (Render-Thread): liest die 3 Frames alten
     * Query-Ergebnisse des wiederverwendeten Slots aus und rotiert den Ring.
     */
    public static void newFrame() {
        if (!initialized) {
            initialized = true;
            enabled = SkyEngine.get().getConfig().getDebugMode().equals(EngineConfig.DebugMode.FULL);
            if (enabled) {
                for (int s = 0; s < SLOTS; s++) {
                    for (int q = 0; q < queries[s].length; q++) {
                        queries[s][q] = GL15.glGenQueries();
                    }
                }
            }
        }
        if (!enabled) return;

        slot = (int) (frame % SLOTS);
        frame++;

        boolean any = false;
        for (int q = 0; q < queries[slot].length; q++) {
            if (!used[slot][q]) continue;
            gpuSum[q] += GL33.glGetQueryObjectui64(queries[slot][q], GL15.GL_QUERY_RESULT);
            used[slot][q] = false;
            any = true;
        }
        if (any) gpuFrames++;
        cpuFrames++;
    }

    public static void gpuBegin(Gpu section) {
        if (!enabled) return;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[slot][section.ordinal()]);
    }

    public static void gpuEnd(Gpu section) {
        if (!enabled) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        used[slot][section.ordinal()] = true;
    }

    public static void cpuStart(Cpu section) {
        if (!enabled) return;
        cpuStart[section.ordinal()] = System.nanoTime();
    }

    public static void cpuStop(Cpu section) {
        if (!enabled) return;
        cpuSum[section.ordinal()] += System.nanoTime() - cpuStart[section.ordinal()];
    }

    /**
     * Baut die 1s-Statuszeile (Durchschnitt in µs pro Frame) und setzt die Akkumulatoren
     * zurück. Gibt null zurück, wenn der Profiler nicht aktiv ist oder noch keine Daten hat.
     */
    public static String statusLineAndReset() {
        if (!enabled || cpuFrames == 0) return null;

        StringBuilder sb = new StringBuilder("GPU[µs]");
        for (Gpu g : Gpu.values()) {
            long avg = gpuFrames > 0 ? gpuSum[g.ordinal()] / gpuFrames / 1000 : 0;
            sb.append(' ').append(g.label).append('=').append(avg);
            gpuSum[g.ordinal()] = 0;
        }
        sb.append(" | CPU[µs]");
        long attributed = 0;
        for (Cpu c : Cpu.values()) {
            if (c == Cpu.FRAME) continue;
            long avg = cpuSum[c.ordinal()] / cpuFrames / 1000;
            attributed += cpuSum[c.ordinal()];
            sb.append(' ').append(c.label).append('=').append(avg);
            cpuSum[c.ordinal()] = 0;
        }
        long frameAvg = cpuSum[Cpu.FRAME.ordinal()] / cpuFrames / 1000;
        /* rest = im FRAME enthaltene, aber keiner Sektion zugeordnete Zeit */
        long restAvg = (cpuSum[Cpu.FRAME.ordinal()] - attributed) / cpuFrames / 1000;
        sb.append(" | frame=").append(frameAvg).append(" rest=").append(restAvg);
        cpuSum[Cpu.FRAME.ordinal()] = 0;
        gpuFrames = 0;
        cpuFrames = 0;
        return sb.toString();
    }
}
