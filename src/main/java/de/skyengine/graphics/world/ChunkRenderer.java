package de.skyengine.graphics.world;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.EngineProperties;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.dimension.DimensionEnvironment;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.texture.TextureArray;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import de.skyengine.utils.collect.LongObjMap;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Zeichnet alle Chunk-Sections über MultiDrawIndirect: die Geometrie aller Sections liegt
 * in einer {@link VertexArena} pro RenderLayer, pro Frame wird nur noch das
 * Indirect-Command-Array (+ Offset-SSBO) aus den sichtbaren Sections gebaut — ein
 * glMultiDrawElementsIndirect-Call pro Layer statt eines Draw-Calls pro Section×Layer.
 */
public class ChunkRenderer {

    private static final int SLOTS = 3;                 // Frames in flight (Command-/Offset-Ringe)
    private static final int COMMAND_BYTES = 20;        // DrawElementsIndirectCommand (5 uints)
    private static final int OFFSET_BYTES = 16;         // vec4 im std430-SSBO

    private final Logger logger = LogManager.getLogger(ChunkRenderer.class.getName());

    private final ChunkManager chunkManager;
    private final long renderGeneration;
    private ShaderProgram shader;
    /* Block-Atlas: gehört dem GameContainer (Engine-Lebensdauer, welt-unabhängig) —
       der Renderer hält nur Referenzen und disposed NICHTS davon. */
    private BlockTextureAtlas atlas;
    private TextureArray textures;
    /* sectionKey -> mesh, render thread only. LongObjMap: kein Long-Boxing pro Zugriff,
       Cleanup-Walk und Frame-Iterationen laufen als flacher Array-Scan. */
    private final LongObjMap<SectionMesh> meshes = new LongObjMap<>(4096);

    /* Cull-Hierarchie: Chunk-Spalten-Index über den Section-Meshes. Erst das Spalten-AABB
       (XZ der Spalte, [minSy,maxSy] der vorhandenen Sections) gegen das Frustum testen, nur
       bei Schnitt die einzelnen Sections — spart den Großteil der testAab-Aufrufe (gemessen
       war der flache Loop ~135 µs/Frame). Parallel-Index zu this.meshes: an ALLEN Mutationsstellen über
       columnAdd/columnRemove mitpflegen (put/remove/Cleanup-Walk/dispose). */
    private static final class CullColumn {
        final int chunkX, chunkZ;
        final SectionMesh[] sections = new SectionMesh[Chunk.SECTIONS];
        int count, minSy, maxSy;

