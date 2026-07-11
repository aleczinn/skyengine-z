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
import de.skyengine.game.world.lod.LodConfig;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.lod.LodMesher;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.SpriteAnimations;
import de.skyengine.graphics.texture.TextureArray;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
    private ShaderProgram shader;
    private TextureArray textures;
    private SpriteAnimations animations;
    private long lastAnimNanos;

    /* sectionKey -> mesh, render thread only */
    private final Map<Long, SectionMesh> meshes = new HashMap<>();

    /* Pro Frame neu befüllt: alle Sections, die den Frustum-Test bestanden haben */
    private final List<SectionMesh> visible = new ArrayList<>();

    /* Teilmenge von visible mit TRANSLUCENT-Layer - nur diese werden back-to-front sortiert */
    private final List<SectionMesh> translucentVisible = new ArrayList<>();

    /* --- Heightmap-LOD: zusätzliche Draws im OPAQUE-Segment (gleiche Arena, gleicher Shader) --- */

    /* regionKey -> LOD-Mesh, render thread only. null-Manager = LOD aus (rendert wie bisher). */
    private final Map<Long, LodMesh> lodMeshes = new HashMap<>();
    private final List<LodMesh> visibleLod = new ArrayList<>();
    private LodManager lodManager;

    private static final int MAX_LOD_UPLOADS_PER_FRAME = 4;

    /* Letzter LOD-Settings-Stand für die Arena-Vorabvergrößerung (s. applyLodResults) */
    private int lastLodRenderDistance = -1, lastLodMaxDistance = -1;
    private boolean lastLodEnabled;

    /* Gate für die Cleanup-Walks (Schritte 3/3b): die O(Meshes)-Prüfungen laufen nur noch
       in Frames, in denen sich Chunk-Set bzw. LOD-Desired-Set wirklich geändert haben
       (gemessen ~250 µs/Frame gespart im Steady-State). -1 = beim ersten Frame laufen. */
    private int lastChunkRemovalVersion = -1;
    private int lastLodDesiredVersion = -1;

    /* Fence-Diagnose (nur bei aktivem FrameProfiler gefüllt, s. beginFrame) */
    private long syncFrames, syncSignaled, syncWaitNs, syncWaitMaxNs;

    private static final int MAX_UPLOADS_PER_FRAME = 8;

    /* Deckelt Quad-Sorts pro Frame — bei Kamerabewegung wollen sonst alle sichtbaren
       Translucent-Sections gleichzeitig neu sortieren (Ozean -> Upload-Spike). */
    private static final int MAX_TRANSLUCENT_SORTS_PER_FRAME = 8;

    private static final int TEXTURE_SIZE = 16;

    /* --- MDI-Infrastruktur --- */

    /* Pseudo-Layer-Indizes für LOD: eigene Arena + eigener Draw-Call (volle Isolation von
       echtem Terrain), aber dasselbe Alloc/Free/Collect-/VAO-Binding-Schema wie die drei
       echten RenderLayer — die generischen Schleifen über arenas/vaos erfassen sie automatisch. */
    private static final int LOD_OPAQUE = RenderLayer.VALUES.length;
    private static final int LOD_TRANSLUCENT = RenderLayer.VALUES.length + 1;
    private static final int ARENA_SLOTS = RenderLayer.VALUES.length + 2;

    /* Eine Arena + ein VAO pro RenderLayer (Index = ordinal) + 2 dedizierte LOD-Slots */
    private final VertexArena[] arenas = new VertexArena[ARENA_SLOTS];
    private final int[] vaos = new int[ARENA_SLOTS];
    /* Im VAO gebundener Arena-Buffer/EBO — bei Arena-Wachstum oder EBO-Neubau neu binden */
    private final int[] vaoArenaBuffer = new int[ARENA_SLOTS];
    private final int[] vaoEbo = new int[ARENA_SLOTS];

    /* Geteilter Quad-Index-Buffer (0,1,2, 2,3,0 je Quad) für alle Sections */
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

    /* Polygon-Offset für LOD-Draws: LOD wird minimal "nach hinten" gedrückt, damit (nahezu)
       koplanares echtes Terrain den Tiefentest IMMER gewinnt — sonst fightet das LOD im
       Lade-Fenster (Chunk fertig hochgeladen, Region noch nicht remeshed) mit dem echten
       Terrain. Vorzeichen hängt am Depth-Modus (Muster verwandt mit or-equal-DepthFunc):
       Reversed-Z bedeutet näher = GRÖSSERER Depth-Wert → "weiter weg" = negativer Offset. */
    private float lodOffsetFactor, lodOffsetUnits;

    private int renderedSections = 0;
    private int totalSections = 0;

    /* Gecachte Uniform-Locations des Chunk-Shaders (erspart Map-Lookups im Hot-Path) */
    private int locProjectionView, locAlphaCutoff, locFogStart, locFogEnd, locFogColor;

    /* Zuletzt hochgeladene Fog-Werte: Upload nur bei Änderung (Settings/Clear-Color) —
       die Werte sind pro Frame konstant, ein Re-Upload pro Pass wäre doppelt umsonst. */
    private float lastFogStart = Float.NaN, lastFogEnd = Float.NaN;
    private float lastFogR = -1F, lastFogG = -1F, lastFogB = -1F;

    public ChunkRenderer(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    /** Verdrahtet das LOD-System (aus World.init). Ohne Manager rendert alles wie bisher. */
    public void setLodManager(LodManager lodManager) {
        this.lodManager = lodManager;
    }

    /** Render thread, GL context required. Blocks.bootstrap() muss vorher gelaufen sein! */
    public void init() {
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        if (!properties.isUseMultiDrawIndirect() || !properties.isUseBufferStorage()) {
            throw new IllegalStateException("ChunkRenderer benötigt MultiDrawIndirect (GL 4.3) + BufferStorage (GL 4.4)");
        }

        /* Tiefen-Bias-Richtung je Depth-Modus (s. Feld-Kommentar lodOffsetFactor) */
        float sign = properties.isUseInverseDepth() ? -1F : 1F;
        this.lodOffsetFactor = sign;
        this.lodOffsetUnits = sign * 2F;

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
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.unbind();
        /* Layer-Reihenfolge kommt aus dem Model-Bake (BlockTextures) */
        String[] paths = BlockTextures.getOrderedPaths();
        this.animations = SpriteAnimations.build(paths, TEXTURE_SIZE);
        this.textures = new TextureArray(TEXTURE_SIZE, paths, this.animations.animatedLayers());
        this.animations.uploadInitial(this.textures);
        /* Mipmaps neu bauen, jetzt mit echten Fluid-Frame-0-Daten (animierte Layer waren beim
           ersten glGenerateMipmap noch leer → hätten in der Ferne transparente Mips). */
        this.textures.regenerateMipmaps();
        this.lastAnimNanos = System.nanoTime();

        /* Arenen großzügig nahe am Steady-State starten, damit das Wachstum im Normalbetrieb
           entfällt (jeder Grow = neuer Buffer + Voll-Kopie + NVIDIA-0x20072-Warnung). OPAQUE
           trägt das Terrain (~70-100 MB bei Sichtweite 16); CUTOUT (Gras-Seiten-Overlays)
           erreicht bei voller Sichtweite ~60 MB — sonst wächst es beim Start mehrfach hoch. */
        this.arenas[RenderLayer.OPAQUE.ordinal()] = new VertexArena("VertexArena OPAQUE", 96L * 1024 * 1024);
        this.arenas[RenderLayer.CUTOUT.ordinal()] = new VertexArena("VertexArena CUTOUT", 64L * 1024 * 1024);
        this.arenas[RenderLayer.TRANSLUCENT.ordinal()] = new VertexArena("VertexArena TRANSLUCENT", 8L * 1024 * 1024);
        /* Eigene Arenen für LOD (volle Isolation von Section-Meshes). LOD-OPAQUE (Boden +
           Wände der Clipmap-Ringe) skaliert stark mit lodMaxDistance (bei Default RD=16/
           lodMax=128 real ~190 MB) — Startgröße daher aus der Ring-Konfiguration schätzen,
           statt einer festen Zahl, die entweder VRAM verschwendet oder mehrfach nachwächst.
           Deckel nach unten auf 8 MB (kleine Sichtweiten / LOD aus). Wächst bei Bedarf weiter. */
        GameSettings settings = GameSettings.get();
        long lodOpaqueBytes = 8L * 1024 * 1024;
        if (settings.lodEnabled) {
            lodOpaqueBytes = Math.max(lodOpaqueBytes,
                    LodMesher.estimateOpaqueArenaBytes(LodConfig.of(settings.renderDistance, settings.lodMaxDistance)));
        }
        this.arenas[LOD_OPAQUE] = new VertexArena("VertexArena LOD-OPAQUE", lodOpaqueBytes);
        this.arenas[LOD_TRANSLUCENT] = new VertexArena("VertexArena LOD-TRANSLUCENT", 2L * 1024 * 1024);

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
                + " MB (Sections), " + (this.arenas[LOD_OPAQUE].getCapacity() >> 20) + "/"
                + (this.arenas[LOD_TRANSLUCENT].getCapacity() >> 20) + " MB (LOD), "
                + SLOTS + " Frame-Slots");
    }

    /**
     * Opaque- und Cutout-Pass (inkl. Upload/Cleanup/Frustum-Culling). Der Translucent-Pass
     * folgt separat in {@link #renderTranslucent}, damit Entities dazwischen rendern können
     * (Vanilla-Reihenfolge: Wasser blendet über Entities).
     */
    public void renderSolid(Camera camera) {
        /* 0. Texturanimationen vorrücken (Frame-Tausch, kein Re-Mesh) */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.ANIM);
        long now = System.nanoTime();
        this.animations.tick(this.textures, (now - this.lastAnimNanos) / 1.0e9);
        this.lastAnimNanos = now;
        FrameProfiler.cpuStop(FrameProfiler.Cpu.ANIM);

        /* 1. Frame-Slot übernehmen: auf den 3 Frames alten Fence warten (i.d.R. längst
           signalisiert) und dann die bis dahin aufgelaufenen Arena-Frees einsammeln. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.SYNC);
        this.beginFrame();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.SYNC);

        FrameProfiler.cpuStart(FrameProfiler.Cpu.UPLOAD);

        /* 2a. Prioritäts-Batches (Edit-/Fluid-Remeshes) immer zuerst und vollständig —
           das Volumen ist klein und der Spieler soll seine Änderung sofort sehen. */
        ChunkManager.MeshBatch batch;
        while ((batch = this.chunkManager.getPriorityUploadQueue().poll()) != null) {
            this.applyBatch(batch);
        }

        /* 2b. Normale Upload-Queue (Initial-Load), gedeckelt pro Frame */
        int uploads = 0;
        while (uploads < MAX_UPLOADS_PER_FRAME && (batch = this.chunkManager.getUploadQueue().poll()) != null) {
            this.applyBatch(batch);
            uploads++;
        }

        /* 2c. LOD-Uploads (eigenes Budget) — Meshes landen in der bestehenden OPAQUE-Arena */
        if (this.lodManager != null) this.applyLodResults();

        /* 3. Meshes entladener Chunks freigeben (Regionen deferred) — nur in Frames, in denen
           der ChunkManager wirklich etwas entfernt hat (Removal-Version), statt jeden Frame
           alle Meshes gegen die Chunk-Map zu prüfen. update() (Tick) läuft vor renderSolid,
           der Walk greift also im selben Frame wie die Entfernung. */
        int removalVersion = this.chunkManager.getChunkRemovalVersion();
        if (removalVersion != this.lastChunkRemovalVersion) {
            this.lastChunkRemovalVersion = removalVersion;
            Iterator<Map.Entry<Long, SectionMesh>> it = this.meshes.entrySet().iterator();
            while (it.hasNext()) {
                SectionMesh mesh = it.next().getValue();
                if (!this.chunkManager.getChunks().containsKey(Chunk.key(mesh.chunkX, mesh.chunkZ))) {
                    mesh.dispose(this.arenas, this.frameId);
                    it.remove();
                }
            }
        }

        /* 3b. Nicht mehr gewünschte LOD-Regionen freigeben (deferred) — analog gegated über
           die Desired-Version (bumpt bei jedem recomputeDesired, auch Anker-Bewegung). */
        if (this.lodManager != null && this.lodManager.getDesiredVersion() != this.lastLodDesiredVersion) {
            this.lastLodDesiredVersion = this.lodManager.getDesiredVersion();
            VertexArena lodOpaqueArena = this.arenas[LOD_OPAQUE];
            VertexArena lodTranslucentArena = this.arenas[LOD_TRANSLUCENT];
            Iterator<Map.Entry<Long, LodMesh>> lit = this.lodMeshes.entrySet().iterator();
            while (lit.hasNext()) {
                Map.Entry<Long, LodMesh> entry = lit.next();
                if (!this.lodManager.isDesiredKey(entry.getKey())) {
                    entry.getValue().dispose(lodOpaqueArena, lodTranslucentArena, this.frameId);
                    lit.remove();
                }
            }
        }

        FrameProfiler.cpuStop(FrameProfiler.Cpu.UPLOAD);

        /* 4. Frustum culling, einmal pro Frame */
        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        FrameProfiler.cpuStart(FrameProfiler.Cpu.CULL);

        this.visible.clear();
        this.translucentVisible.clear();
        this.totalSections = this.meshes.size();

        int opaqueDraws = 0, cutoutDraws = 0;
        for (SectionMesh mesh : this.meshes.values()) {
            float ox = offsetX(mesh, cam);
            float oy = offsetY(mesh, cam);
            float oz = offsetZ(mesh, cam);

            if (!camera.getFrustum().testAab(ox, oy, oz, ox + size, oy + size, oz + size)) continue;
            this.visible.add(mesh);
            if (mesh.hasLayer(RenderLayer.OPAQUE)) opaqueDraws++;
            if (mesh.hasLayer(RenderLayer.CUTOUT)) cutoutDraws++;
            if (mesh.hasLayer(RenderLayer.TRANSLUCENT)) this.translucentVisible.add(mesh);
        }
        this.renderedSections = this.visible.size();

        /* 4b. LOD-Regionen: Frustum-Test über das Regionen-AABB (128 x [minY,maxY] x 128) */
        this.visibleLod.clear();
        for (LodMesh mesh : this.lodMeshes.values()) {
            float ox = (float) ((long) mesh.rx * LodMesher.REGION_BLOCKS - cam.x);
            float oz = (float) ((long) mesh.rz * LodMesher.REGION_BLOCKS - cam.z);
            float y0 = (float) (mesh.minY - cam.y);
            float y1 = (float) (mesh.maxY - cam.y);
            if (!camera.getFrustum().testAab(ox, y0, oz,
                    ox + LodMesher.REGION_BLOCKS, y1, oz + LodMesher.REGION_BLOCKS)) continue;
            this.visibleLod.add(mesh);
        }
        int lodDraws = this.visibleLod.size();

        FrameProfiler.cpuStop(FrameProfiler.Cpu.CULL);

        /* 5. Kapazitäten sicherstellen (Uploads können Arenen/EBO gewachsen sein lassen) */
        this.ensureIndexCapacity(this.maxSeenQuads);
        this.ensureVaoBindings();

        /* LOD-Opaque/-Translucent sind jetzt eigene, separat ausgerichtete Segmente (eigene
           Arenen) statt ans Section-Segment angehängt zu werden — daher pro Segment einzeln
           aligned. lodDraws ist eine sichere Obergrenze für das LOD-Translucent-Segment
           (tatsächlich nur der Teil mit hasTranslucent() — exakte Zählung lohnt hier nicht,
           die Ringe wachsen ohnehin nur bei echtem Bedarf). */
        int translucentDraws = this.translucentVisible.size();
        this.commandRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * COMMAND_BYTES)
                        + MappedRing.align((long) lodDraws * COMMAND_BYTES)
                        + MappedRing.align((long) cutoutDraws * COMMAND_BYTES)
                        + MappedRing.align((long) translucentDraws * COMMAND_BYTES)
                        + MappedRing.align((long) lodDraws * COMMAND_BYTES));
        this.offsetRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * OFFSET_BYTES)
                        + MappedRing.align((long) lodDraws * OFFSET_BYTES)
                        + MappedRing.align((long) cutoutDraws * OFFSET_BYTES)
                        + MappedRing.align((long) translucentDraws * OFFSET_BYTES)
                        + MappedRing.align((long) lodDraws * OFFSET_BYTES));

        /* 6. Command-/Offset-Segmente für OPAQUE und CUTOUT schreiben */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.WRITE);

        IntBuffer cmds = this.commandRing.intView(this.frameSlot);
        FloatBuffer offs = this.offsetRing.floatView(this.frameSlot);

        long cmdOpaque = 0, offOpaque = 0;
        int nOpaque = this.writeSegment(RenderLayer.OPAQUE, this.visible, cmds, offs, cmdOpaque, offOpaque, cam);

        /* LOD-Opaque: eigenes Segment, eigener Draw-Call (eigene Arena -> eigener Vertex-Buffer,
           baseVertex wäre in der Section-Arena ungültig). */
        long cmdLodOpaque = cmdOpaque + MappedRing.align((long) nOpaque * COMMAND_BYTES);
        long offLodOpaque = offOpaque + MappedRing.align((long) nOpaque * OFFSET_BYTES);
        int nLodOpaque = this.writeLodOpaqueSegment(cmds, offs, cmdLodOpaque, offLodOpaque, cam);

        long cmdCutout = cmdLodOpaque + MappedRing.align((long) nLodOpaque * COMMAND_BYTES);
        long offCutout = offLodOpaque + MappedRing.align((long) nLodOpaque * OFFSET_BYTES);
        int nCutout = this.writeSegment(RenderLayer.CUTOUT, this.visible, cmds, offs, cmdCutout, offCutout, cam);

        /* Cursor für das Translucent-Segment in renderTranslucent merken */
        this.cmdCursor = cmdCutout + MappedRing.align((long) nCutout * COMMAND_BYTES);
        this.offCursor = offCutout + MappedRing.align((long) nCutout * OFFSET_BYTES);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.WRITE);

        /* 7. Render-Pässe: opaque & cutout (Alpha-Test bei 0.5) — je EIN Draw-Call.
           u_Textures ist einmalig gesetzt (init); Fog lädt nur bei Wertänderung hoch. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GLSUB);
        this.shader.bind();
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.setFogUniforms();
        this.textures.bind(0);

        this.shader.setUniformf(this.locAlphaCutoff, 0.5F);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SOLID);
        this.drawSegment(RenderLayer.OPAQUE.ordinal(), cmdOpaque, offOpaque, nOpaque);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SOLID);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.LOD_OPAQUE);
        this.drawLodSegment(LOD_OPAQUE, cmdLodOpaque, offLodOpaque, nLodOpaque);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.LOD_OPAQUE);

        /* CUTOUT mit "or-equal"-Depth-Func: die koplanaren Gras-Seiten-Overlays (identische
           Vertices wie ihre OPAQUE-Basis-Seite) muessen den Tiefentest exakt gewinnen.
           Reversed-Z: GREATER -> GEQUAL. Die Funcs kommen statisch aus EngineProperties
           statt per glGetInteger (synchroner Treiber-Roundtrip pro Frame). */
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.CUTOUT);
        this.drawSegment(RenderLayer.CUTOUT.ordinal(), cmdCutout, offCutout, nCutout);
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

        /* Nur die Sections mit Translucent-Layer sortieren, nicht die ganze visible-Liste. */
        this.translucentVisible.sort((a, b) -> Double.compare(distanceSq(b, cam), distanceSq(a, cam)));

        /* Per-Quad-Sortierung: nahe Sections zuerst (Liste ist fern -> nah). Sortierte Daten
           wandern in frische Arena-Regionen -> danach ggf. VAO neu binden (Arena-Wachstum). */
        VertexArena translucentArena = this.arenas[RenderLayer.TRANSLUCENT.ordinal()];
        int sortBudget = MAX_TRANSLUCENT_SORTS_PER_FRAME;
        for (int i = this.translucentVisible.size() - 1; i >= 0 && sortBudget > 0; i--) {
            if (this.translucentVisible.get(i).sortTranslucent(cam, translucentArena, this.frameId)) sortBudget--;
        }
        this.ensureVaoBindings();

        FrameProfiler.cpuStop(FrameProfiler.Cpu.SORT);

        FrameProfiler.cpuStart(FrameProfiler.Cpu.WRITE);

        IntBuffer cmds = this.commandRing.intView(this.frameSlot);
        FloatBuffer offs = this.offsetRing.floatView(this.frameSlot);
        int n = this.writeSegment(RenderLayer.TRANSLUCENT, this.translucentVisible, cmds, offs, this.cmdCursor, this.offCursor, cam);

        /* LOD-Translucent (Fluid-Tops): eigenes Segment, direkt nach den echten Sections
           geschrieben — Reihenfolge Sections -> LOD passt zur Vanilla-Konvention. */
        long cmdLodT = this.cmdCursor + MappedRing.align((long) n * COMMAND_BYTES);
        long offLodT = this.offCursor + MappedRing.align((long) n * OFFSET_BYTES);
        int nLodT = this.writeLodTranslucentSegment(cmds, offs, cmdLodT, offLodT, cam);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.WRITE);

        /* Gleiches Programm wie renderSolid im selben Frame: u_ProjectionView/Fog/u_Textures
           bleiben als Programm-Uniform-State erhalten (die Entity-/BlockEntity-Pässe dazwischen
           nutzen eigene Programme) — kein Re-Upload nötig. Nur die Textur-Unit neu binden,
           die Entity-Renderer binden dort ihre eigenen Texturen. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GLSUB);
        this.shader.bind();
        this.textures.bind(0);

        GL11.glEnable(GL11.GL_BLEND);
        this.shader.setUniformf(this.locAlphaCutoff, 0.001F);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.TRANSLUCENT);
        this.drawSegment(RenderLayer.TRANSLUCENT.ordinal(), this.cmdCursor, this.offCursor, n);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.TRANSLUCENT);
        /* Bewusst keine Sortierung für LOD-Translucent (weder Region- noch Quad-Sortierung):
           LOD-Wasserflächen sind großflächige, meist einfache Top-Quads ohne die
           Überlappungskomplexität von Höhlenwasser. */
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.LOD_TRANSLUCENT);
        this.drawLodSegment(LOD_TRANSLUCENT, cmdLodT, offLodT, nLodT);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.LOD_TRANSLUCENT);
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
    private void applyBatch(ChunkManager.MeshBatch batch) {
        for (ChunkManager.MeshResult result : batch.results()) {
            /* Upload-Bestätigung für die LOD-Maske: erst wenn alle Sections angewendet sind,
               darf das LOD dort weichen (Chunk kann bei Unload-Race schon fehlen). */
            Chunk chunk = this.chunkManager.getChunks().get(Chunk.key(result.chunkX(), result.chunkZ()));
            if (chunk != null) chunk.markSectionUploaded();

            long key = sectionKey(result.chunkX(), result.sectionY(), result.chunkZ());

            SectionMesh old = this.meshes.remove(key);
            if (old != null) old.dispose(this.arenas, this.frameId);

            /* Späte Batches entladener Chunks verwerfen (chunk == null): der Cleanup-Walk in
               Schritt 3 läuft nur noch bei Removal-Version-Wechsel — ein danach eingefügtes
               Waisen-Mesh würde nie wieder abgeräumt (Arena-Leak + Geistergeometrie). */
            if (chunk != null && result.data() != null && !result.data().isEmpty()) {
                SectionMesh mesh = new SectionMesh(result.chunkX(), result.sectionY(), result.chunkZ(), result.data(), this.arenas);
                this.meshes.put(key, mesh);
                this.maxSeenQuads = Math.max(this.maxSeenQuads, mesh.maxQuads());
            }
        }
    }

    /**
     * Übernimmt fertige LOD-Meshes (Budget pro Frame) in die dedizierten LOD-Arenen. Nicht
     * mehr gewünschte Ergebnisse werden verworfen (Race Upload vs. Unload → sonst Arena-Leak).
     */
    private void applyLodResults() {
        VertexArena opaqueArena = this.arenas[LOD_OPAQUE];
        VertexArena translucentArena = this.arenas[LOD_TRANSLUCENT];

        /* Arena-Vorabvergrößerung bei Settings-Wechsel: Wird LOD zur Laufzeit eingeschaltet
           (oder die Reichweite erhöht), startet die Arena sonst vom kleinen Init-Floor und
           wächst treppenweise (~9 Grows à 1,5x, jeder mit voller GPU-Kopie — real beobachtet
           8→196 MB). Einmalig auf die Schätzung wachsen statt vieler Schritte. */
        GameSettings settings = GameSettings.get();
        if (settings.lodEnabled != this.lastLodEnabled || settings.renderDistance != this.lastLodRenderDistance
                || settings.lodMaxDistance != this.lastLodMaxDistance) {
            this.lastLodEnabled = settings.lodEnabled;
            this.lastLodRenderDistance = settings.renderDistance;
            this.lastLodMaxDistance = settings.lodMaxDistance;
            if (settings.lodEnabled) {
                opaqueArena.ensureCapacity(LodMesher.estimateOpaqueArenaBytes(
                        LodConfig.of(settings.renderDistance, settings.lodMaxDistance)));
            }
        }
        int uploads = 0;
        LodManager.LodMeshResult result;
        while (uploads < MAX_LOD_UPLOADS_PER_FRAME && (result = this.lodManager.pollResult()) != null) {
            if (!this.lodManager.acceptResult(result)) continue;

            long key = LodManager.key(result.rx(), result.rz());
            LodMesh old = this.lodMeshes.remove(key);
            if (old != null) old.dispose(opaqueArena, translucentArena, this.frameId);

            if (result.opaqueData().length > 0 || result.translucentData().length > 0) {
                LodMesh mesh = new LodMesh(result.rx(), result.rz(), result.level(), result.yBase(),
                        result.opaqueData(), result.translucentData(), result.minY(), result.maxY(),
                        opaqueArena, translucentArena);
                this.lodMeshes.put(key, mesh);
                this.maxSeenQuads = Math.max(this.maxSeenQuads, mesh.maxQuads());
                uploads++;
            }
        }

        /* Statistik gelegentlich loggen (Budget-Annahmen verifizierbar halten) */
        if ((this.frameId & 2047) == 0 && !this.lodMeshes.isEmpty()) {
            long quads = 0;
            for (LodMesh mesh : this.lodMeshes.values()) quads += mesh.quadCount();
            this.logger.debug("LOD: " + this.lodMeshes.size() + " Regionen, " + quads
                    + " Quads, " + ((quads * 4 * ChunkMesher.VERTEX_SIZE * Integer.BYTES) >> 20) + " MB Arena");
        }
    }

    /**
     * Schreibt die sichtbaren LOD-Opaque-Regionen als eigenes Indirect-Command-Segment (eigene
     * Arena, eigener Draw-Call — baseVertex ist nur innerhalb desselben Vertex-Buffers gültig).
     * Offset-Semantik unverändert: .xyz = Ursprung kamerarelativ, .w = 0.
     *
     * @return Anzahl geschriebener LOD-Opaque-Draws
     */
    private int writeLodOpaqueSegment(IntBuffer cmds, FloatBuffer offs, long cmdSegBytes, long offSegBytes,
                                      Vector3d cam) {
        int cmdBase = (int) (cmdSegBytes / Integer.BYTES);
        int offBase = (int) (offSegBytes / Float.BYTES);
        int n = 0;
        for (int i = 0; i < this.visibleLod.size(); i++) {
            LodMesh mesh = this.visibleLod.get(i);
            if (!mesh.hasOpaque()) continue;

            int ci = cmdBase + n * 5;
            cmds.put(ci, mesh.indexCountOpaque());        // count
            cmds.put(ci + 1, 1);                          // instanceCount
            cmds.put(ci + 2, 0);                          // firstIndex (geteilter EBO ab 0)
            cmds.put(ci + 3, mesh.baseVertexOpaque());    // baseVertex = Arena-Region
            cmds.put(ci + 4, 0);                          // baseInstance (ungenutzt)

            int oi = offBase + n * 4;
            offs.put(oi, (float) ((long) mesh.rx * LodMesher.REGION_BLOCKS - cam.x));
            offs.put(oi + 1, (float) (mesh.yBase - cam.y)); // Vertices sind relativ zu yBase gepackt
            offs.put(oi + 2, (float) ((long) mesh.rz * LodMesher.REGION_BLOCKS - cam.z));
            offs.put(oi + 3, 0F);
            n++;
        }
        return n;
    }

    /**
     * Schreibt die sichtbaren LOD-Translucent-Regionen (Fluid-Top-Quads) als eigenes
     * Indirect-Command-Segment, analog zu {@link #writeLodOpaqueSegment}, aber gegen die
     * LOD-Translucent-Arena/-Region. Keine Sortierung (siehe Aufrufer).
     *
     * @return Anzahl geschriebener LOD-Translucent-Draws
     */
    private int writeLodTranslucentSegment(IntBuffer cmds, FloatBuffer offs, long cmdSegBytes, long offSegBytes,
                                           Vector3d cam) {
        int cmdBase = (int) (cmdSegBytes / Integer.BYTES);
        int offBase = (int) (offSegBytes / Float.BYTES);
        int n = 0;
        for (int i = 0; i < this.visibleLod.size(); i++) {
            LodMesh mesh = this.visibleLod.get(i);
            if (!mesh.hasTranslucent()) continue;

            int ci = cmdBase + n * 5;
            cmds.put(ci, mesh.indexCountTranslucent());        // count
            cmds.put(ci + 1, 1);                               // instanceCount
            cmds.put(ci + 2, 0);                               // firstIndex (geteilter EBO ab 0)
            cmds.put(ci + 3, mesh.baseVertexTranslucent());    // baseVertex = Arena-Region
            cmds.put(ci + 4, 0);                               // baseInstance (ungenutzt)

            int oi = offBase + n * 4;
            offs.put(oi, (float) ((long) mesh.rx * LodMesher.REGION_BLOCKS - cam.x));
            offs.put(oi + 1, (float) (mesh.yBase - cam.y)); // Vertices sind relativ zu yBase gepackt
            offs.put(oi + 2, (float) ((long) mesh.rz * LodMesher.REGION_BLOCKS - cam.z));
            offs.put(oi + 3, 0F);
            n++;
        }
        return n;
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
     * LOD-Variante von {@link #drawSegment}: zeichnet mit Polygon-Offset "nach hinten"
     * (s. {@link #lodOffsetFactor}), damit echtes Terrain koplanare LOD-Geometrie immer
     * überdeckt — Zustand wird danach zurückgesetzt.
     */
    private void drawLodSegment(int slot, long cmdSegBytes, long offSegBytes, int drawCount) {
        if (drawCount == 0) return;
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(this.lodOffsetFactor, this.lodOffsetUnits);
        this.drawSegment(slot, cmdSegBytes, offSegBytes, drawCount);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    /**
     * Ein glMultiDrawElementsIndirect-Call für ein Arena-/VAO-Segment des aktuellen Frame-Slots.
     * {@code slot} indiziert {@link #vaos}/{@link #arenas} (RenderLayer-Ordinal oder LOD_*).
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
        int[] indices = new int[newCapacity * 6];
        for (int q = 0, i = 0; q < newCapacity; q++) {
            int v = q * 4;
            indices[i++] = v;
            indices[i++] = v + 1;
            indices[i++] = v + 2;
            indices[i++] = v + 2;
            indices[i++] = v + 3;
            indices[i++] = v;
        }
        /* Neuen EBO VOR dem Löschen des alten erzeugen (wie VertexArena.grow): solange der
           alte Name lebt, ist der neue garantiert verschieden — sonst recycelt der Treiber
           den Namen, ensureVaoBindings hält die Bindung für aktuell und die VAOs zeigen
           weiter auf das alte, zu kleine EBO-Objekt (Garbage-Indizes hinter dessen Ende). */
        int oldEbo = this.sharedEbo;
        this.sharedEbo = GL15.glGenBuffers();
        /* Kein VAO gebunden -> Bindung landet nicht versehentlich in einem VAO */
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.sharedEbo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        if (oldEbo != 0) {
            GL15.glDeleteBuffers(oldEbo);
            this.logger.debug("Quad-EBO gewachsen: " + this.indexCapacityQuads + " -> " + newCapacity + " Quads");
        }
        GlDebug.labelBuffer(this.sharedEbo, "Geteilter Quad-EBO (" + newCapacity + " Quads)");
        this.indexCapacityQuads = newCapacity;
    }

    /** Bindet Arena-Buffer + EBO in die Layer-VAOs neu, falls sich einer geändert hat (Wachstum). */
    private void ensureVaoBindings() {
        for (int i = 0; i < this.vaos.length; i++) {
            int arenaBuffer = this.arenas[i].getBuffer();
            if (this.vaoArenaBuffer[i] == arenaBuffer && this.vaoEbo[i] == this.sharedEbo) continue;

            GL30.glBindVertexArray(this.vaos[i]);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arenaBuffer);
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_INT, ChunkMesher.VERTEX_SIZE * Integer.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.sharedEbo);
            GL30.glBindVertexArray(0);

            this.vaoArenaBuffer[i] = arenaBuffer;
            this.vaoEbo[i] = this.sharedEbo;
        }
    }

    /** Setzt die Fog-Uniforms für den gebundenen Chunk-Shader. Fog blendet fernes Terrain
        Richtung Clear-Color (= Himmel) und nimmt dem Horizont damit den Kontrast, der das
        Sub-Pixel-Flimmern verursacht. Bezugsgröße ist die sichtbare Terrain-Reichweite
        (nicht die Far-Plane): mit LOD der äußerste LOD-Ring, ohne LOD die Chunk-Ladekante. */
    private void setFogUniforms() {
        GameSettings settings = GameSettings.get();
        float fogStart, fogEnd;
        if (settings.fog) {
            float range = (settings.lodEnabled
                    ? Math.max(settings.lodMaxDistance, settings.renderDistance)
                    : settings.renderDistance) * ChunkSection.SIZE;
            /* Fog-Ende ~3 Chunks VOR die theoretische Grenze: der Lade-Kreis ist auf ganze
               Chunks quantisiert und der Spieler steht bis zu 31 Blöcke neben seinem
               Chunk-Ursprung — die sichtbare Kante liegt daher bis zu ~2-3 Chunks innerhalb
               von rd*32. Ohne diesen Rand bleibt die Stufen-Silhouette der Ladegrenze bei
               kurzer Fog-Spanne (ohne LOD) sichtbar. */
            fogEnd = Math.max(range - 3 * ChunkSection.SIZE, 2 * ChunkSection.SIZE);
            /* Ohne LOD ist der einzige Fog-Zweck das Verstecken der Ladekante -> kurze steile
               Rampe (80 %), damit von der knappen Sichtweite mehr klar bleibt; mit LOD lange
               Rampe (60 %) gegen das Sub-Pixel-Flimmern des Fernterrains. */
            fogStart = fogEnd * (settings.lodEnabled ? 0.60F : 0.80F);
        } else {
            /* Fog aus: Start/Ende jenseits jeder Distanz -> Faktor 0, keine Shader-Variante nötig */
            fogStart = 1.0e30F;
            fogEnd = 2.0e30F;
        }
        Color4 clear = SkyEngine.get().getConfig().getWindowClearColor();

        /* Upload nur bei Änderung — die Werte hängen nur an Settings/Clear-Color, nicht an
           der Kamera. Uniform-State bleibt im Programm erhalten. */
        if (fogStart == this.lastFogStart && fogEnd == this.lastFogEnd
                && clear.red == this.lastFogR && clear.green == this.lastFogG && clear.blue == this.lastFogB) {
            return;
        }
        this.shader.setUniformf(this.locFogStart, fogStart);
        this.shader.setUniformf(this.locFogEnd, fogEnd);
        this.shader.setUniformVector3f(this.locFogColor, clear.red, clear.green, clear.blue);
        this.lastFogStart = fogStart;
        this.lastFogEnd = fogEnd;
        this.lastFogR = clear.red;
        this.lastFogG = clear.green;
        this.lastFogB = clear.blue;
    }

    /* ------------------------- Helfer ------------------------- */

    /** Debug-Label für einen Arena-/VAO-Slot (RenderLayer-Name oder LOD-Pseudo-Layer). */
    private static String slotLabel(int slot) {
        if (slot < RenderLayer.VALUES.length) return RenderLayer.VALUES[slot].toString();
        return slot == LOD_OPAQUE ? "LOD-OPAQUE" : "LOD-TRANSLUCENT";
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

    private static double distanceSq(SectionMesh mesh, Vector3d cam) {
        double cx = ((long) mesh.chunkX << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.x;
        double cy = ((long) mesh.sectionY << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.y;
        double cz = ((long) mesh.chunkZ << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.z;
        return cx * cx + cy * cy + cz * cz;
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void dispose() {
        for (SectionMesh mesh : this.meshes.values()) mesh.dispose(this.arenas, this.frameId);
        this.meshes.clear();
        for (LodMesh mesh : this.lodMeshes.values()) {
            mesh.dispose(this.arenas[LOD_OPAQUE], this.arenas[LOD_TRANSLUCENT], this.frameId);
        }
        this.lodMeshes.clear();
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
        if (this.animations != null) this.animations.dispose();
        if (this.shader != null) this.shader.dispose();
        if (this.textures != null) this.textures.dispose();
    }

    /* Gepacktes Vertex-Format (20 Bytes, siehe ChunkMesher.VERTEX_SIZE):
       x: posX | posY<<16 (u16 fixed 8.8, Bias +1) — y: posZ | u<<16 (uv fixed 6.10, Bias +1)
       z: v | layer<<16 — w: rgb8
       (5. Int reserviert für farbiges Licht, vom Shader aktuell ungenutzt — Stride wächst
       automatisch über ChunkMesher.VERTEX_SIZE, a_data liest weiterhin nur die ersten 4 Ints)
       Section-Origin (kamerarelativ) kommt pro Draw aus dem SSBO, indiziert via gl_DrawID. */
    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in uvec4 a_data;

            layout(std430, binding = 0) readonly buffer DrawOffsets {
                vec4 u_DrawOffsets[];
            };

            uniform mat4 u_ProjectionView;

            out vec3 v_texCoord;
            out vec3 v_color;
            out float v_viewDist;

            void main() {
                vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16), float(a_data.y & 0xFFFFu)) * (1.0 / 256.0) - 1.0;
                vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu)) * (1.0 / 1024.0) - 1.0;
                float layer = float(a_data.z >> 16);
                vec3 color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu), float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);

                v_texCoord = vec3(uv, layer);
                v_color = color;
                /* Positionen sind kamerarelativ und welt-achsen-ausgerichtet -> length(rel.xz) =
                   horizontale Sichtdistanz fuer ZYLINDRISCHEN Fog (wie MC 1.18+): Hochfliegen
                   schiebt das Terrain unter dem Spieler nicht in den Nebel, die horizontale
                   Ladekante bleibt verdeckt. Rotationsinvariant, kein "Atmen" beim Umschauen. */
                vec3 rel = pos + u_DrawOffsets[gl_DrawID].xyz;
                v_viewDist = length(rel.xz);
                gl_Position = u_ProjectionView * vec4(rel, 1.0);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            in vec3 v_texCoord;
            in vec3 v_color;
            in float v_viewDist;

            uniform sampler2DArray u_Textures;
            uniform float u_AlphaCutoff;
            uniform vec3 u_FogColor;
            uniform float u_FogStart;
            uniform float u_FogEnd;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(u_Textures, v_texCoord);
                if (color.a < u_AlphaCutoff) discard;
                /* Clamp gegen Attribut-EXTRApolation: kantenparallel gesehene Faces rastern als
                   degenerierte Sliver-Dreiecke, deren Interpolation die per-Vertex-AO-Farben
                   ueber 1.0 hinaus extrapoliert -> helle Funkel-Striche auf Augenhoehe. */
                vec3 lit = color.rgb * clamp(v_color, 0.0, 1.0);
                /* Linearer Distanz-Fog Richtung Clear-Color: nimmt dem Horizont den Kontrast
                   (Sub-Pixel-Flimmern des Fernterrains) und versteckt die Far-Plane-Kante. */
                float fog = clamp((v_viewDist - u_FogStart) / (u_FogEnd - u_FogStart), 0.0, 1.0);
                fragColor = vec4(mix(lit, u_FogColor, fog), color.a);
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
