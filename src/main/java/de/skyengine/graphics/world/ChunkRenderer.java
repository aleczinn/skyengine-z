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
import org.joml.FrustumIntersection;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;

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

    /* Cull-Hierarchie: Chunk-Spalten-Index über den Section-Meshes. Erst das Spalten-AABB
       (XZ der Spalte, [minSy,maxSy] der vorhandenen Sections) gegen das Frustum testen, nur
       bei Schnitt die einzelnen Sections — spart den Großteil der testAab-Aufrufe (gemessen
       war der flache Loop ~135 µs/Frame) und zieht das LOD-Sicht-Gate von pro-Section auf
       pro-Spalte vor. Parallel-Index zu this.meshes: an ALLEN Mutationsstellen über
       columnAdd/columnRemove mitpflegen (put/remove/Cleanup-Walk/dispose). */
    private static final class CullColumn {
        final int chunkX, chunkZ;
        final SectionMesh[] sections = new SectionMesh[Chunk.SECTIONS];
        int count, minSy, maxSy;
        int gateSlot = -1; // Sicht-Gate-Slot im GPU-Cull-Substrat (pro Spalte)

        CullColumn(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private final Map<Long, CullColumn> cullColumns = new HashMap<>();

    /* Pro Frame neu befüllt: alle Sections, die den Frustum-Test bestanden haben */
    private final List<SectionMesh> visible = new ArrayList<>();

    /* Teilmenge von visible mit TRANSLUCENT-Layer - nur diese werden back-to-front sortiert */
    private final List<SectionMesh> translucentVisible = new ArrayList<>();

    /* Alle Meshes mit TRANSLUCENT-Layer (Mitgliedschafts-Hooks in applyBatch/Cleanup):
       im GPU-Cull-Pfad entfällt der große Section-Loop, Translucent bleibt aber CPU
       (Sortierung braucht CPU-Reihenfolge) — dafür reicht diese kleine Liste. */
    private final List<SectionMesh> translucentMeshes = new ArrayList<>();

    /* GPU-Cull-Substrat (P3): Compute-Frustum + Command-Kompaktierung + IndirectCount für
       OPAQUE/CUTOUT/LOD-OPAQUE. CPU-Pfad bleibt vollständig erhalten (Fallback + A/B). */
    private final GpuCull gpuCull = new GpuCull();

    /* --- Heightmap-LOD: zusätzliche Draws im OPAQUE-Segment (gleiche Arena, gleicher Shader) --- */

    /* regionKey -> LOD-Mesh, render thread only. null-Manager = LOD aus (rendert wie bisher). */
    private final Map<Long, LodMesh> lodMeshes = new HashMap<>();
    private final List<LodMesh> visibleLod = new ArrayList<>();
    private LodManager lodManager;

    /* Cull-Hierarchie LOD: 4×4-Regionen-Kacheln (512 Blöcke) mit aggregiertem [minY,maxY] —
       analog zum Spalten-Index (der flache Regionen-Loop war die größere Hälfte der Cull-Zeit).
       Superregionen (sizeBlocks > REGION_BLOCKS, aktuell ruhender Pfad) passen nicht sicher in
       eine Kachel und werden separat flach getestet. Pflege über lodTileAdd/lodTileRemove. */
    private static final class LodTile {
        final int tx, tz;
        final List<LodMesh> members = new ArrayList<>();
        float minY, maxY;

        LodTile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }
    }

    private static final int LOD_TILE_SHIFT = 2; // 4×4 Regionen = 512 Blöcke Kachelkante
    private final Map<Long, LodTile> lodTiles = new HashMap<>();
    private final List<LodMesh> lodOversize = new ArrayList<>();

    /* Sichtbare LOD-Regionen mit Translucent-Anteil (pro Frame): im CPU-Pfad aus dem
       Kachel-Cull befüllt, im GPU-Pfad aus lodTranslucentMeshes (Kleinstmenge, flach). */
    private final List<LodMesh> visibleLodTranslucent = new ArrayList<>();
    private final List<LodMesh> lodTranslucentMeshes = new ArrayList<>();

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

    /* Mess-Gate LOD-Superregionen: bei aktivem FrameProfiler wird das LOD-Opaque-Segment
       pro Level in eigene MDI-Sub-Segmente mit eigener GPU-Query gesplittet (GL_TIME_ELAPSED
       darf nicht verschachteln -> ein Draw pro Level). Ohne Profiler: ein Draw wie bisher.
       Index 1..5 = LOD-Level (LodConfig deckelt maxLevel auf 5). */
    private static final int MAX_LOD_LEVELS = 5;
    @SuppressWarnings("unchecked")
    private final List<LodMesh>[] visibleLodByLevel = new List[MAX_LOD_LEVELS + 1];
    private static final FrameProfiler.Gpu[] LOD_LEVEL_GPU = {
            null, FrameProfiler.Gpu.LOD_O_L1, FrameProfiler.Gpu.LOD_O_L2,
            FrameProfiler.Gpu.LOD_O_L3, FrameProfiler.Gpu.LOD_O_L4, FrameProfiler.Gpu.LOD_O_L5
    };
    /* Sub-Segment-Cursor/Draw-Zahlen pro Level (nur im Split-Modus befüllt, keine
       per-Frame-Allokationen im Hot-Path) */
    private final long[] lodLvlCmd = new long[MAX_LOD_LEVELS + 1];
    private final long[] lodLvlOff = new long[MAX_LOD_LEVELS + 1];
    private final int[] lodLvlN = new int[MAX_LOD_LEVELS + 1];

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
       Reversed-Z bedeutet näher = GRÖSSERER Depth-Wert → "weiter weg" = negativer Offset.

       NUR Units, KEIN Steigungsterm (factor bleibt 0) — das ist tragend, nicht nachlässig:
       glPolygonOffset rechnet o = factor * m + units * r, wobei m die Tiefensteigung des
       Polygons im Fensterraum ist. Tops und Wände/Skirts des LOD stecken im SELBEN Draw, haben
       aber gegensätzliche Steigungen: Bei flachem Blick in die Ferne ist m der fast von der
       Seite gesehenen Top-Quads riesig, das der senkrechten (also fast frontal gesehenen)
       Skirt-Wände dagegen ~0. Ein Steigungsterm drückt damit den LOD-Boden weit hinter seine
       EIGENEN, eigentlich vergrabenen Regionsrand-Skirts — die bluten dann als schwarzes
       128er-Gitter (= REGION_BLOCKS) durch die Landschaft. Für den eigentlichen Zweck reicht
       ein konstanter Bias: L0- und LOD-Terrain sind dort koplanar (gleiche Ebene, gleiche
       Steigung), da trägt der Steigungsterm ohnehin nichts bei. */
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
        for (int l = 1; l <= MAX_LOD_LEVELS; l++) this.visibleLodByLevel[l] = new ArrayList<>();
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
        this.lodOffsetFactor = 0F;          // KEIN Steigungsterm — s. Feld-Kommentar!
        this.lodOffsetUnits = sign * 8F;    // konstanter Bias (ersetzt den Steigungsanteil)

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

        /* Arenen so starten, dass das Wachstum auch beim SCHNELLEN FLIEGEN entfällt — jeder
           Grow ist eine GPU-Vollkopie der ganzen Arena im Frame (= gemessener Ruckler, der
           sogar die Flugsteuerung kurz anhält, plus NVIDIA-0x20072-Warnung).

           Bezugsgröße ist bewusst der (rd+6)-Kreis, NICHT rd: pendingUnload-Chunks behalten
           ihre Meshes bis zum Notventil rd+6, solange das LOD ihre Zelle noch nicht deckt —
           beim Flug hält die Arena also weit mehr als den Steady-State (real beobachtet:
           OPAQUE wuchs bei rd=16 von 96 auf 324 MB, inkl. First-Fit-Fragmentierung).
           Die Bytes/Chunk sind aus Sprint-Flug-Messungen abgeleitet (OPAQUE: Endstand 324 MB /
           ~1520 Chunks ≈ 220 KB; CUTOUT trägt das LAUB — über Wald wuchs es real auf >285 MB,
           also NICHT kleiner ansetzen als OPAQUE); Floors = bisherige Startgrößen. */
        GameSettings settings = GameSettings.get();
        int holdRadius = settings.renderDistance + 6;
        long holdChunks = Math.round(Math.PI * holdRadius * holdRadius);
        this.arenas[RenderLayer.OPAQUE.ordinal()] = new VertexArena("VertexArena OPAQUE",
                Math.max(96L << 20, holdChunks * 256L * 1024));
        this.arenas[RenderLayer.CUTOUT.ordinal()] = new VertexArena("VertexArena CUTOUT",
                Math.max(64L << 20, holdChunks * 256L * 1024));
        this.arenas[RenderLayer.TRANSLUCENT.ordinal()] = new VertexArena("VertexArena TRANSLUCENT",
                Math.max(8L << 20, holdChunks * 16L * 1024));
        /* Eigene Arenen für LOD (volle Isolation von Section-Meshes). LOD-OPAQUE (Boden +
           Wände der Clipmap-Ringe) skaliert stark mit lodMaxDistance (bei Default RD=16/
           lodMax=128 real ~190 MB) — Startgröße daher aus der Ring-Konfiguration schätzen,
           statt einer festen Zahl, die entweder VRAM verschwendet oder mehrfach nachwächst.
           Deckel nach unten auf 8 MB (kleine Sichtweiten / LOD aus). Wächst bei Bedarf weiter. */
        long lodOpaqueBytes = 8L * 1024 * 1024;
        if (settings.lodEnabled) {
            lodOpaqueBytes = Math.max(lodOpaqueBytes,
                    LodMesher.estimateOpaqueArenaBytes(LodConfig.of(settings.renderDistance, settings.lodMaxDistance)));
        }
        this.arenas[LOD_OPAQUE] = new VertexArena("VertexArena LOD-OPAQUE", lodOpaqueBytes);
        /* 8 MB statt 2: bei Ozean im Ring wuchs die Arena sonst direkt beim Start (2->4 MB). */
        this.arenas[LOD_TRANSLUCENT] = new VertexArena("VertexArena LOD-TRANSLUCENT", 8L * 1024 * 1024);

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

        /* GPU-Cull-Substrat (P3): ohne IndirectCount-Capability bleibt alles im CPU-Pfad. */
        this.gpuCull.init(properties.isSourceIndirectDrawCallCountFromBuffer());

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
                    this.unregisterSectionMesh(mesh);
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
                    LodMesh mesh = entry.getValue();
                    mesh.dispose(lodOpaqueArena, lodTranslucentArena, this.frameId);
                    lit.remove();
                    this.unregisterLodMesh(mesh);
                    this.refreshGateForRegion(mesh.rx, mesh.rz, mesh.sizeBlocks / LodMesher.REGION_BLOCKS);
                }
            }
        }

        FrameProfiler.cpuStop(FrameProfiler.Cpu.UPLOAD);

        /* 4. Frustum culling, einmal pro Frame */
        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        FrameProfiler.cpuStart(FrameProfiler.Cpu.CULL);

        boolean gpu = this.gpuCull.isActive();
        this.visible.clear();
        this.translucentVisible.clear();
        this.visibleLodTranslucent.clear();
        this.totalSections = this.meshes.size();

        int opaqueDraws = 0, cutoutDraws = 0;
        boolean lodSplit = FrameProfiler.isEnabled();
        this.visibleLod.clear();
        if (lodSplit) {
            for (int l = 1; l <= MAX_LOD_LEVELS; l++) this.visibleLodByLevel[l].clear();
        }
        FrustumIntersection frustum = camera.getFrustum();
        if (gpu) {
            /* GPU-Pfad: OPAQUE/CUTOUT/LOD-OPAQUE cullt der Compute-Pass (Dispatch unten) —
               die CPU testet nur die kleinen Translucent-Mengen, deren Sortierung
               CPU-Reihenfolge braucht. Das Sicht-Gate gilt hier wie im Spalten-Loop. */
            for (int i = 0; i < this.translucentMeshes.size(); i++) {
                SectionMesh mesh = this.translucentMeshes.get(i);
                if (this.lodManager != null && this.lodManager.lodShowsCell(mesh.chunkX, mesh.chunkZ)) continue;
                float ox = offsetX(mesh, cam);
                float oy = offsetY(mesh, cam);
                float oz = offsetZ(mesh, cam);
                if (!frustum.testAab(ox, oy, oz, ox + size, oy + size, oz + size)) continue;
                this.translucentVisible.add(mesh);
            }
            for (int i = 0; i < this.lodTranslucentMeshes.size(); i++) {
                LodMesh mesh = this.lodTranslucentMeshes.get(i);
                float ox = (float) ((long) mesh.rx * LodMesher.REGION_BLOCKS - cam.x);
                float oz = (float) ((long) mesh.rz * LodMesher.REGION_BLOCKS - cam.z);
                if (!frustum.testAab(ox, (float) (mesh.minY - cam.y), oz,
                        ox + mesh.sizeBlocks, (float) (mesh.maxY - cam.y), oz + mesh.sizeBlocks)) continue;
                this.visibleLodTranslucent.add(mesh);
            }
            this.gpuCull.dispatch(this.frameSlot, camera);
        } else {
        for (CullColumn col : this.cullColumns.values()) {
            /* Sicht-Gate: solange ein hochgeladenes LOD-Mesh die Zelle noch ungeclippt zeigt,
               Chunk-Sections NICHT zeichnen — applyLodResults lief oben im selben Frame, das
               geclippte LOD und der Chunk erscheinen/verschwinden also im SELBEN Frame
               (atomarer Swap statt Doppelbild an der Ladefront). Versteckt auch
               teil-hochgeladene Chunks (kein progressiver Teil-Pop). Pro Spalte statt
               pro Section — die Zelle ist ohnehin spaltenweit. */
            if (this.lodManager != null && this.lodManager.lodShowsCell(col.chunkX, col.chunkZ)) continue;

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
            }
        }
        this.renderedSections = this.visible.size();

        /* 4b. LOD-Regionen: Kachel-AABB zuerst, dann Regionen (nur CPU-Pfad). */
        int tileBlocks = LodMesher.REGION_BLOCKS << LOD_TILE_SHIFT;
        for (LodTile tile : this.lodTiles.values()) {
            /* Kachel-AABB zuerst (4×4 Regionen, aggregiertes [minY,maxY]). */
            float tx = (float) (((long) tile.tx << LOD_TILE_SHIFT) * LodMesher.REGION_BLOCKS - cam.x);
            float tz = (float) (((long) tile.tz << LOD_TILE_SHIFT) * LodMesher.REGION_BLOCKS - cam.z);
            if (!frustum.testAab(tx, (float) (tile.minY - cam.y), tz,
                    tx + tileBlocks, (float) (tile.maxY - cam.y), tz + tileBlocks)) continue;
            for (int i = 0; i < tile.members.size(); i++) {
                this.cullLodMesh(tile.members.get(i), frustum, cam, lodSplit);
            }
        }
        /* Superregionen (ruhender Pfad) passen nicht sicher in eine Kachel → flach testen. */
        for (int i = 0; i < this.lodOversize.size(); i++) {
            this.cullLodMesh(this.lodOversize.get(i), frustum, cam, lodSplit);
        }
        } // Ende CPU-Cull-Pfad
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
        int lodTDraws = this.visibleLodTranslucent.size();
        /* Im Split-Modus ist jedes per-Level-Sub-Segment einzeln aligned (SSBO-Offset-
           Anforderung) -> Kapazität als Summe der aligned Level-Segmente rechnen. */
        long lodCmdCap, lodOffCap;
        if (lodSplit) {
            lodCmdCap = 0;
            lodOffCap = 0;
            for (int l = 1; l <= MAX_LOD_LEVELS; l++) {
                int n = this.visibleLodByLevel[l].size();
                lodCmdCap += MappedRing.align((long) n * COMMAND_BYTES);
                lodOffCap += MappedRing.align((long) n * OFFSET_BYTES);
            }
        } else {
            lodCmdCap = MappedRing.align((long) lodDraws * COMMAND_BYTES);
            lodOffCap = MappedRing.align((long) lodDraws * OFFSET_BYTES);
        }
        this.commandRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * COMMAND_BYTES)
                        + lodCmdCap
                        + MappedRing.align((long) cutoutDraws * COMMAND_BYTES)
                        + MappedRing.align((long) translucentDraws * COMMAND_BYTES)
                        + MappedRing.align((long) lodTDraws * COMMAND_BYTES));
        this.offsetRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * OFFSET_BYTES)
                        + lodOffCap
                        + MappedRing.align((long) cutoutDraws * OFFSET_BYTES)
                        + MappedRing.align((long) translucentDraws * OFFSET_BYTES)
                        + MappedRing.align((long) lodTDraws * OFFSET_BYTES));

        /* 6. Command-/Offset-Segmente für OPAQUE und CUTOUT schreiben */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.WRITE);

        IntBuffer cmds = this.commandRing.intView(this.frameSlot);
        FloatBuffer offs = this.offsetRing.floatView(this.frameSlot);

        long cmdOpaque = 0, offOpaque = 0;
        int nOpaque = this.writeSegment(RenderLayer.OPAQUE, this.visible, cmds, offs, cmdOpaque, offOpaque, cam);

        /* LOD-Opaque: eigenes Segment, eigener Draw-Call (eigene Arena -> eigener Vertex-Buffer,
           baseVertex wäre in der Section-Arena ungültig). Im Split-Modus (FrameProfiler) pro
           Level ein eigenes, einzeln aligntes Sub-Segment für per-Level-GPU-Queries. */
        long cmdLodOpaque = cmdOpaque + MappedRing.align((long) nOpaque * COMMAND_BYTES);
        long offLodOpaque = offOpaque + MappedRing.align((long) nOpaque * OFFSET_BYTES);
        long cmdCutout, offCutout;
        int nLodOpaque = 0;
        if (lodSplit) {
            long c = cmdLodOpaque, o = offLodOpaque;
            for (int l = 1; l <= MAX_LOD_LEVELS; l++) {
                List<LodMesh> list = this.visibleLodByLevel[l];
                this.lodLvlCmd[l] = c;
                this.lodLvlOff[l] = o;
                this.lodLvlN[l] = list.isEmpty() ? 0 : this.writeLodOpaqueSegment(list, cmds, offs, c, o, cam);
                c += MappedRing.align((long) this.lodLvlN[l] * COMMAND_BYTES);
                o += MappedRing.align((long) this.lodLvlN[l] * OFFSET_BYTES);
            }
            cmdCutout = c;
            offCutout = o;
        } else {
            nLodOpaque = this.writeLodOpaqueSegment(this.visibleLod, cmds, offs, cmdLodOpaque, offLodOpaque, cam);
            cmdCutout = cmdLodOpaque + MappedRing.align((long) nLodOpaque * COMMAND_BYTES);
            offCutout = offLodOpaque + MappedRing.align((long) nLodOpaque * OFFSET_BYTES);
        }
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

        /* GPU-Pfad: Compute-Ergebnisse (Commands/Offsets/Counts) vor den Draws sichtbar machen. */
        if (gpu) this.gpuCull.barrier();

        this.shader.setUniformf(this.locAlphaCutoff, 0.5F);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SOLID);
        if (gpu) {
            this.drawSegmentGpu(RenderLayer.OPAQUE.ordinal(), GpuCull.SEG_OPAQUE);
        } else {
            this.drawSegment(RenderLayer.OPAQUE.ordinal(), cmdOpaque, offOpaque, nOpaque);
        }
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SOLID);
        if (gpu) {
            /* Immer per Level (eigene Count-Segmente): per-Level-GPU-Queries bleiben möglich,
               leere Level werden über den CPU-bekannten Descriptor-Zähler übersprungen. */
            for (int l = 1; l <= MAX_LOD_LEVELS; l++) {
                if (this.gpuCull.lodLevelCount(l) == 0) continue;
                FrameProfiler.gpuBegin(LOD_LEVEL_GPU[l]);
                this.drawLodSegmentGpu(LOD_OPAQUE, GpuCull.SEG_LOD_BASE + l - 1);
                FrameProfiler.gpuEnd(LOD_LEVEL_GPU[l]);
            }
        } else if (lodSplit) {
            for (int l = 1; l <= MAX_LOD_LEVELS; l++) {
                if (this.lodLvlN[l] == 0) continue;
                FrameProfiler.gpuBegin(LOD_LEVEL_GPU[l]);
                this.drawLodSegment(LOD_OPAQUE, this.lodLvlCmd[l], this.lodLvlOff[l], this.lodLvlN[l]);
                FrameProfiler.gpuEnd(LOD_LEVEL_GPU[l]);
            }
        } else {
            this.drawLodSegment(LOD_OPAQUE, cmdLodOpaque, offLodOpaque, nLodOpaque);
        }

        /* CUTOUT mit "or-equal"-Depth-Func: die koplanaren Gras-Seiten-Overlays (identische
           Vertices wie ihre OPAQUE-Basis-Seite) muessen den Tiefentest exakt gewinnen.
           Reversed-Z: GREATER -> GEQUAL. Die Funcs kommen statisch aus EngineProperties
           statt per glGetInteger (synchroner Treiber-Roundtrip pro Frame). */
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.CUTOUT);
        if (gpu) {
            this.drawSegmentGpu(RenderLayer.CUTOUT.ordinal(), GpuCull.SEG_CUTOUT);
        } else {
            this.drawSegment(RenderLayer.CUTOUT.ordinal(), cmdCutout, offCutout, nCutout);
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

            /* GPU-Cull: Draw-Zahlen des fence-bestätigt fertigen Slots für die Statistik
               (Fenstertitel) — winzige Lesung, die GPU ist mit dem Slot garantiert durch. */
            if (this.gpuCull.isActive()) {
                this.renderedSections = this.gpuCull.readCounts(this.frameSlot)[GpuCull.SEG_OPAQUE];
            }
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
            if (old != null) {
                old.dispose(opaqueArena, translucentArena, this.frameId);
                this.unregisterLodMesh(old);
            }

            if (result.opaqueData().length > 0 || result.translucentData().length > 0) {
                LodMesh mesh = new LodMesh(result.rx(), result.rz(), result.level(), result.sizeRegions(),
                        result.yBase(), result.opaqueData(), result.translucentData(), result.minY(), result.maxY(),
                        opaqueArena, translucentArena);
                this.lodMeshes.put(key, mesh);
                this.registerLodMesh(mesh);
                this.maxSeenQuads = Math.max(this.maxSeenQuads, mesh.maxQuads());
                uploads++;
            }
            /* Sicht-Gate der betroffenen Spalten nachziehen (auch bei leerem Ergebnis —
               die Maske kann Zellen freigegeben haben). */
            this.refreshGateForRegion(result.rx(), result.rz(), result.sizeRegions());
        }

        /* Statistik gelegentlich loggen (Budget-Annahmen verifizierbar halten); per Level
           aufgeschlüsselt als Datenbasis für das Superregionen-Mess-Gate. */
        if ((this.frameId & 2047) == 0 && !this.lodMeshes.isEmpty()) {
            long quads = 0;
            long[] lvlRegions = new long[MAX_LOD_LEVELS + 1];
            long[] lvlQuads = new long[MAX_LOD_LEVELS + 1];
            for (LodMesh mesh : this.lodMeshes.values()) {
                quads += mesh.quadCount();
                lvlRegions[mesh.level]++;
                lvlQuads[mesh.level] += mesh.quadCount();
            }
            StringBuilder sb = new StringBuilder("LOD: ").append(this.lodMeshes.size())
                    .append(" Regionen, ").append(quads).append(" Quads, ")
                    .append((quads * 4 * ChunkMesher.VERTEX_SIZE * Integer.BYTES) >> 20).append(" MB Arena |");
            for (int l = 1; l <= MAX_LOD_LEVELS; l++) {
                if (lvlRegions[l] == 0) continue;
                sb.append(" L").append(l).append("=").append(lvlRegions[l])
                        .append("R/").append(lvlQuads[l]).append("Q");
            }
            this.logger.debug(sb.toString());
        }
    }

    /**
     * Schreibt die LOD-Opaque-Regionen der Liste als eigenes Indirect-Command-Segment (eigene
     * Arena, eigener Draw-Call — baseVertex ist nur innerhalb desselben Vertex-Buffers gültig).
     * Liste = alle sichtbaren Regionen oder (Split-Modus) die eines einzelnen Levels.
     * Offset-Semantik unverändert: .xyz = Ursprung kamerarelativ, .w = 0.
     *
     * @return Anzahl geschriebener LOD-Opaque-Draws
     */
    private int writeLodOpaqueSegment(List<LodMesh> list, IntBuffer cmds, FloatBuffer offs,
                                      long cmdSegBytes, long offSegBytes, Vector3d cam) {
        int cmdBase = (int) (cmdSegBytes / Integer.BYTES);
        int offBase = (int) (offSegBytes / Float.BYTES);
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            LodMesh mesh = list.get(i);
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
            offs.put(oi + 3, mesh.invPosScale); // .w = Positions-Skala (1/256; Superregionen 1/64)
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
        for (int i = 0; i < this.visibleLodTranslucent.size(); i++) {
            LodMesh mesh = this.visibleLodTranslucent.get(i);

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
            offs.put(oi + 3, mesh.invPosScale); // .w = Positions-Skala (1/256; Superregionen 1/64)
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
            offs.put(oi + 3, 1F / 256F); // .w = Positions-Skala (2^-8 exakt -> bitidentisch)
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
     * GPU-Cull-Variante von {@link #drawSegment}: Commands/Offsets kommen aus den vom
     * Compute-Pass kompaktierten GpuCull-Puffern, die Draw-Zahl per IndirectCount aus dem
     * Count-Buffer (GL_PARAMETER_BUFFER) — die CPU kennt sie nie.
     */
    private void drawSegmentGpu(int slot, int segment) {
        GL30.glBindVertexArray(this.vaos[slot]);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.gpuCull.getCommandBuffer());
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, this.gpuCull.getCountBuffer());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.gpuCull.getOffsetBuffer(),
                this.gpuCull.offsetOffset(this.frameSlot, segment), this.gpuCull.offsetBytes(segment));
        GL46.glMultiDrawElementsIndirectCount(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                this.gpuCull.commandOffset(this.frameSlot, segment),
                this.gpuCull.countOffset(this.frameSlot, segment),
                this.gpuCull.maxDraws(segment), 0);
        GL30.glBindVertexArray(0);
    }

    /** GPU-Cull-Variante von {@link #drawLodSegment} (Polygon-Offset wie dort). */
    private void drawLodSegmentGpu(int slot, int segment) {
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(this.lodOffsetFactor, this.lodOffsetUnits);
        this.drawSegmentGpu(slot, segment);
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

    /* ------------------------- Cull-Index-Pflege (Spalten/Kacheln) ------------------------- */

    /**
     * Meldet ein Section-Mesh bei allen Cull-Strukturen an: Spalten-Index, GPU-Descriptoren
     * (OPAQUE/CUTOUT) und Translucent-Liste. Gegenstück: {@link #unregisterSectionMesh}.
     */
    private void registerSectionMesh(SectionMesh mesh) {
        CullColumn col = this.columnAdd(mesh);
        int bx = mesh.chunkX << ChunkSection.SHIFT;
        int bz = mesh.chunkZ << ChunkSection.SHIFT;
        int by = mesh.sectionY << ChunkSection.SHIFT;
        if (mesh.hasLayer(RenderLayer.OPAQUE)) {
            mesh.gpuSlotOpaque = this.gpuCull.addSection(GpuCull.SEG_OPAQUE, bx, bz, by, col.gateSlot,
                    mesh.indexCount(RenderLayer.OPAQUE), mesh.baseVertex(RenderLayer.OPAQUE));
        }
        if (mesh.hasLayer(RenderLayer.CUTOUT)) {
            mesh.gpuSlotCutout = this.gpuCull.addSection(GpuCull.SEG_CUTOUT, bx, bz, by, col.gateSlot,
                    mesh.indexCount(RenderLayer.CUTOUT), mesh.baseVertex(RenderLayer.CUTOUT));
        }
        if (mesh.hasLayer(RenderLayer.TRANSLUCENT)) this.translucentMeshes.add(mesh);
    }

    private void unregisterSectionMesh(SectionMesh mesh) {
        if (mesh.gpuSlotOpaque >= 0) this.gpuCull.removeSection(GpuCull.SEG_OPAQUE, mesh.gpuSlotOpaque);
        if (mesh.gpuSlotCutout >= 0) this.gpuCull.removeSection(GpuCull.SEG_CUTOUT, mesh.gpuSlotCutout);
        mesh.gpuSlotOpaque = -1;
        mesh.gpuSlotCutout = -1;
        if (mesh.hasLayer(RenderLayer.TRANSLUCENT)) this.translucentMeshes.remove(mesh);
        this.columnRemove(mesh);
    }

    /** Analog für LOD-Regionen: Kachel-Index, GPU-Descriptor (Opaque), Translucent-Liste. */
    private void registerLodMesh(LodMesh mesh) {
        this.lodTileAdd(mesh);
        if (mesh.hasOpaque()) {
            mesh.gpuSlot = this.gpuCull.addLod(mesh.level,
                    mesh.rx * LodMesher.REGION_BLOCKS, mesh.rz * LodMesher.REGION_BLOCKS, mesh.yBase,
                    mesh.minY, mesh.maxY, mesh.sizeBlocks, mesh.invPosScale,
                    mesh.indexCountOpaque(), mesh.baseVertexOpaque());
        }
        if (mesh.hasTranslucent()) this.lodTranslucentMeshes.add(mesh);
    }

    private void unregisterLodMesh(LodMesh mesh) {
        if (mesh.gpuSlot >= 0) {
            this.gpuCull.removeLod(mesh.level, mesh.gpuSlot);
            mesh.gpuSlot = -1;
        }
        if (mesh.hasTranslucent()) this.lodTranslucentMeshes.remove(mesh);
        this.lodTileRemove(mesh);
    }

    /**
     * Aktualisiert das Sicht-Gate der Chunk-Spalten einer LOD-Region — nach jedem
     * Mesh-Wechsel/-Abbau der Region, damit GPU- und CPU-Pfad denselben atomaren
     * Swap zeigen (applyLodResults läuft im selben Frame VOR Cull/Dispatch).
     */
    private void refreshGateForRegion(int rx, int rz, int sizeRegions) {
        int chunksPerRegion = LodMesher.REGION_BLOCKS / ChunkSection.SIZE;
        int c0x = rx * chunksPerRegion, c0z = rz * chunksPerRegion;
        int span = sizeRegions * chunksPerRegion;
        for (int cz = 0; cz < span; cz++) {
            for (int cx = 0; cx < span; cx++) {
                CullColumn col = this.cullColumns.get(Chunk.key(c0x + cx, c0z + cz));
                if (col != null && col.gateSlot >= 0) {
                    this.gpuCull.setGate(col.gateSlot,
                            this.lodManager != null && this.lodManager.lodShowsCell(col.chunkX, col.chunkZ));
                }
            }
        }
    }

    private CullColumn columnAdd(SectionMesh mesh) {
        CullColumn col = this.cullColumns.computeIfAbsent(Chunk.key(mesh.chunkX, mesh.chunkZ),
                k -> new CullColumn(mesh.chunkX, mesh.chunkZ));
        if (col.gateSlot < 0) {
            col.gateSlot = this.gpuCull.allocGate(
                    this.lodManager != null && this.lodManager.lodShowsCell(col.chunkX, col.chunkZ));
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
            if (col.gateSlot >= 0) this.gpuCull.freeGate(col.gateSlot);
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

    /** Frustum-Test + Sichtbar-Listen für ein einzelnes LOD-Mesh (aus Kachel- oder Flach-Pfad). */
    private void cullLodMesh(LodMesh mesh, FrustumIntersection frustum, Vector3d cam, boolean lodSplit) {
        float ox = (float) ((long) mesh.rx * LodMesher.REGION_BLOCKS - cam.x);
        float oz = (float) ((long) mesh.rz * LodMesher.REGION_BLOCKS - cam.z);
        float y0 = (float) (mesh.minY - cam.y);
        float y1 = (float) (mesh.maxY - cam.y);
        if (!frustum.testAab(ox, y0, oz, ox + mesh.sizeBlocks, y1, oz + mesh.sizeBlocks)) return;
        this.visibleLod.add(mesh);
        if (mesh.hasTranslucent()) this.visibleLodTranslucent.add(mesh);
        if (lodSplit) this.visibleLodByLevel[mesh.level].add(mesh);
    }

    private void lodTileAdd(LodMesh mesh) {
        if (mesh.sizeBlocks > LodMesher.REGION_BLOCKS) {
            this.lodOversize.add(mesh);
            return;
        }
        LodTile tile = this.lodTiles.computeIfAbsent(
                LodManager.key(mesh.rx >> LOD_TILE_SHIFT, mesh.rz >> LOD_TILE_SHIFT),
                k -> new LodTile(mesh.rx >> LOD_TILE_SHIFT, mesh.rz >> LOD_TILE_SHIFT));
        tile.members.add(mesh);
        if (tile.members.size() == 1) {
            tile.minY = mesh.minY;
            tile.maxY = mesh.maxY;
        } else {
            tile.minY = Math.min(tile.minY, mesh.minY);
            tile.maxY = Math.max(tile.maxY, mesh.maxY);
        }
    }

    private void lodTileRemove(LodMesh mesh) {
        if (mesh.sizeBlocks > LodMesher.REGION_BLOCKS) {
            this.lodOversize.remove(mesh);
            return;
        }
        long key = LodManager.key(mesh.rx >> LOD_TILE_SHIFT, mesh.rz >> LOD_TILE_SHIFT);
        LodTile tile = this.lodTiles.get(key);
        if (tile == null || !tile.members.remove(mesh)) return;
        if (tile.members.isEmpty()) {
            this.lodTiles.remove(key);
            return;
        }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (LodMesh m : tile.members) {
            if (m.minY < lo) lo = m.minY;
            if (m.maxY > hi) hi = m.maxY;
        }
        tile.minY = lo;
        tile.maxY = hi;
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
        this.cullColumns.clear();
        for (LodMesh mesh : this.lodMeshes.values()) {
            mesh.dispose(this.arenas[LOD_OPAQUE], this.arenas[LOD_TRANSLUCENT], this.frameId);
        }
        this.lodMeshes.clear();
        this.lodTiles.clear();
        this.lodOversize.clear();
        this.translucentMeshes.clear();
        this.lodTranslucentMeshes.clear();
        this.gpuCull.dispose();
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
                /* Positions-Skala pro Draw aus .w (Sections + normales LOD: 1/256; LOD-
                   Superregionen: 1/64 -- u16-Fixed-Point traegt sonst nur ~255 Bloecke). */
                vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16), float(a_data.y & 0xFFFFu)) * u_DrawOffsets[gl_DrawID].w - 1.0;
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