        CullColumn(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private final LongObjMap<CullColumn> cullColumns = new LongObjMap<>(1024);

    /* Pro Frame neu befüllt: alle Sections, die den Frustum-Test bestanden haben */
    private final List<SectionMesh> visible = new ArrayList<>();

    /* Teilmenge von visible mit TRANSLUCENT-Layer - nur diese werden back-to-front sortiert */
    private final List<SectionMesh> translucentVisible = new ArrayList<>();

    /* Wiederverwendete Scratch-Puffer für die Translucent-Sortierung (Key-Array statt
       Comparator: der Lambda-Sort rechnete distanceSq ZWEIMAL pro Vergleich, also
       ~2·n·log n statt n mal, und allozierte pro Frame — Muster wie SectionMesh.sortTranslucent). */
    private long[] translucentSortKeys = new long[0];
    private SectionMesh[] translucentSortScratch = new SectionMesh[0];
    private final Vector3d translucentSortDirection = new Vector3d();

    /* Kleinvegetation (Detail-Segment in der CUTOUT-Arena), pro Frame aus den sichtbaren
       Sections abgeleitet. */
    private final List<SectionMesh> visibleDetail = new ArrayList<>();

    /* Anker der Vegetations-Ausdünnung: die Fade-Distanz wird NICHT von der Live-Position
       gerechnet (jede Bewegung ließe einzelne Pflanzen nacheinander auftauchen), sondern vom
       zuletzt gesetzten Chunk-Zentrum; der Anker springt erst nach, wenn der Spieler sich
       DETAIL_ANCHOR_HYSTERESE Blöcke entfernt hat -> seltene Batch-Pops statt Dauer-Getröpfel,
       und kein Hin-und-her-Flackern beim Entlanglaufen einer Chunk-Grenze. */
    private static final double DETAIL_ANCHOR_HYSTERESE = 48.0;
    private double detailAnchorX = Double.NaN, detailAnchorZ;

    private DimensionEnvironment environment = DimensionEnvironment.OVERWORLD;

    /* Deckel für die Vorab-Reservierung je Arena (s. cappedArenaBytes). */
    private static final long MAX_INITIAL_ARENA_BYTES = 768L << 20;

    /* Weicher Deckel der Priority-Uploads (Edit-Remeshes): Einzel-Edits liegen weit darunter,
       Explosions-Wellen verteilen sich über wenige Frames (s. renderSolid Schritt 2a). */
    private static final int MAX_PRIORITY_UPLOADS_PER_FRAME = 24;

    /* Gate für die Cleanup-Walks: die O(Meshes)-Prüfungen laufen nur noch
       in Frames, in denen sich das Chunk-Set wirklich geändert hat
       (gemessen ~250 µs/Frame gespart im Steady-State). -1 = beim ersten Frame laufen. */
    private int lastChunkRemovalVersion = -1;

    /* Fence-Diagnose (nur bei aktivem FrameProfiler gefüllt, s. beginFrame) */
    private long syncFrames, syncSignaled, syncWaitNs, syncWaitMaxNs;

    private static final int MAX_UPLOADS_PER_FRAME = 8;

    /* Startdiagnose (nur Debug-Log), s. trackInitialUpload: verbleibende Erst-Mesh-Batches
       des Lade-Fixpunkts; -1 = noch nicht scharf, true = Messung abgeschlossen. */
    private int initialUploadRemaining = -1;
    private boolean initialUploadMeasured;
    private int initialUploadCycle = -1;

    /* Deckelt Quad-Sorts pro Frame — bei Kamerabewegung wollen sonst alle sichtbaren
       Translucent-Sections gleichzeitig neu sortieren (Ozean -> Upload-Spike). */
    private static final int MAX_TRANSLUCENT_SORTS_PER_FRAME = 8;

    /* --- MDI-Infrastruktur --- */

    private static final int ARENA_SLOTS = RenderLayer.VALUES.length;

    /* Eine Arena + ein VAO pro RenderLayer (Index = ordinal). */
    private final VertexArena[] arenas = new VertexArena[ARENA_SLOTS];
    private final int[] vaos = new int[ARENA_SLOTS];
    /* Im VAO gebundener Arena-Buffer/EBO — bei Arena-Wachstum oder EBO-Neubau neu binden */
    private final int[] vaoArenaBuffer = new int[ARENA_SLOTS];
    private final int[] vaoEbo = new int[ARENA_SLOTS];

    /* Dynamischer Quad-Index-Buffer (0,1,2, 2,3,0 je Quad). */
    private int sharedEbo = 0;
    private int indexCapacityQuads = 0;
    private int maxSeenQuads = 1;

    private MappedRing commandRing;
    private MappedRing offsetRing;

    /* Frame-Fences je Ring-Slot: schützen Slot-Wiederverwendung und Arena-Deferred-Frees */
    private final long[] fences = new long[SLOTS];
    private final long[] slotFrames = new long[SLOTS];
    private long frameId = 0;
    private int frameSlot = 0;

    /* Segment-Cursor (Bytes im Slot) zwischen renderSolid und renderTranslucent */
    private long cmdCursor, offCursor;

    private int renderedSections = 0;
    private int totalSections = 0;

    /* Gecachte Uniform-Locations des Chunk-Shaders (erspart Map-Lookups im Hot-Path) */
    private int locProjectionView, locAlphaCutoff, locFogStart, locFogEnd, locFogColor,
            locDetailFade, locDetailCamSnap, locMinLight, locBrightness,
            locPbrEnabled;

    /* Grundhelligkeit bei Lichtlevel 0 (Minecraft-Niveau): eine unbeleuchtete Höhle ist nie
       exakt schwarz. Der Regler hebt genau diesen Wert mit an (0,04 bei 0 % bis 0,15 bei 100 %,
       s. Fragment-Shader) — deshalb steht der Boden dort VOR der Kurve. */
    private static final float AMBIENT_LIGHT = 0.04F;
    /* Zuletzt hochgeladene Werte — Upload nur bei Änderung (wie bei den Fog-Uniforms). */
    private float lastMinLight = Float.NaN;
    private float lastBrightness = Float.NaN;

    /* Zuletzt hochgeladene Fog-Werte: Upload nur bei Änderung (Settings/Clear-Color) —
       die Werte sind pro Frame konstant, ein Re-Upload pro Pass wäre doppelt umsonst. */
    private float lastFogStart = Float.NaN, lastFogEnd = Float.NaN;
    private float lastFogR = -1F, lastFogG = -1F, lastFogB = -1F;

    public ChunkRenderer(ChunkManager chunkManager, long renderGeneration) {
        this.chunkManager = chunkManager;
        this.renderGeneration = renderGeneration;
    }

    public void setEnvironment(DimensionEnvironment environment) {
        this.environment = environment == null ? DimensionEnvironment.OVERWORLD : environment;
        this.lastFogStart = Float.NaN;
        this.lastMinLight = Float.NaN;
    }

    /** Render thread, GL context required. Der Atlas muss bereits gebaut sein (Boot). */
    public void init(BlockTextureAtlas atlas) {
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        if (!properties.isUseMultiDrawIndirect() || !properties.isUseBufferStorage()) {
            throw new IllegalStateException("ChunkRenderer benötigt MultiDrawIndirect (GL 4.3) + BufferStorage (GL 4.4)");
        }

        this.shader = new ShaderProgram(
                new Shader(VERTEX_SOURCE, ShaderType.VERTEX),
                new Shader(FRAGMENT_SOURCE, ShaderType.FRAGMENT)
        );
        /* Uniform-Locations einmalig cachen; u_Textures ändert sich nie -> einmal setzen */
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locAlphaCutoff = this.shader.getUniformLocation("u_AlphaCutoff");
        this.locFogStart = this.shader.getUniformLocation("u_FogStart");
        this.locFogEnd = this.shader.getUniformLocation("u_FogEnd");
        this.locFogColor = this.shader.getUniformLocation("u_FogColor");
        this.locDetailFade = this.shader.getUniformLocation("u_DetailFade");
        this.locDetailCamSnap = this.shader.getUniformLocation("u_DetailCamSnap");
        this.locMinLight = this.shader.getUniformLocation("u_MinLight");
        this.locBrightness = this.shader.getUniformLocation("u_Brightness");
        this.locPbrEnabled = this.shader.getUniformLocation("u_PbrEnabled");
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformi("u_NormalTextures", 1);
        this.shader.setUniformi("u_MaterialTextures", 2);
        this.shader.setUniformVector2f(this.locDetailFade, 0F, 0F); // Ausdünnung default aus
        this.shader.unbind();
        /* Atlas kommt von außen (BlockTextureAtlas, einmal beim Boot gebaut) — Welt-Ein-/
           Austritte erzeugen ihn nicht neu (Layer-Indizes stecken in den gebackenen Modellen). */
        this.atlas = atlas;
        this.textures = atlas.textures();

        /* Arenen so starten, dass das Wachstum auch beim SCHNELLEN FLIEGEN entfällt — jeder
           Grow ist eine GPU-Vollkopie der ganzen Arena im Frame (= gemessener Ruckler, der
           sogar die Flugsteuerung kurz anhält, plus NVIDIA-0x20072-Warnung).

           Bezugsgröße ist bewusst der (rd+6)-Kreis, NICHT rd: beim Flug hält die Arena
           durch noch auslaufende Uploads mehr als den Steady-State (real beobachtet:
           OPAQUE wuchs bei rd=16 von 96 auf 324 MB, inkl. First-Fit-Fragmentierung).
           Die Bytes/Chunk sind aus Sprint-Flug-Messungen abgeleitet (OPAQUE: Endstand 324 MB /
           ~1520 Chunks ≈ 220 KB; CUTOUT trägt das LAUB — über Wald wuchs es real auf >285 MB,
           also NICHT kleiner ansetzen als OPAQUE); Floors = bisherige Startgrößen. */
        GameSettings settings = GameSettings.get();
        int holdRadius = settings.renderDistance + 6;
        long holdChunks = Math.round(Math.PI * holdRadius * holdRadius);
        this.arenas[RenderLayer.OPAQUE.ordinal()] = new VertexArena("VertexArena OPAQUE",
                cappedArenaBytes("OPAQUE", Math.max(96L << 20, holdChunks * 256L * 1024)));
        this.arenas[RenderLayer.CUTOUT.ordinal()] = new VertexArena("VertexArena CUTOUT",
                cappedArenaBytes("CUTOUT", Math.max(64L << 20, holdChunks * 256L * 1024)));
        this.arenas[RenderLayer.TRANSLUCENT.ordinal()] = new VertexArena("VertexArena TRANSLUCENT",
                cappedArenaBytes("TRANSLUCENT", Math.max(8L << 20, holdChunks * 16L * 1024)));
        for (int i = 0; i < this.vaos.length; i++) {
            this.vaos[i] = GL30.glGenVertexArrays();
            /* VAO-Name existiert erst nach dem ersten Bind — sonst GL_INVALID_VALUE beim Label */
            GL30.glBindVertexArray(this.vaos[i]);
            GlDebug.labelVertexArray(this.vaos[i], "ChunkRenderer VAO " + slotLabel(i));
        }
        GL30.glBindVertexArray(0);

        /* Initial: 16k Draws je Layer-Segment reichen weit; Ringe wachsen bei Bedarf. */
        this.commandRing = new MappedRing("MDI CommandRing", SLOTS, 3 * MappedRing.align(16384L * COMMAND_BYTES));
        this.offsetRing = new MappedRing("MDI OffsetRing", SLOTS, 3 * MappedRing.align(16384L * OFFSET_BYTES));

        this.logger.info("MDI-Renderer: Arenen " + (this.arenas[0].getCapacity() >> 20) + "/"
                + (this.arenas[1].getCapacity() >> 20) + "/" + (this.arenas[2].getCapacity() >> 20)
                + " MB (Sections), " + SLOTS + " Frame-Slots");
    }

    /**
     * VRAM-Deckel für die Vorab-Reservierung einer Arena: bei sehr großen Render-Distanzen
     * rechnet die (rd+6)-Formel sonst in den Multi-GB-Bereich (rd=32 ≈ 1,13 GB je für OPAQUE
     * und CUTOUT) — ohne jeden Fallback, wenn der Treiber das nicht mehr hergibt. Der Deckel
     * betrifft NUR die Startgröße; die Arena wächst bei echtem Bedarf weiter (grow/
     * ensureCapacity bleiben ungedeckelt, createBuffer wirft jetzt bei GL_OUT_OF_MEMORY).
     * Jede geklemmte Arena wird geloggt: ab da sind Grow-Ruckler beim Flug wieder möglich.
     */
    private long cappedArenaBytes(String label, long wanted) {
        long capped = Math.min(wanted, MAX_INITIAL_ARENA_BYTES);
        if (capped < wanted) {
            this.logger.warning("Arena-Startgroesse " + label + " gedeckelt: " + (wanted >> 20)
                    + " -> " + (capped >> 20) + " MB — Grows (Frame-Ruckler) sind moeglich");
        }
        return capped;
    }

    /**
     * Opaque- und Cutout-Pass (inkl. Upload/Cleanup/Frustum-Culling). Der Translucent-Pass
     * folgt separat in {@link #renderTranslucent}, damit Entities dazwischen rendern können
     * (Vanilla-Reihenfolge: Wasser blendet über Entities).
     */
    public void renderSolid(Camera camera) {
        /* 0. Texturanimationen vorrücken (Frame-Tausch, kein Re-Mesh) */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.ANIM);
        this.atlas.tick();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.ANIM);

        /* 1. Frame-Slot übernehmen: auf den 3 Frames alten Fence warten (i.d.R. längst
           signalisiert) und dann die bis dahin aufgelaufenen Arena-Frees einsammeln. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.SYNC);
        this.beginFrame();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.SYNC);

        FrameProfiler.cpuStart(FrameProfiler.Cpu.UPLOAD);

        /* 2a. Prioritäts-Batches (Edit-/Fluid-Remeshes) immer zuerst; weich gedeckelt:
           bei Einzel-Edits bleibt die Änderung sofort sichtbar (weit unter dem Deckel),
           aber eine Explosion (dutzende Chunk-Batches auf einmal) verteilt ihre Uploads
           über wenige Frames statt hunderte GL-Calls + Arena-Allocs in EINEM Frame zu
           machen. Überholen bleibt korrekt (meshSeq-Prüfung in applyBatch). */
        ChunkManager.MeshBatch batch;
        int priorityUploads = 0;
        while (priorityUploads < MAX_PRIORITY_UPLOADS_PER_FRAME
                && (batch = this.chunkManager.getPlayerUploadQueue().poll()) != null) {
            this.applyProfiledBatch(batch);
            priorityUploads++;
        }
        while (priorityUploads < MAX_PRIORITY_UPLOADS_PER_FRAME
                && (batch = this.chunkManager.getPriorityUploadQueue().poll()) != null) {
            this.applyProfiledBatch(batch);
            priorityUploads++;
        }

        /* 2b. Normale Upload-Queue (Initial-Load), gedeckelt pro Frame */
        int uploads = 0;
        while (uploads < MAX_UPLOADS_PER_FRAME && (batch = this.chunkManager.getUploadQueue().poll()) != null) {
            this.applyProfiledBatch(batch);
            uploads++;
        }
        this.trackInitialUpload(uploads);

        /* 3. Meshes entladener Chunks freigeben (Regionen deferred) — nur in Frames, in denen
           der ChunkManager wirklich etwas entfernt hat (Removal-Version), statt jeden Frame
           alle Meshes gegen die Chunk-Map zu prüfen. update() (Tick) läuft vor renderSolid,
           der Walk greift also im selben Frame wie die Entfernung. */
        int removalVersion = this.chunkManager.getChunkRemovalVersion();
        if (removalVersion != this.lastChunkRemovalVersion) {
            this.lastChunkRemovalVersion = removalVersion;
            this.meshes.removeIf((key, mesh) -> {
                if (this.chunkManager.getChunks().containsKey(Chunk.key(mesh.chunkX, mesh.chunkZ))) return false;
                mesh.dispose(this.arenas, this.frameId);
                this.unregisterSectionMesh(mesh);
                return true;
            });
        }

        FrameProfiler.cpuStop(FrameProfiler.Cpu.UPLOAD);

        /* 4. Frustum culling, einmal pro Frame */
        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        FrameProfiler.cpuStart(FrameProfiler.Cpu.CULL);

        this.visible.clear();
        this.translucentVisible.clear();
        this.visibleDetail.clear();
        this.totalSections = this.meshes.size();

        int opaqueDraws = 0, cutoutDraws = 0;
        FrustumIntersection frustum = camera.getFrustum();
        for (int ci = 0, cn = this.cullColumns.tableSize(); ci < cn; ci++) {
            CullColumn col = this.cullColumns.valueAt(ci);
            if (col == null) continue;

            /* Spalten-AABB zuerst: nur bei Schnitt die einzelnen Sections testen. */
            float ox = (float) (((long) col.chunkX << ChunkSection.SHIFT) - cam.x);
            float oz = (float) (((long) col.chunkZ << ChunkSection.SHIFT) - cam.z);
            float cy0 = (float) (((long) col.minSy << ChunkSection.SHIFT) - cam.y);
            float cy1 = (float) (((long) (col.maxSy + 1) << ChunkSection.SHIFT) - cam.y);
            if (!frustum.testAab(ox, cy0, oz, ox + size, cy1, oz + size)) continue;

            for (int sy = col.minSy; sy <= col.maxSy; sy++) {
                SectionMesh mesh = col.sections[sy];
                if (mesh == null) continue;
                float oy = offsetY(mesh, cam);
                if (!frustum.testAab(ox, oy, oz, ox + size, oy + size, oz + size)) continue;
                this.visible.add(mesh);
                if (mesh.hasLayer(RenderLayer.OPAQUE)) opaqueDraws++;
                if (mesh.hasLayer(RenderLayer.CUTOUT)) cutoutDraws++;
                if (mesh.hasLayer(RenderLayer.TRANSLUCENT)) this.translucentVisible.add(mesh);
                if (mesh.hasDetail()) this.visibleDetail.add(mesh);
            }
        }
        this.renderedSections = this.visible.size();

        FrameProfiler.cpuStop(FrameProfiler.Cpu.CULL);

        /* 5. Kapazitäten sicherstellen (Uploads können Arenen/EBO gewachsen sein lassen) */
        this.ensureIndexCapacity(this.maxSeenQuads);
        this.ensureVaoBindings();

        int translucentDraws = this.translucentVisible.size();
        int detailDraws = this.visibleDetail.size();
        this.commandRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * COMMAND_BYTES)
                        + MappedRing.align((long) cutoutDraws * COMMAND_BYTES)
                        + MappedRing.align((long) detailDraws * COMMAND_BYTES)
                        + MappedRing.align((long) translucentDraws * COMMAND_BYTES));
        this.offsetRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * OFFSET_BYTES)
                        + MappedRing.align((long) cutoutDraws * OFFSET_BYTES)
                        + MappedRing.align((long) detailDraws * OFFSET_BYTES)
                        + MappedRing.align((long) translucentDraws * OFFSET_BYTES));

        /* 6. Command-/Offset-Segmente für OPAQUE und CUTOUT schreiben */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.WRITE);

        IntBuffer cmds = this.commandRing.intView(this.frameSlot);
        FloatBuffer offs = this.offsetRing.floatView(this.frameSlot);

        long cmdOpaque = 0, offOpaque = 0;
        int nOpaque = this.writeSegment(RenderLayer.OPAQUE, this.visible, cmds, offs, cmdOpaque, offOpaque, cam);

        long cmdCutout = cmdOpaque + MappedRing.align((long) nOpaque * COMMAND_BYTES);
        long offCutout = offOpaque + MappedRing.align((long) nOpaque * OFFSET_BYTES);
        int nCutout = this.writeSegment(RenderLayer.CUTOUT, this.visible, cmds, offs, cmdCutout, offCutout, cam);

        /* Kleinvegetations-Segment (CUTOUT-Arena, eigener Draw für die Distanz-Ausdünnung). */
        long cmdDetail = cmdCutout + MappedRing.align((long) nCutout * COMMAND_BYTES);
        long offDetail = offCutout + MappedRing.align((long) nCutout * OFFSET_BYTES);
        int nDetail = this.writeDetailSegment(cmds, offs, cmdDetail, offDetail, cam);

        /* Cursor für das Translucent-Segment in renderTranslucent merken */
        this.cmdCursor = cmdDetail + MappedRing.align((long) nDetail * COMMAND_BYTES);
        this.offCursor = offDetail + MappedRing.align((long) nDetail * OFFSET_BYTES);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.WRITE);

        /* 7. Render-Pässe: opaque & cutout (Alpha-Test bei 0.5) — je EIN Draw-Call.
           u_Textures ist einmalig gesetzt (init); Fog lädt nur bei Wertänderung hoch. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GLSUB);
        this.shader.bind();
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.setFogUniforms();
        this.setLightUniforms();
        this.bindMaterials();

        this.shader.setUniformf(this.locAlphaCutoff, 0.5F);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SOLID);
        this.drawSegment(RenderLayer.OPAQUE.ordinal(), cmdOpaque, offOpaque, nOpaque);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SOLID);
        /* CUTOUT mit "or-equal"-Depth-Func: die koplanaren Gras-Seiten-Overlays (identische
           Vertices wie ihre OPAQUE-Basis-Seite) muessen den Tiefentest exakt gewinnen.
           Reversed-Z: GREATER -> GEQUAL. Die Funcs kommen statisch aus EngineProperties
           statt per glGetInteger (synchroner Treiber-Roundtrip pro Frame). */
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.CUTOUT);
        this.drawSegment(RenderLayer.CUTOUT.ordinal(), cmdCutout, offCutout, nCutout);
        /* Kleinvegetation: gleicher Pass-State, aber mit aktiver Distanz-Ausdünnung — der
           Shader kollabiert ferne Pflanzen deterministisch per Pflanzen-Hash (int3-Topbyte).
           Danach Fade wieder deaktivieren, alle anderen Segmente bleiben unberührt. */
        if (nDetail > 0) {
            int start = GameSettings.get().vegetationDistance;
            if (start > 0) {
                /* Anker nachziehen (Chunk-Zentrum, mit Hysterese — s. Feld-Kommentar). */
                double dax = cam.x - this.detailAnchorX, daz = cam.z - this.detailAnchorZ;
                if (Double.isNaN(this.detailAnchorX)
                        || dax * dax + daz * daz > DETAIL_ANCHOR_HYSTERESE * DETAIL_ANCHOR_HYSTERESE) {
                    this.detailAnchorX = (Math.floorDiv((int) Math.floor(cam.x), ChunkSection.SIZE)
                            * ChunkSection.SIZE) + ChunkSection.SIZE / 2.0;
                    this.detailAnchorZ = (Math.floorDiv((int) Math.floor(cam.z), ChunkSection.SIZE)
                            * ChunkSection.SIZE) + ChunkSection.SIZE / 2.0;
                }
                float startBlocks = start << ChunkSection.SHIFT;
                this.shader.setUniformVector2f(this.locDetailFade, startBlocks, 1F / (startBlocks * 0.5F));
                /* Versatz Live-Kamera -> Anker (klein, float-exakt): der Shader rechnet die
                   Fade-Distanz damit vom Anker statt von der Kamera. */
                this.shader.setUniformVector2f(this.locDetailCamSnap,
                        (float) (cam.x - this.detailAnchorX), (float) (cam.z - this.detailAnchorZ));
            }
            this.drawSegment(RenderLayer.CUTOUT.ordinal(), cmdDetail, offDetail, nDetail);
            if (start > 0) this.shader.setUniformVector2f(this.locDetailFade, 0F, 0F);
        }
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.CUTOUT);
        GL11.glDepthFunc(properties.baseDepthFunc());

        this.shader.unbind();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GLSUB);
    }

    /**
     * Pass 3: translucent — zuletzt, mit Blending. Sections back-to-front (Command-Reihenfolge
     * = Zeichen-Reihenfolge im MDI), Quads innerhalb der Sections per
     * {@link SectionMesh#sortTranslucent} (Vanilla-Stil). Nutzt die in {@link #renderSolid}
     * befüllten visible-Listen und den Frame-Slot desselben Frames.
     */
    public void renderTranslucent(Camera camera) {
        Vector3d cam = camera.getPosition();

        FrameProfiler.cpuStart(FrameProfiler.Cpu.SORT);

        /* Nur die Sections mit Translucent-Layer sortieren, nicht die ganze visible-Liste.
           Key = float-Bits der Distanz (nicht-negativ → Bitmuster ordnungserhaltend) << 32 | Index;
           aufsteigend sortiert und rückwärts zurückgeschrieben = fern → nah wie zuvor. */
        int tn = this.translucentVisible.size();
        if (tn > 1) {
            if (this.translucentSortKeys.length < tn) {
                this.translucentSortKeys = new long[tn * 2];
                this.translucentSortScratch = new SectionMesh[tn * 2];
            }
            for (int i = 0; i < tn; i++) {
                SectionMesh mesh = this.translucentVisible.get(i);
                this.translucentSortScratch[i] = mesh;
                this.translucentSortKeys[i] = ((long) Float.floatToIntBits((float) distanceSq(mesh, cam)) << 32) | i;
            }
            Arrays.sort(this.translucentSortKeys, 0, tn);
            for (int i = 0; i < tn; i++) {
                this.translucentVisible.set(i, this.translucentSortScratch[(int) this.translucentSortKeys[tn - 1 - i]]);
            }
        }

        /* Per-Quad-Sortierung: nahe Sections zuerst (Liste ist fern -> nah). Sortierte Daten
           wandern in frische Arena-Regionen -> danach ggf. VAO neu binden (Arena-Wachstum). */
        VertexArena translucentArena = this.arenas[RenderLayer.TRANSLUCENT.ordinal()];
        camera.getDirection(this.translucentSortDirection);
        int sortBudget = MAX_TRANSLUCENT_SORTS_PER_FRAME;
        for (int i = this.translucentVisible.size() - 1; i >= 0 && sortBudget > 0; i--) {
            if (this.translucentVisible.get(i).sortTranslucent(camera, this.translucentSortDirection,
                    translucentArena, this.frameId)) sortBudget--;
        }
        this.ensureVaoBindings();

        FrameProfiler.cpuStop(FrameProfiler.Cpu.SORT);

        FrameProfiler.cpuStart(FrameProfiler.Cpu.WRITE);

        IntBuffer cmds = this.commandRing.intView(this.frameSlot);
        FloatBuffer offs = this.offsetRing.floatView(this.frameSlot);
        int n = this.writeSegment(RenderLayer.TRANSLUCENT, this.translucentVisible, cmds, offs, this.cmdCursor, this.offCursor, cam);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.WRITE);

        /* Gleiches Programm wie renderSolid im selben Frame: u_ProjectionView/Fog/u_Textures
           bleiben als Programm-Uniform-State erhalten (die Entity-/BlockEntity-Pässe dazwischen
           nutzen eigene Programme) — kein Re-Upload nötig. Nur die Textur-Unit neu binden,
           die Entity-Renderer binden dort ihre eigenen Texturen. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GLSUB);
        this.shader.bind();
        this.bindMaterials();

        GL11.glEnable(GL11.GL_BLEND);
        GL30.glDisablei(GL11.GL_BLEND, 1);
        this.shader.setUniformf(this.locAlphaCutoff, 0.001F);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.TRANSLUCENT);
        this.drawSegment(RenderLayer.TRANSLUCENT.ordinal(), this.cmdCursor, this.offCursor, n);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.TRANSLUCENT);
        GL11.glDisable(GL11.GL_BLEND);

        this.shader.unbind();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GLSUB);

        /* Frame-Ende: Fence schützt Ring-Slot und Arena-Regionen dieses Frames */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.SYNC);
        this.endFrame();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.SYNC);
    }

    /* ------------------------- Frame-Sync ------------------------- */

    private void beginFrame() {
        this.frameSlot = (int) (this.frameId % SLOTS);
        long fence = this.fences[this.frameSlot];
        if (fence != 0L) {
            if (FrameProfiler.isEnabled()) {
                /* Diagnose: erst blockierungsfreier Status-Poll (timeout 0, ohne Flush) —
                   unterscheidet „GPU war längst fertig" von echtem Warten auf die Timeline. */
                this.syncFrames++;
                int status = GL32.glClientWaitSync(fence, 0, 0L);
                if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
                    this.syncSignaled++;
                } else {
                    long t0 = System.nanoTime();
                    this.waitOnFence(fence);
                    long waited = System.nanoTime() - t0;
                    this.syncWaitNs += waited;
                    if (waited > this.syncWaitMaxNs) this.syncWaitMaxNs = waited;
                }
            } else {
                this.waitOnFence(fence);
            }
            GL32.glDeleteSync(fence);
            this.fences[this.frameSlot] = 0L;

            /* Frames werden in Reihenfolge fertig -> alles bis slotFrames[slot] ist durch. */
            long completed = this.slotFrames[this.frameSlot];
            for (VertexArena arena : this.arenas) arena.collect(completed);

        }
    }

    /** Blockierendes Warten auf den Fence (Flush-Bit: Commands sicher abgeschickt). */
    private void waitOnFence(long fence) {
        int status;
        do {
            status = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
        } while (status == GL32.GL_TIMEOUT_EXPIRED);
    }

    /**
     * Fence-Diagnose der letzten Sekunde (nur bei aktivem FrameProfiler, sonst null):
     * wie oft war der 3 Frames alte Fence beim Eintreffen schon signalisiert, und wie
     * lange hat das echte Warten im Schnitt/Maximum gedauert.
     */
    public String syncStatsLineAndReset() {
        if (this.syncFrames == 0) return null;
        long waitedFrames = this.syncFrames - this.syncSignaled;
        String line = "Fence: signalisiert %d/%d | Wartezeit avg=%dµs max=%dµs".formatted(
                this.syncSignaled, this.syncFrames,
                waitedFrames > 0 ? this.syncWaitNs / waitedFrames / 1000 : 0,
                this.syncWaitMaxNs / 1000);
        this.syncFrames = 0;
        this.syncSignaled = 0;
        this.syncWaitNs = 0;
        this.syncWaitMaxNs = 0;
        return line;
    }

    private void endFrame() {
        this.fences[this.frameSlot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        this.slotFrames[this.frameSlot] = this.frameId;
        this.frameId++;
    }

    /* ------------------------- MDI-Bausteine ------------------------- */

    /** Wendet einen Mesh-Batch an: alte Section-Regionen freigeben, neue allozieren. */
    private void applyProfiledBatch(ChunkManager.MeshBatch batch) {
        PerformanceProfiler profiler = PerformanceProfiler.get();
        int sections = Math.max(1, batch.results().size());
        /* Jede MeshBatch gehoert genau zu einem Chunk. Die komplette Queue-Latenz ist die
           reale Wartezeit des Batches; durch Sections zu teilen wuerde keine Latenz mehr
           beschreiben und kleine Batches mit grossen ungleich vergleichbar machen. */
        profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_UPLOAD_WAIT,
                batch.enqueuedAt());
        long started = profiler.begin();
        this.applyBatch(batch);
        if (started == 0 || !profiler.isEnabled()) return;
        long elapsed = System.nanoTime() - started;
        long perSection = elapsed / sections;
        for (int i = 0; i < sections; i++) {
            profiler.record(PerformanceProfiler.WorkerSection.L0_UPLOAD, perSection);
        }
    }

    private void applyBatch(ChunkManager.MeshBatch batch) {
        if (batch.renderGeneration() != this.renderGeneration
                || !this.chunkManager.isRenderGenerationActive(this.renderGeneration)) return;
        for (ChunkManager.MeshResult result : batch.results()) {
            Chunk chunk = this.chunkManager.getChunks().get(Chunk.key(result.chunkX(), result.chunkZ()));

            /* Aktualitätsprüfung: die uploadQueue (8/Frame) kann viele Sekunden Rückstand
               haben — ein dort wartendes Erst-Mesh darf ein bereits angewendetes, NEUERES
               Priority-Remesh derselben Section nicht überschreiben (das Dirty-Bit ist dann
               schon konsumiert, die falsche Geometrie bliebe bis zum nächsten Edit stehen). */
            if (chunk != null && !chunk.tryApplyMeshSection(this.renderGeneration,
                    result.sectionY(), result.meshSeq())) continue;

            if (chunk != null) {
                chunk.markSectionUploaded(this.renderGeneration, result.sectionY());
            }

            long key = sectionKey(result.chunkX(), result.sectionY(), result.chunkZ());

            SectionMesh old = this.meshes.remove(key);
            if (old != null) {
                old.dispose(this.arenas, this.frameId);
                this.unregisterSectionMesh(old);
            }

            /* Späte Batches entladener Chunks verwerfen (chunk == null): der Cleanup-Walk in
               Schritt 3 läuft nur noch bei Removal-Version-Wechsel — ein danach eingefügtes
               Waisen-Mesh würde nie wieder abgeräumt (Arena-Leak + Geistergeometrie). */
            if (chunk != null && result.data() != null && !result.data().isEmpty()) {
                SectionMesh mesh = new SectionMesh(result.chunkX(), result.sectionY(), result.chunkZ(), result.data(), this.arenas);
                this.meshes.put(key, mesh);
                this.registerSectionMesh(mesh);
                this.maxSeenQuads = Math.max(this.maxSeenQuads, mesh.maxQuads());
            }
        }
    }

    /**
     * Startdiagnose: misst, wann die beim Lade-Fixpunkt offenen Erst-Mesh-Batches abgearbeitet
     * sind — das ist der Moment, ab dem das echte Terrain wirklich vollstaendig steht (der
     * Ladebildschirm schliesst deutlich frueher, naemlich schon am Latch selbst).
     *
     * <p>Bewusst NICHT ueber {@code uploadQueue.isEmpty()}: die Queue ist beim Weltstart
     * zunaechst leer und bekommt nach jeder Spielerbewegung wieder Arbeit — ein Leerlauf-Test
     * wuerde also mal zu frueh und mal nie ausloesen. Gezaehlt wird stattdessen gegen den
     * Snapshot aus {@link ChunkManager#initialUploadBacklog()}; die Messung ist einmalig und
     * setzt fuer den Benchmark einen stehenden Spieler voraus.
     */
    private void trackInitialUpload(int applied) {
        /* Zyklus-Abgleich MUSS vor dem Early-Return stehen: sonst verhindert genau der alte
           Messzustand sein eigenes Zuruecksetzen und "Chunks neu laden" misst nie wieder. */
        int cycle = this.chunkManager.loadCycle();
        if (cycle != this.initialUploadCycle) {
            this.initialUploadCycle = cycle;
            this.initialUploadMeasured = false;
            this.initialUploadRemaining = -1;
        }
        if (this.initialUploadMeasured) return;
        if (this.initialUploadRemaining < 0) {
            if (!this.chunkManager.isInitialLoadComplete()) return;
            this.initialUploadRemaining = this.chunkManager.initialUploadBacklog();
        }
        this.initialUploadRemaining -= applied;
        if (this.initialUploadRemaining > 0) return;
        this.initialUploadMeasured = true;
        this.logger.debug("L0-Initial-Upload fertig nach "
                + ((System.nanoTime() - this.chunkManager.loadStartNanos()) / 1_000_000L)
                + " ms (ab Weltbetreten)");
    }

    /**
     * Schreibt für alle Sections der Liste, die den Layer haben, Indirect-Command + Offset
     * ins aktuelle Frame-Slot-Segment (Byte-Cursor relativ zum Slot-Anfang).
     *
     * @return Anzahl geschriebener Draws
     */
    private int writeSegment(RenderLayer layer, List<SectionMesh> list, IntBuffer cmds, FloatBuffer offs,
                             long cmdSegBytes, long offSegBytes, Vector3d cam) {
        int cmdBase = (int) (cmdSegBytes / Integer.BYTES);
        int offBase = (int) (offSegBytes / Float.BYTES);
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            SectionMesh mesh = list.get(i);
            if (!mesh.hasLayer(layer)) continue;

            int ci = cmdBase + n * 5;
            cmds.put(ci, mesh.indexCount(layer));       // count
            cmds.put(ci + 1, 1);                        // instanceCount
            cmds.put(ci + 2, 0);                        // firstIndex (geteilter EBO ab 0)
            cmds.put(ci + 3, mesh.baseVertex(layer));   // baseVertex = Arena-Region
            cmds.put(ci + 4, 0);                        // baseInstance (ungenutzt, gl_DrawID reicht)

            int oi = offBase + n * 4;
            offs.put(oi, offsetX(mesh, cam));
            offs.put(oi + 1, offsetY(mesh, cam));
            offs.put(oi + 2, offsetZ(mesh, cam));
            offs.put(oi + 3, 0F);
            n++;
        }
        return n;
    }

    /**
     * Schreibt die Kleinvegetations-Draws (Detail-Region in der CUTOUT-Arena) der sichtbaren
     * Sections als eigenes Segment — analog {@link #writeSegment}, aber über die
     * Detail-Accessoren der SectionMesh.
     *
     * @return Anzahl geschriebener Draws
     */
    private int writeDetailSegment(IntBuffer cmds, FloatBuffer offs, long cmdSegBytes, long offSegBytes,
                                   Vector3d cam) {
        int cmdBase = (int) (cmdSegBytes / Integer.BYTES);
        int offBase = (int) (offSegBytes / Float.BYTES);
        int n = 0;
        for (int i = 0; i < this.visibleDetail.size(); i++) {
            SectionMesh mesh = this.visibleDetail.get(i);

            int ci = cmdBase + n * 5;
            cmds.put(ci, mesh.indexCountDetail());      // count
            cmds.put(ci + 1, 1);                        // instanceCount
            cmds.put(ci + 2, 0);                        // firstIndex (geteilter EBO ab 0)
            cmds.put(ci + 3, mesh.baseVertexDetail());  // baseVertex = Region in der CUTOUT-Arena
            cmds.put(ci + 4, 0);                        // baseInstance (ungenutzt)

            int oi = offBase + n * 4;
            offs.put(oi, offsetX(mesh, cam));
            offs.put(oi + 1, offsetY(mesh, cam));
            offs.put(oi + 2, offsetZ(mesh, cam));
            offs.put(oi + 3, 0F);
            n++;
        }
        return n;
    }

    /**
     * Ein glMultiDrawElementsIndirect-Call für ein Arena-/VAO-Segment des aktuellen Frame-Slots.
     * {@code slot} indiziert {@link #vaos}/{@link #arenas} per RenderLayer-Ordinal.
     */
    private void drawSegment(int slot, long cmdSegBytes, long offSegBytes, int drawCount) {
        if (drawCount == 0) return;

        GL30.glBindVertexArray(this.vaos[slot]);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.commandRing.getBuffer());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.offsetRing.getBuffer(),
                this.offsetRing.slotOffset(this.frameSlot) + offSegBytes, (long) drawCount * OFFSET_BYTES);
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                this.commandRing.slotOffset(this.frameSlot) + cmdSegBytes, drawCount, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Geteilter Quad-Index-Buffer: wächst auf die größte je gesehene Quad-Anzahl einer
     * Section. Nach Neubau binden die VAOs den neuen EBO in {@link #ensureVaoBindings}.
     */
    private void ensureIndexCapacity(int quads) {
        if (this.sharedEbo != 0 && quads <= this.indexCapacityQuads) return;

        int newCapacity = Math.max(32768, Integer.highestOneBit(Math.max(1, quads - 1)) << 1);
        /* Neuen EBO VOR dem Löschen des alten erzeugen (wie VertexArena.grow): solange der
           alte Name lebt, ist der neue garantiert verschieden — sonst recycelt der Treiber
           den Namen, ensureVaoBindings hält die Bindung für aktuell und die VAOs zeigen
           weiter auf das alte, zu kleine EBO-Objekt (Garbage-Indizes hinter dessen Ende). */
        int oldEbo = this.sharedEbo;
        this.sharedEbo = createQuadEbo(newCapacity, "Geteilter Quad-EBO (" + newCapacity + " Quads)");
        if (oldEbo != 0) {
            GL15.glDeleteBuffers(oldEbo);
            this.logger.debug("Quad-EBO gewachsen: " + this.indexCapacityQuads + " -> " + newCapacity + " Quads");
        }
        this.indexCapacityQuads = newCapacity;
    }

    /** Erzeugt einen nullbasierten Quad-EBO. Aufruf nur ohne gebundenes VAO. */
    private static int createQuadEbo(int quads, String label) {
        int[] indices = new int[Math.multiplyExact(quads, 6)];
        for (int q = 0, i = 0; q < quads; q++) {
            int v = q * 4;
            indices[i++] = v;
            indices[i++] = v + 1;
            indices[i++] = v + 2;
            indices[i++] = v + 2;
            indices[i++] = v + 3;
            indices[i++] = v;
        }
        int ebo = GL15.glGenBuffers();
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GlDebug.labelBuffer(ebo, label);
        return ebo;
    }

    /** Bindet Arena-Buffer + EBO in die Layer-VAOs neu, falls sich einer geändert hat (Wachstum). */
    private void ensureVaoBindings() {
        for (int i = 0; i < this.vaos.length; i++) {
            int arenaBuffer = this.arenas[i].getBuffer();
            int ebo = this.sharedEbo;
            if (this.vaoArenaBuffer[i] == arenaBuffer && this.vaoEbo[i] == ebo) continue;

            GL30.glBindVertexArray(this.vaos[i]);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arenaBuffer);
            int stride = ChunkMesher.VERTEX_SIZE * Integer.BYTES;
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_INT, stride, 0);
            GL20.glEnableVertexAttribArray(0);
            /* Attribut 1 = der 5. Int (Licht). Ein 5-komponentiges Attribut gibt es nicht,
               daher ein zweites 1-komponentiges bei Offset 16 — Stride bleibt derselbe. */
            GL30.glVertexAttribIPointer(1, 1, GL11.GL_UNSIGNED_INT, stride, 16);
            GL20.glEnableVertexAttribArray(1);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GL30.glBindVertexArray(0);

            this.vaoArenaBuffer[i] = arenaBuffer;
            this.vaoEbo[i] = ebo;
        }
    }

    /** Setzt die Fog-Uniforms für den gebundenen Chunk-Shader. Fog blendet Terrain
        Richtung Clear-Color (= Himmel) und nimmt dem Horizont damit den Kontrast, der das
        Sub-Pixel-Flimmern verursacht. Bezugsgröße ist die Chunk-Ladekante. */
    private void setFogUniforms() {
        GameSettings settings = GameSettings.get();
        float fogStart, fogEnd;
        if (this.environment.forceFog()) {
            fogStart = this.environment.fogStart();
            fogEnd = this.environment.fogEnd();
        } else if (settings.fog) {
            float range = settings.renderDistance * ChunkSection.SIZE;
            /* Fog-Ende ~3 Chunks VOR die theoretische Grenze: der Lade-Kreis ist auf ganze
               Chunks quantisiert und der Spieler steht bis zu 31 Blöcke neben seinem
               Chunk-Ursprung — die sichtbare Kante liegt daher bis zu ~2-3 Chunks innerhalb
               von rd*32. Ohne diesen Rand bleibt die Stufen-Silhouette der Ladegrenze bei
               kurzer Fog-Spanne sichtbar. */
            fogEnd = Math.max(range - 3 * ChunkSection.SIZE, 2 * ChunkSection.SIZE);
            fogStart = fogEnd * 0.80F;
        } else {
            /* Fog aus: Start/Ende jenseits jeder Distanz -> Faktor 0, keine Shader-Variante nötig */
            fogStart = 1.0e30F;
            fogEnd = 2.0e30F;
        }
        Color4 clear = SkyEngine.get().getConfig().getWindowClearColor();
        float fogRed = this.environment.forceFog() ? this.environment.fogRed() : clear.red;
        float fogGreen = this.environment.forceFog() ? this.environment.fogGreen() : clear.green;
        float fogBlue = this.environment.forceFog() ? this.environment.fogBlue() : clear.blue;

        /* Upload nur bei Änderung — die Werte hängen nur an Settings/Clear-Color, nicht an
           der Kamera. Uniform-State bleibt im Programm erhalten. */
        if (fogStart == this.lastFogStart && fogEnd == this.lastFogEnd
                && fogRed == this.lastFogR && fogGreen == this.lastFogG && fogBlue == this.lastFogB) {
            return;
        }
        this.shader.setUniformf(this.locFogStart, fogStart);
        this.shader.setUniformf(this.locFogEnd, fogEnd);
        this.shader.setUniformVector3f(this.locFogColor, fogRed, fogGreen, fogBlue);
        this.lastFogStart = fogStart;
        this.lastFogEnd = fogEnd;
        this.lastFogR = fogRed;
        this.lastFogG = fogGreen;
        this.lastFogB = fogBlue;
    }

    /**
     * Setzt die Untergrenze des Himmelslichts aus dem Helligkeits-Setting. Reicht EINMAL pro
     * Frame in {@code renderSolid}: alle Pässe (Cutout, Detail, Translucent) teilen sich
     * dasselbe Shader-Programm, und Uniform-State bleibt darin erhalten.
     */
    private void setLightUniforms() {
        int brightness = GameSettings.get().brightness;
        /* AUS = Fullbright: minLight 1.0 macht das Ergebnis unabhängig von allem anderen exakt
           1.0, der Regler-Wert ist dann egal (auf 0 gesetzt, damit der Cache eindeutig bleibt). */
        boolean fullbright = brightness <= 0;
        float minLight = fullbright ? 1.0F : this.environment.ambientLight();
        float gamma = fullbright ? 0.0F : brightness / 100F;
        if (minLight == this.lastMinLight && gamma == this.lastBrightness) return;
        this.shader.setUniformf(this.locMinLight, minLight);
        this.shader.setUniformf(this.locBrightness, gamma);
        this.lastMinLight = minLight;
        this.lastBrightness = gamma;
    }

    /**
     * Licht-Level 0..15 (Himmel und Block, der hellere gewinnt) → Helligkeitsfaktor, <b>exakt
     * dieselbe Kurve wie im Fragment-Shader</b> (max, dann Lichtkurve, dann Ambient-Boden, dann
     * Regler-Kurve). Für Objekte ohne gebackenes Vertex-Licht — Spieler, Item-Drops, Item in der
     * Hand, BlockEntities: dort ist das Licht pro Draw konstant, also rechnet die CPU den fertigen
     * Faktor und die Renderer brauchen nur ein skalares Uniform statt vier Kopien derselben
     * GLSL-Kurve.
     *
     * <p>Diese Methode und der Shader müssen zusammen geändert werden — laufen sie auseinander,
     * sitzt eine Truhe sichtbar heller oder dunkler in ihrer Wand als das Terrain daneben.
     *
     * <p>Dass der Shader das Maximum erst nach der Interpolation bildet und die CPU schon davor,
     * macht keinen Unterschied: hier sind beide Werte pro Draw konstant.
     *
     * @return 1.0 bei Fullbright (Regler AUS) und bei Lichtlevel 15
     */
    public static float lightFactor(int skyLevel, int blockLevel) {
        return lightFactor(skyLevel, blockLevel, AMBIENT_LIGHT);
    }

    public static float lightFactor(int skyLevel, int blockLevel, float ambientLight) {
        int brightness = GameSettings.get().brightness;
        if (brightness <= 0) return 1.0F; // Fullbright
        float f = Math.clamp(Math.max(skyLevel, blockLevel), 0, 15) / 15.0F;
        float light = f / (4.0F - 3.0F * f);
        light = ambientLight + (1.0F - ambientLight) * light;
        float inv = 1.0F - light;
        float inv2 = inv * inv;
        float lifted = 1.0F - inv2 * inv2;
        return light + (lifted - light) * (brightness / 100F); // = mix(light, lifted, brightness)
    }

    /* ------------------------- Helfer ------------------------- */

    /** Debug-Label für einen Arena-/VAO-Slot. */
    private static String slotLabel(int slot) {
        return RenderLayer.VALUES[slot].toString();
    }

    private static float offsetX(SectionMesh mesh, Vector3d cam) {
        return (float) (((long) mesh.chunkX << ChunkSection.SHIFT) - cam.x);
    }

    private static float offsetY(SectionMesh mesh, Vector3d cam) {
        return (float) (((long) mesh.sectionY << ChunkSection.SHIFT) - cam.y);
    }

    private static float offsetZ(SectionMesh mesh, Vector3d cam) {
        return (float) (((long) mesh.chunkZ << ChunkSection.SHIFT) - cam.z);
    }

    /* ------------------------- Cull-Index-Pflege (Spalten/Kacheln) ------------------------- */

    /** Meldet ein Section-Mesh beim Spalten-Cull-Index an. */
    private void registerSectionMesh(SectionMesh mesh) {
        this.columnAdd(mesh);
    }

    private void unregisterSectionMesh(SectionMesh mesh) {
        this.columnRemove(mesh);
    }

    private CullColumn columnAdd(SectionMesh mesh) {
        long key = Chunk.key(mesh.chunkX, mesh.chunkZ);
        CullColumn col = this.cullColumns.get(key);
        if (col == null) {
            col = new CullColumn(mesh.chunkX, mesh.chunkZ);
            this.cullColumns.put(key, col);
        }
        if (col.sections[mesh.sectionY] == null) col.count++;
        col.sections[mesh.sectionY] = mesh;
        if (col.count == 1) {
            col.minSy = mesh.sectionY;
            col.maxSy = mesh.sectionY;
        } else {
            col.minSy = Math.min(col.minSy, mesh.sectionY);
            col.maxSy = Math.max(col.maxSy, mesh.sectionY);
        }
        return col;
    }

    private void columnRemove(SectionMesh mesh) {
        long key = Chunk.key(mesh.chunkX, mesh.chunkZ);
        CullColumn col = this.cullColumns.get(key);
        if (col == null || col.sections[mesh.sectionY] == null) return;
        col.sections[mesh.sectionY] = null;
        if (--col.count == 0) {
            this.cullColumns.remove(key);
            return;
        }
        /* min/max nur bei Entfernung neu bestimmen (16 Slots, selten) */
        int lo = Chunk.SECTIONS, hi = -1;
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            if (col.sections[sy] != null) {
                if (sy < lo) lo = sy;
                hi = sy;
            }
        }
        col.minSy = lo;
        col.maxSy = hi;
    }

    /** Abstand eines Section-Mesh-Zentrums zur Kamera. */
    private static double distanceSq(SectionMesh mesh, Vector3d cam) {
        double cx = ((long) mesh.chunkX << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.x;
        double cy = ((long) mesh.sectionY << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.y;
        double cz = ((long) mesh.chunkZ << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.z;
        return cx * cx + cy * cy + cz * cz;
    }

    /** Section-Key im geteilten 26/26/12-Layout ({@code BlockPos.asLong}; y = sectionY 0..15). */
    private static long sectionKey(int x, int y, int z) {
        return de.skyengine.game.world.block.BlockPos.asLong(x, y, z);
    }

    private void bindMaterials() {
        this.textures.bind(0);
        boolean enabled = this.atlas != null && this.atlas.hasMaterials();
        this.shader.setUniformi(this.locPbrEnabled, enabled ? 1 : 0);
        if (enabled) {
            this.atlas.normals().bind(1);
            this.atlas.materials().bind(2);
        }
    }

    public void dispose() {
        for (int i = 0, n = this.meshes.tableSize(); i < n; i++) {
            SectionMesh mesh = this.meshes.valueAt(i);
            if (mesh != null) mesh.dispose(this.arenas, this.frameId);
        }
        this.meshes.clear();
        this.cullColumns.clear();
        for (long fence : this.fences) {
            if (fence != 0L) GL32.glDeleteSync(fence);
        }
        for (int vao : this.vaos) GL30.glDeleteVertexArrays(vao);
        if (this.sharedEbo != 0) GL15.glDeleteBuffers(this.sharedEbo);
        if (this.commandRing != null) this.commandRing.dispose();
        if (this.offsetRing != null) this.offsetRing.dispose();
        for (VertexArena arena : this.arenas) {
            if (arena != null) arena.dispose();
        }
        /* Atlas (textures/animations) NICHT disposen — gehört dem GameContainer und
           überlebt Welt-Austritte (Hauptmenü braucht ihn für Item-Icons). */
        if (this.shader != null) this.shader.dispose();
    }

    /* Gepacktes Vertex-Format (20 Bytes, siehe ChunkMesher.VERTEX_SIZE):
       x: posX | posY<<16 — y: posZ | u<<16. Section-Positionen nutzen Bias +1;
       UV ist fixed 6.10 mit Bias +1.
       z: v | layer<<16 — w: rgb8
       5. Int: Licht in Bits 0-15, Vertex-Flags ab Bit 16 (siehe ChunkMesher) — Stride wächst
       automatisch über ChunkMesher.VERTEX_SIZE, a_data liest weiterhin nur die ersten 4 Ints
       Section-Origin (kamerarelativ) kommt pro Draw aus dem SSBO, indiziert via gl_DrawID. */
    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in uvec4 a_data;
            /* 5. Int: Skylight Bits 0-7, Blocklight 8-15, Vertex-Flags ab Bit 16. */
            layout(location = 1) in uint a_light;

            const uint FLAT_SOURCE_FLUID_TOP = %du;
            const float SOURCE_FLUID_RENDER_HEIGHT = %s;

            layout(std430, binding = 0) readonly buffer DrawOffsets {
                vec4 u_DrawOffsets[];
            };

            uniform mat4 u_ProjectionView;
            /* Kleinvegetations-Ausduennung: x = Start-Distanz (Bloecke), y = 1/Fade-Spanne.
               y <= 0 = aus; nur waehrend des Detail-Segment-Draws aktiv. */
            uniform vec2 u_DetailFade;
            /* Versatz Live-Kamera -> Ausduennungs-Anker (Chunk-Zentrum mit Hysterese):
               die Fade-Distanz haengt damit NICHT an der kontinuierlichen Bewegung
               (kein Dauer-Getroepfel einzelner Pflanzen, nur seltene Batch-Pops). */
            uniform vec2 u_DetailCamSnap;

            out vec3 v_texCoord;
            out vec3 v_color;
            out vec2 v_light;   // x = Himmelslicht, y = Blocklicht (je 0..1)
            out float v_viewDist;
            out vec3 v_relPos;

            void main() {
                vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16),
                        float(a_data.y & 0xFFFFu)) * (1.0 / 1024.0);
                pos.xz -= 1.0;
                pos.y -= 1.0;
                /* Dieselbe Quelloberfläche wird mit drei verschiedenen Fixed-Point-Skalen
                   gepackt. Ihre fraktionale Y-Komponente deshalb analytisch rekonstruieren;
                   ganzzahliger Draw-Ursprung + identische Fraktion = exakt koplanar. */
                if ((a_light & FLAT_SOURCE_FLUID_TOP) != 0u) {
                    pos.y = floor(pos.y) + SOURCE_FLUID_RENDER_HEIGHT;
                }
                vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu)) * (1.0 / 1024.0) - 1.0;
                float layer = float(a_data.z >> 16);
                vec3 color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu), float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);

                v_texCoord = vec3(uv, layer);
                v_color = color;
                /* Himmels- und Blocklicht je 0..1, interpoliert -> weiche Verlaeufe (Smooth
                   Lighting). Muss VOR dem Ausduennungs-Block stehen, der mit return aussteigt. */
                v_light = vec2(float(a_light & 0xFFu), float((a_light >> 8) & 0xFFu)) * (1.0 / 255.0);
                /* Positionen sind kamerarelativ und welt-achsen-ausgerichtet -> length(rel.xz) =
                   horizontale Sichtdistanz fuer ZYLINDRISCHEN Fog (wie MC 1.18+): Hochfliegen
                   schiebt das Terrain unter dem Spieler nicht in den Nebel, die horizontale
                   Ladekante bleibt verdeckt. Rotationsinvariant, kein "Atmen" beim Umschauen. */
                vec3 rel = pos + u_DrawOffsets[gl_DrawID].xyz;
                v_viewDist = length(rel.xz);
                v_relPos = rel;

                /* Distanz-Ausduennung der Kleinvegetation: der Pflanzen-Hash (Topbyte von
                   int3, pro Pflanze identisch — beide Tall-Grass-Haelften teilen ihn) wird
                   gegen die distanzabhaengige Dichte getestet; verlierende Pflanzen
                   kollabieren zu degenerierten Quads (Punkt ausserhalb des Clip-Volumens).
                   Die Distanz kommt aus dem SECTION-Zentrum (Draw-Offset) — fuer alle
                   Vertices eines Quads identisch. Per-Vertex-Distanz wuerde nahe der
                   Schwelle einzelne Ecken kollabieren -> himmelweite Sliver-Dreiecke. */
                if (u_DetailFade.y > 0.0) {
                    float sectionDist = length(u_DrawOffsets[gl_DrawID].xz + vec2(16.0) + u_DetailCamSnap);
                    float dichte = 1.0 - clamp((sectionDist - u_DetailFade.x) * u_DetailFade.y, 0.0, 1.0);
                    if (float((a_data.w >> 24) & 0xFFu) > dichte * 255.0) {
                        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                        return;
                    }
                }

                gl_Position = u_ProjectionView * vec4(rel, 1.0);
            }
            """.formatted(ChunkMesher.FLAT_SOURCE_FLUID_TOP,
                    Float.toString(FluidGeometry.SOURCE_RENDER_HEIGHT));

    static final String FRAGMENT_SOURCE = """
            #version 460 core
            in vec3 v_texCoord;
            in vec3 v_color;
            in vec2 v_light;    // x = Himmelslicht, y = Blocklicht (je 0..1)
            in float v_viewDist;
            in vec3 v_relPos;

            uniform sampler2DArray u_Textures;
            uniform sampler2DArray u_NormalTextures;
            uniform sampler2DArray u_MaterialTextures;
            uniform int u_PbrEnabled;
            uniform float u_AlphaCutoff;
            uniform vec3 u_FogColor;
            uniform float u_FogStart;
            uniform float u_FogEnd;
            /* Grundhelligkeit bei Lichtlevel 0. 1.0 = Fullbright: dann ist das Ergebnis fuer
               JEDES v_light exakt 1.0, also bit-identisch zum Bild ohne Lichtsystem — deshalb
               braucht Fullbright weder Shader-Zweig noch Remesh. */
            uniform float u_MinLight;
            /* Helligkeitsregler 0..1 (Minecraft-Brightness). */
            uniform float u_Brightness;
            layout(location = 0) out vec4 fragColor;

            /* Minecraft-Helligkeitskurve (lightBrightnessTable): staucht mittlere Licht-Level
               nach unten. Linear waere der Abfall fast ueberall zu grell. */
            float lightCurve(float f) { return f / (4.0 - 3.0 * f); }

            mat3 cotangentFrame(vec3 n, vec3 p, vec2 uv) {
                vec3 dp1 = dFdx(p), dp2 = dFdy(p);
                vec2 duv1 = dFdx(uv), duv2 = dFdy(uv);
                vec3 dp2perp = cross(dp2, n);
                vec3 dp1perp = cross(n, dp1);
                vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
                vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
                float invmax = inversesqrt(max(max(dot(t, t), dot(b, b)), 1e-8));
                return mat3(t * invmax, b * invmax, n);
            }

            float distributionGGX(vec3 n, vec3 h, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float nh = max(dot(n, h), 0.0);
                float d = nh * nh * (a2 - 1.0) + 1.0;
                return a2 / max(3.14159265 * d * d, 1e-5);
            }

            float geometrySchlick(float nv, float roughness) {
                float r = roughness + 1.0;
                float k = r * r * 0.125;
                return nv / max(nv * (1.0 - k) + k, 1e-5);
            }

            void main() {
                vec4 color = texture(u_Textures, v_texCoord);
                if (color.a < u_AlphaCutoff) discard;
                /* Monochrom wie in Minecraft: der hellere der beiden Werte gewinnt. Erst DANACH
                   die Kurve — die Reihenfolge darunter bleibt unangetastet. */
                float light = lightCurve(clamp(max(v_light.x, v_light.y), 0.0, 1.0));
                /* Ambient-Boden ZUERST — er ist der Wert, den der Regler anheben soll. Stuende
                   die Kurve davor, bekaeme sie bei Lichtlevel 0 eine Null herein und gaebe eine
                   Null heraus (1 - 1^4 = 0): der Regler waere in der dunkelsten Hoehle exakt
                   wirkungslos, also genau dort, wo man ihn braucht. */
                light = u_MinLight + (1.0 - u_MinLight) * light;
                /* Danach die Kurve. Der Regler wirkt als KURVE, nicht als Summand: 1-(1-x)^4
                   hebt das dunkle Ende kraeftig an und laesst die Fixpunkte 0 und 1 stehen —
                   Verlaeufe bleiben erhalten und die Oberflaeche (Licht 15) bleibt exakt 1.0.
                   Ein flacher Boden verschoebe dagegen nur alles gleichmaessig und drueckte die
                   Abstufungen platt, was Hoehlen als gleichfoermigen Matsch erscheinen laesst.
                   inv2*inv2 statt pow(): identisches Ergebnis, billiger, keine pow-Randfaelle. */
                float inv = 1.0 - light;
                float inv2 = inv * inv;
                light = mix(light, 1.0 - inv2 * inv2, u_Brightness);
                /* Clamp gegen Attribut-EXTRApolation: kantenparallel gesehene Faces rastern als
                   degenerierte Sliver-Dreiecke, deren Interpolation die per-Vertex-AO-Farben
                   ueber 1.0 hinaus extrapoliert -> helle Funkel-Striche auf Augenhoehe. */
                vec3 lit = color.rgb * clamp(v_color, 0.0, 1.0) * light;
                if (u_PbrEnabled != 0) {
                    vec4 normalTex = texture(u_NormalTextures, v_texCoord);
                    vec4 material = texture(u_MaterialTextures, v_texCoord);
                    if (normalTex.a > 0.0 || material.a > 0.0) {
                        vec3 geometric = normalize(cross(dFdx(v_relPos), dFdy(v_relPos)));
                        if (!gl_FrontFacing) geometric = -geometric;
                        vec3 n = geometric;
                        if (normalTex.a > 0.0) {
                            n = normalize(cotangentFrame(geometric, v_relPos, v_texCoord.xy)
                                    * (normalTex.rgb * 2.0 - 1.0));
                        }
                        float roughness = material.a > 0.0 ? clamp(material.r, 0.04, 1.0) : 1.0;
                        float metallic = material.a > 0.0 ? material.g : 0.0;
                        float emission = material.a > 0.0 ? material.b : 0.0;
                        vec3 l = normalize(vec3(-0.35, 0.80, 0.45));
                        vec3 v = normalize(-v_relPos);
                        vec3 h = normalize(l + v);
                        float nl = max(dot(n, l), 0.0);
                        float nv = max(dot(n, v), 0.001);
                        float hv = max(dot(h, v), 0.0);
                        float skyShare = v_light.x / max(v_light.x + v_light.y, 0.001);
                        float normalShade = mix(1.0, 0.35 + 0.65 * nl, skyShare);
                        vec3 albedo = color.rgb * clamp(v_color, 0.0, 1.0);
                        vec3 f0 = mix(vec3(0.04), albedo, metallic);
                        vec3 f = f0 + (1.0 - f0) * pow(1.0 - hv, 5.0);
                        float d = distributionGGX(n, h, roughness);
                        float g = geometrySchlick(nv, roughness) * geometrySchlick(max(dot(n, l), 0.001), roughness);
                        vec3 specular = d * g * f / max(4.0 * nv * max(nl, 0.001), 0.001);
                        lit = albedo * (1.0 - metallic) * light * normalShade
                                + specular * light * nl * skyShare
                                + color.rgb * emission;
                    }
                }
                /* Linearer Distanz-Fog Richtung Clear-Color: nimmt dem Horizont den Kontrast
                   (Sub-Pixel-Flimmern des Fernterrains) und versteckt die Far-Plane-Kante. */
                float fog = clamp((v_viewDist - u_FogStart) / (u_FogEnd - u_FogStart), 0.0, 1.0);
                vec3 finalRgb = mix(lit, u_FogColor, fog);
                fragColor = vec4(finalRgb, color.a);
            }
            """;

    /** Das Block-TextureArray (von der GUI für Item-Icons mitgenutzt). Erst nach {@link #init} gültig. */
    public TextureArray getTextureArray() {
        return textures;
    }

    public int getRenderedSections() {
        return renderedSections;
    }

    public int getTotalSections() {
        return totalSections;
    }

    /** Belegte Bytes aller Vertex-Arenen (Debug/Statistik). */
    public long getArenaUsedBytes() {
        long used = 0;
        for (VertexArena arena : this.arenas) used += arena.getUsedBytes();
        return used;
    }
}
