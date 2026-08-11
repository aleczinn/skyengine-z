package de.skyengine.graphics.world;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.EngineProperties;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.lod.LodConfig;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.lod.LodMesher;
import de.skyengine.graphics.DebugFlags;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.shaderpack.ShaderPack;
import de.skyengine.graphics.shaderpack.ShaderPackManager;
import de.skyengine.graphics.shaderpack.ShaderPackManifest;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.texture.TextureArray;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.ARBClipControl;

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
public class ChunkRenderer implements ShaderPackManager.Participant {

    private static final int ATMOSPHERE_FOG_UNIT = 7;
    private static final int WATER_NOISE_UNIT = 8;
    private static final int OPAQUE_COLOR_UNIT = 9;
    private static final int OPAQUE_DEPTH_UNIT = 10;
    private static final int SKY_REFLECTION_UNIT = 11;
    private static final int SHADOW_DEPTH_SOLID_UNIT = 12;
    private static final int SHADOW_DEPTH_ALL_UNIT = 13;
    private static final int SHADOW_COLOR_UNIT = 14;
    private static final int SKY_SH_BINDING = 3;

    private static final int SLOTS = 3;                 // Frames in flight (Command-/Offset-Ringe)
    private static final int COMMAND_BYTES = 20;        // DrawElementsIndirectCommand (5 uints)
    private static final int OFFSET_BYTES = 16;         // vec4 im std430-SSBO

    private final Logger logger = LogManager.getLogger(ChunkRenderer.class.getName());

    private final ChunkManager chunkManager;
    private ShaderProgram shader;
    private ShaderProgram waterShadowShader;
    private ShaderPackManager shaderPackManager;
    private long animationStartNanos;
    /* Block-Atlas: gehört dem GameContainer (Engine-Lebensdauer, welt-unabhängig) —
       der Renderer hält nur Referenzen und disposed NICHTS davon. */
    private BlockTextureAtlas atlas;
    private TextureArray textures;
    private int atmosphereFogTexture;
    private int waterNoiseTexture;
    private int skyReflectionTexture;
    private int skyShBuffer;
    private static final int DEFAULT_WATER_SHADOW_SIZE = 2048;
    private static final float WATER_SHADOW_DISTANCE = 128F;
    private int waterShadowSize = DEFAULT_WATER_SHADOW_SIZE;
    private int waterShadowAllFbo;
    private int waterShadowSolidFbo;
    private int waterShadowDepthAll;
    private int waterShadowDepthSolid;
    private int waterShadowColor;
    private final Matrix4f waterShadowMatrix = new Matrix4f();
    private final Matrix4f waterShadowProjection = new Matrix4f();
    private final Matrix4f waterShadowView = new Matrix4f();
    private final Vector3f waterShadowLight = new Vector3f();
    private final Vector3f waterShadowTarget = new Vector3f();
    private final Vector3f waterShadowUp = new Vector3f();
    /* Eigene, vom Kamera-Frustum unabhaengige Command-Segmente fuer Photons Lichtpass.
       Ein Caster darf nicht verschwinden, nur weil er hinter der Hauptkamera liegt. */
    private long shadowCmdOpaque, shadowOffOpaque, shadowCmdCutout, shadowOffCutout;
    private long shadowCmdDetail, shadowOffDetail;
    private long shadowCmdTranslucent, shadowOffTranslucent;
    private int shadowNOpaque, shadowNCutout, shadowNDetail;

    /* sectionKey -> mesh, render thread only. LongObjMap: kein Long-Boxing pro Zugriff,
       Cleanup-Walk und Frame-Iterationen laufen als flacher Array-Scan. */
    private final LongObjMap<SectionMesh> meshes = new LongObjMap<>(4096);

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

    private final LongObjMap<CullColumn> cullColumns = new LongObjMap<>(1024);

    /* Pro Frame neu befüllt: alle Sections, die den Frustum-Test bestanden haben */
    private final List<SectionMesh> visible = new ArrayList<>();

    /* Teilmenge von visible mit TRANSLUCENT-Layer - nur diese werden back-to-front sortiert */
    private final List<SectionMesh> translucentVisible = new ArrayList<>();

    /* Alle geladenen Section-Meshes, deren AABB die lichtseitige 128-Block-Projektion
       schneidet. Diese Liste ist bewusst unabhaengig von visible/translucentVisible. */
    private final List<SectionMesh> shadowVisible = new ArrayList<>();

    /* Wiederverwendete Scratch-Puffer für die Translucent-Sortierung (Key-Array statt
       Comparator: der Lambda-Sort rechnete distanceSq ZWEIMAL pro Vergleich, also
       ~2·n·log n statt n mal, und allozierte pro Frame — Muster wie SectionMesh.sortTranslucent). */
    private long[] translucentSortKeys = new long[0];
    private SectionMesh[] translucentSortScratch = new SectionMesh[0];

    /* Alle Meshes mit TRANSLUCENT-Layer (Mitgliedschafts-Hooks in applyBatch/Cleanup):
       im GPU-Cull-Pfad entfällt der große Section-Loop, Translucent bleibt aber CPU
       (Sortierung braucht CPU-Reihenfolge) — dafür reicht diese kleine Liste. */
    private final List<SectionMesh> translucentMeshes = new ArrayList<>();

    /* Kleinvegetation (Detail-Segment in der CUTOUT-Arena): sichtbare Meshes pro Frame +
       Mitglieds-Liste für den GPU-Cull-Pfad (dort wird Detail vorerst CPU-gecullt und über
       den Mapped-Ring gezeichnet — GpuCull-Descriptor-Integration kommt mit P4). */
    private final List<SectionMesh> visibleDetail = new ArrayList<>();
    private final List<SectionMesh> detailMeshes = new ArrayList<>();

    /* Anker der Vegetations-Ausdünnung: die Fade-Distanz wird NICHT von der Live-Position
       gerechnet (jede Bewegung ließe einzelne Pflanzen nacheinander auftauchen), sondern vom
       zuletzt gesetzten Chunk-Zentrum; der Anker springt erst nach, wenn der Spieler sich
       DETAIL_ANCHOR_HYSTERESE Blöcke entfernt hat -> seltene Batch-Pops statt Dauer-Getröpfel,
       und kein Hin-und-her-Flackern beim Entlanglaufen einer Chunk-Grenze (LOD-Anker-Muster). */
    private static final double DETAIL_ANCHOR_HYSTERESE = 48.0;
    private double detailAnchorX = Double.NaN, detailAnchorZ;

    /* GPU-Cull-Substrat (P3): Compute-Frustum + Command-Kompaktierung + IndirectCount für
       OPAQUE/CUTOUT/LOD-OPAQUE. CPU-Pfad bleibt vollständig erhalten (Fallback + A/B). */
    private final GpuCull gpuCull = new GpuCull();

    /* --- Heightmap-LOD: zusätzliche Draws im OPAQUE-Segment (gleiche Arena, gleicher Shader) --- */

    /* regionKey -> LOD-Mesh, render thread only. null-Manager = LOD aus (rendert wie bisher). */
    private final LongObjMap<LodMesh> lodMeshes = new LongObjMap<>(1024);
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
    private final LongObjMap<LodTile> lodTiles = new LongObjMap<>(256);
    private final List<LodMesh> lodOversize = new ArrayList<>();

    /* Sichtbare LOD-Regionen mit Translucent-Anteil (pro Frame): im CPU-Pfad aus dem
       Kachel-Cull befüllt, im GPU-Pfad aus lodTranslucentMeshes (Kleinstmenge, flach). */
    private final List<LodMesh> visibleLodTranslucent = new ArrayList<>();
    private final List<LodMesh> lodTranslucentMeshes = new ArrayList<>();

    private static final int MAX_LOD_UPLOADS_PER_FRAME = 4;

    /* Deckel für die Vorab-Reservierung je Arena (s. cappedArenaBytes). */
    private static final long MAX_INITIAL_ARENA_BYTES = 768L << 20;

    /* Weicher Deckel der Priority-Uploads (Edit-Remeshes): Einzel-Edits liegen weit darunter,
       Explosions-Wellen verteilen sich über wenige Frames (s. renderSolid Schritt 2a). */
    private static final int MAX_PRIORITY_UPLOADS_PER_FRAME = 24;

    /* Letzter LOD-Settings-Stand für die Arena-Vorabvergrößerung (s. applyLodResults) */
    private int lastLodRenderDistance = -1, lastLodMaxDistance = -1;
    private boolean lastLodEnabled;

    /* Gate für die Cleanup-Walks (Schritte 3/3b): die O(Meshes)-Prüfungen laufen nur noch
       in Frames, in denen sich Chunk-Set bzw. LOD-Desired-Set wirklich geändert haben
       (gemessen ~250 µs/Frame gespart im Steady-State). -1 = beim ersten Frame laufen. */
    private int lastChunkRemovalVersion = -1;
    private int lastLodDesiredVersion = -1;

    /* Letzter Zustand von gpuCull.isActive() — Flanke schaltet refreshAllGates (s. renderSolid). */
    private boolean lastGpuActive;

    /* Fence-Diagnose (nur bei aktivem FrameProfiler gefüllt, s. beginFrame) */
    private long syncFrames, syncSignaled, syncWaitNs, syncWaitMaxNs;

    /* GPU-Cull-Draw-Telemetrie: min/max der per-Frame-Draw-Counts je Segment und Phase über
       die letzte Sekunde (aus dem gemappten Count-Readback; [0..SEGMENTS) = Phase 1,
       [SEGMENTS..2*SEGMENTS) = Phase 2). Two-Phase-Abnahmekriterium: die SUMME P1+P2 eines
       Segments muss bei statischer Kamera konstant sein — einzelne Grenzobjekte dürfen die
       Phase wechseln. Springende Summen = objektiver Flacker-Nachweis inkl. WELCHES Segment. */
    private final int[] gpuDrawMin = new int[2 * GpuCull.SEGMENTS];
    private final int[] gpuDrawMax = new int[2 * GpuCull.SEGMENTS];
    /* Zusätzlich die PER-FRAME-SUMME P1+P2 je Segment (min/max über die Sekunde): die
       Phasen-Spannen alleine können ein Loch verstecken (P1 und P2 unabhängig getrackt) —
       nur eine konstante SUMME bei statischer Kamera beweist „nichts fehlt". */
    private final int[] gpuSumMin = new int[GpuCull.SEGMENTS];
    private final int[] gpuSumMax = new int[GpuCull.SEGMENTS];
    private boolean gpuDrawStatsValid;

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
    private int locProjectionView, locAlphaCutoff, locFogStart, locFogEnd, locFogEnabled,
            locDetailFade, locDetailCamSnap, locMinLight, locBrightness, locTranslucentPass,
            locCameraPosition, locTime;

    /* Grundhelligkeit bei Lichtlevel 0 (Minecraft-Niveau): eine unbeleuchtete Höhle ist nie
       exakt schwarz. Der Regler hebt genau diesen Wert mit an (0,04 bei 0 % bis 0,15 bei 100 %,
       s. Fragment-Shader) — deshalb steht der Boden dort VOR der Kurve. */
    private static final float AMBIENT_LIGHT = 0.04F;
    /* Zuletzt hochgeladene Werte — Upload nur bei Änderung (wie bei den Fog-Uniforms). */
    private float lastMinLight = Float.NaN;
    private float lastBrightness = Float.NaN;

    /* Zuletzt hochgeladene Fog-Werte: Upload nur bei Änderung der Settings —
       die Werte sind pro Frame konstant, ein Re-Upload pro Pass wäre doppelt umsonst. */
    private float lastFogStart = Float.NaN, lastFogEnd = Float.NaN;
    private boolean lastFogEnabled;

    public ChunkRenderer(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
        for (int l = 1; l <= MAX_LOD_LEVELS; l++) this.visibleLodByLevel[l] = new ArrayList<>();
    }

    /** Verdrahtet das LOD-System (aus World.init). Ohne Manager rendert alles wie bisher. */
    public void setLodManager(LodManager lodManager) {
        this.lodManager = lodManager;
    }


    /** Render thread, GL context required. Der Atlas muss bereits gebaut sein (Boot). */
    public void init(BlockTextureAtlas atlas) {
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        if (!properties.isUseMultiDrawIndirect() || !properties.isUseBufferStorage()) {
            throw new IllegalStateException("ChunkRenderer benötigt MultiDrawIndirect (GL 4.3) + BufferStorage (GL 4.4)");
        }

        /* Tiefen-Bias-Richtung je Depth-Modus (s. Feld-Kommentar lodOffsetFactor) */
        float sign = properties.isUseInverseDepth() ? -1F : 1F;
        this.lodOffsetFactor = 0F;          // KEIN Steigungsterm — s. Feld-Kommentar!
        this.lodOffsetUnits = sign * 8F;    // konstanter Bias (ersetzt den Steigungsanteil)

        this.shaderPackManager = SkyEngine.get().getShaderPackManager();
        this.shader = this.compileTerrain(this.shaderPackManager.active());
        this.waterShadowShader = this.compileWaterShadow(this.shaderPackManager.active());
        this.shaderPackManager.register(this);
        this.animationStartNanos = System.nanoTime();
        /* Uniform-Locations einmalig cachen; u_Textures ändert sich nie -> einmal setzen */
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locAlphaCutoff = this.shader.getUniformLocation("u_AlphaCutoff");
        this.locFogStart = this.shader.getUniformLocation("u_FogStart");
        this.locFogEnd = this.shader.getUniformLocation("u_FogEnd");
        this.locFogEnabled = this.shader.getUniformLocation("u_FogEnabled");
        this.locDetailFade = this.shader.getUniformLocation("u_DetailFade");
        this.locDetailCamSnap = this.shader.getUniformLocation("u_DetailCamSnap");
        this.locMinLight = this.shader.getUniformLocation("u_MinLight");
        this.locBrightness = this.shader.getUniformLocation("u_Brightness");
        this.locTranslucentPass = this.shader.getUniformLocation("u_TranslucentPass");
        this.locCameraPosition = this.shader.getUniformLocation("u_CameraPosition");
        this.locTime = this.shader.getUniformLocation("u_Time");
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformi("u_AtmosphereFog", ATMOSPHERE_FOG_UNIT);
        this.setWaterLayers(this.shader);
        this.shader.setUniformVector2f(this.locDetailFade, 0F, 0F); // Ausdünnung default aus
        this.shader.unbind();
        /* Atlas kommt von außen (BlockTextureAtlas, einmal beim Boot gebaut) — Welt-Ein-/
           Austritte erzeugen ihn nicht neu (Layer-Indizes stecken in den gebackenen Modellen). */
        this.atlas = atlas;
        this.textures = atlas.textures();

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
                cappedArenaBytes("OPAQUE", Math.max(96L << 20, holdChunks * 256L * 1024)));
        this.arenas[RenderLayer.CUTOUT.ordinal()] = new VertexArena("VertexArena CUTOUT",
                cappedArenaBytes("CUTOUT", Math.max(64L << 20, holdChunks * 256L * 1024)));
        this.arenas[RenderLayer.TRANSLUCENT.ordinal()] = new VertexArena("VertexArena TRANSLUCENT",
                cappedArenaBytes("TRANSLUCENT", Math.max(8L << 20, holdChunks * 16L * 1024)));
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
        this.arenas[LOD_OPAQUE] = new VertexArena("VertexArena LOD-OPAQUE",
                cappedArenaBytes("LOD-OPAQUE", lodOpaqueBytes));
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
        this.waterShadowSize = shadowMapSize(this.shaderPackManager.active());
        this.createWaterShadowTarget();

        this.logger.info("MDI-Renderer: Arenen " + (this.arenas[0].getCapacity() >> 20) + "/"
                + (this.arenas[1].getCapacity() >> 20) + "/" + (this.arenas[2].getCapacity() >> 20)
                + " MB (Sections), " + (this.arenas[LOD_OPAQUE].getCapacity() >> 20) + "/"
                + (this.arenas[LOD_TRANSLUCENT].getCapacity() >> 20) + " MB (LOD), "
                + SLOTS + " Frame-Slots");
    }

    /**
     * VRAM-Deckel für die Vorab-Reservierung einer Arena: bei sehr großen Render-Distanzen
     * rechnet die (rd+6)-Formel sonst in den Multi-GB-Bereich (rd=32 ≈ 1,13 GB je für OPAQUE
     * und CUTOUT) — ohne jeden Fallback, wenn der Treiber das nicht mehr hergibt. Der Deckel
     * betrifft NUR die Startgröße; die Arena wächst bei echtem Bedarf weiter (grow/
     * ensureCapacity bleiben ungedeckelt, createBuffer wirft jetzt bei GL_OUT_OF_MEMORY).
     * Jede geklemmte Arena wird geloggt: ab da sind Grow-Ruckler beim Flug wieder möglich.
     */
    @Override
    public ShaderPackManager.Prepared prepare(ShaderPack pack) {
        ShaderProgram terrain = this.compileTerrain(pack);
        try {
            return new PreparedTerrain(terrain, this.compileWaterShadow(pack), shadowMapSize(pack));
        } catch (RuntimeException e) {
            terrain.dispose();
            throw e;
        }
    }

    @Override
    public void activate(ShaderPackManager.Prepared prepared) {
        PreparedTerrain nextResources = (PreparedTerrain) prepared;
        ShaderProgram next = nextResources.takeTerrain();
        ShaderProgram nextShadow = nextResources.takeShadow();
        ShaderProgram previous = this.shader;
        ShaderProgram previousShadow = this.waterShadowShader;
        this.shader = next;
        this.waterShadowShader = nextShadow;
        if (this.waterShadowSize != nextResources.shadowSize()) {
            this.waterShadowSize = nextResources.shadowSize();
            this.disposeWaterShadowTarget();
            this.createWaterShadowTarget();
        }
        this.cacheTerrainUniforms();
        this.lastFogStart = Float.NaN;
        this.lastFogEnd = Float.NaN;
        this.lastMinLight = Float.NaN;
        this.lastBrightness = Float.NaN;
        if (previous != null) previous.dispose();
        if (previousShadow != null) previousShadow.dispose();
    }

    private ShaderProgram compileTerrain(ShaderPack pack) {
        ShaderProgram program = new ShaderProgram(
                new Shader(this.shaderPackManager.program(pack, "terrain_vertex"), ShaderType.VERTEX),
                new Shader(this.shaderPackManager.program(pack, "terrain_fragment"), ShaderType.FRAGMENT));
        program.bind();
        program.setUniformi("u_Textures", 0);
        program.setUniformi("u_AtmosphereFog", ATMOSPHERE_FOG_UNIT);
        program.setUniformi("u_WaterNoise", WATER_NOISE_UNIT);
        program.setUniformi("u_OpaqueColor", OPAQUE_COLOR_UNIT);
        program.setUniformi("u_OpaqueDepth", OPAQUE_DEPTH_UNIT);
        program.setUniformi("u_SkyReflection", SKY_REFLECTION_UNIT);
        program.setUniformi("u_ShadowDepthSolid", SHADOW_DEPTH_SOLID_UNIT);
        program.setUniformi("u_ShadowDepthAll", SHADOW_DEPTH_ALL_UNIT);
        program.setUniformi("u_ShadowColor", SHADOW_COLOR_UNIT);
        program.setUniformVector2f("u_DetailFade", 0F, 0F);
        this.setWaterLayers(program);
        program.unbind();
        return program;
    }

    private ShaderProgram compileWaterShadow(ShaderPack pack) {
        ShaderProgram program = new ShaderProgram(
                new Shader(this.shaderPackManager.program(pack, "water_shadow_vertex"), ShaderType.VERTEX),
                new Shader(this.shaderPackManager.program(pack, "water_shadow_fragment"), ShaderType.FRAGMENT));
        program.bind();
        program.setUniformi("u_Textures", 0);
        this.setWaterLayers(program);
        program.unbind();
        return program;
    }

    private static int shadowMapSize(ShaderPack pack) {
        ShaderPackManifest.Resource resource = pack.manifest().resources.get("shadow_depth_solid");
        if (resource == null || resource.width <= 0 || resource.height <= 0) {
            return DEFAULT_WATER_SHADOW_SIZE;
        }
        if (resource.width != resource.height) {
            throw new IllegalArgumentException("Photon shadow_depth_solid must be square");
        }
        for (String name : List.of("shadow_depth_all", "shadow_color")) {
            ShaderPackManifest.Resource peer = pack.manifest().resources.get(name);
            if (peer != null && (peer.width != resource.width || peer.height != resource.height)) {
                throw new IllegalArgumentException(name
                        + " must match shadow_depth_solid dimensions");
            }
        }
        return resource.width;
    }

    private static void setWaterLayers(ShaderProgram program) {
        FluidInfo water = Blocks.getState(Blocks.WATER).getBlock().getFluidInfo();
        program.setUniformf("u_WaterStillLayer", water.stillLayer);
        program.setUniformf("u_WaterFlowLayer", water.flowLayer);
    }

    private void cacheTerrainUniforms() {
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locAlphaCutoff = this.shader.getUniformLocation("u_AlphaCutoff");
        this.locFogStart = this.shader.getUniformLocation("u_FogStart");
        this.locFogEnd = this.shader.getUniformLocation("u_FogEnd");
        this.locFogEnabled = this.shader.getUniformLocation("u_FogEnabled");
        this.locDetailFade = this.shader.getUniformLocation("u_DetailFade");
        this.locDetailCamSnap = this.shader.getUniformLocation("u_DetailCamSnap");
        this.locMinLight = this.shader.getUniformLocation("u_MinLight");
        this.locBrightness = this.shader.getUniformLocation("u_Brightness");
        this.locTranslucentPass = this.shader.getUniformLocation("u_TranslucentPass");
        this.locCameraPosition = this.shader.getUniformLocation("u_CameraPosition");
        this.locTime = this.shader.getUniformLocation("u_Time");
    }

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
    public void renderSolid(Camera camera, Vector3f sunDirection) {
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
                && (batch = this.chunkManager.getPriorityUploadQueue().poll()) != null) {
            this.applyBatch(batch);
            priorityUploads++;
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
            this.meshes.removeIf((key, mesh) -> {
                if (this.chunkManager.getChunks().containsKey(Chunk.key(mesh.chunkX, mesh.chunkZ))) return false;
                mesh.dispose(this.arenas, this.frameId);
                this.unregisterSectionMesh(mesh);
                return true;
            });
        }

        /* 3b. Nicht mehr gewünschte LOD-Regionen freigeben (deferred) — analog gegated über
           die Desired-Version (bumpt bei jedem recomputeDesired, auch Anker-Bewegung). */
        if (this.lodManager != null && this.lodManager.getDesiredVersion() != this.lastLodDesiredVersion) {
            this.lastLodDesiredVersion = this.lodManager.getDesiredVersion();
            VertexArena lodOpaqueArena = this.arenas[LOD_OPAQUE];
            VertexArena lodTranslucentArena = this.arenas[LOD_TRANSLUCENT];
            this.lodMeshes.removeIf((key, mesh) -> {
                if (this.lodManager.isDesiredKey(key)) return false;
                mesh.dispose(lodOpaqueArena, lodTranslucentArena, this.frameId);
                this.unregisterLodMesh(mesh);
                this.refreshGateForRegion(mesh.rx, mesh.rz, mesh.sizeBlocks / LodMesher.REGION_BLOCKS);
                return true;
            });
        }

        FrameProfiler.cpuStop(FrameProfiler.Cpu.UPLOAD);

        /* 4. Frustum culling, einmal pro Frame */
        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        FrameProfiler.cpuStart(FrameProfiler.Cpu.CULL);

        /* Photon erzeugt seine Schatten aus Sicht des Sonnenlichts. Insbesondere duerfen
           Sections hinter oder seitlich neben der Kamera weiterhin Schatten werfen. */
        this.prepareWaterShadow(sunDirection, cam);

        boolean gpu = this.gpuCull.isActive();
        /* Pflicht-Begleitfix zum Gate-Gating in refreshGateForRegion: solange der GPU-Pfad aus
           war, sind LOD-Wechsel nicht ins Sicht-Gate gewandert. Beim Einschalten deshalb alle
           Spalten-Gates einmal neu setzen — sonst zeigt der erste GPU-Frame Sections, die das
           LOD noch deckt (Doppelbild), oder verbirgt welche, die es nicht mehr deckt (Loch). */
        if (gpu && !this.lastGpuActive) this.refreshAllGates();
        this.lastGpuActive = gpu;
        /* Single-Phase-Entscheid VOR dem Dispatch: ohne sample-bare Depth-Textur (MSAA-Modus)
           oder ohne Szene-FBO gibt es keine Hi-Z-Pyramide — dann alles in EINEM Durchlauf
           zeichnen, statt Phase 2 (Dispatches + Barrier + zweiter Draw-Satz) für ein
           identisches Ergebnis mitzubezahlen. */
        boolean gpuSinglePhase = false;
        if (gpu) {
            var sceneFb = SkyEngine.get().getWindow().getFrameBuffer();
            gpuSinglePhase = GpuCull.FRUSTUM_ONLY
                    || sceneFb.getDepthTexture() == 0 || sceneFb.getId() == 0;
        }
        this.visible.clear();
        this.translucentVisible.clear();
        this.visibleLodTranslucent.clear();
        this.visibleDetail.clear();
        this.totalSections = this.meshes.size();

        int opaqueDraws = 0, cutoutDraws = 0;
        boolean lodSplit = DebugFlags.lodLevelSplit && FrameProfiler.isEnabled();
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
            for (int i = 0; i < this.detailMeshes.size(); i++) {
                SectionMesh mesh = this.detailMeshes.get(i);
                if (this.lodManager != null && this.lodManager.lodShowsCell(mesh.chunkX, mesh.chunkZ)) continue;
                float ox = offsetX(mesh, cam);
                float oy = offsetY(mesh, cam);
                float oz = offsetZ(mesh, cam);
                if (!frustum.testAab(ox, oy, oz, ox + size, oy + size, oz + size)) continue;
                this.visibleDetail.add(mesh);
            }
            FrameProfiler.gpuBegin(FrameProfiler.Gpu.CULL_P1);
            this.gpuCull.dispatchPhase1(this.frameSlot, camera, gpuSinglePhase);
            FrameProfiler.gpuEnd(FrameProfiler.Gpu.CULL_P1);
        } else {
        for (int ci = 0, cn = this.cullColumns.tableSize(); ci < cn; ci++) {
            CullColumn col = this.cullColumns.valueAt(ci);
            if (col == null) continue;
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
                if (mesh.hasDetail()) this.visibleDetail.add(mesh);
            }
        }
        this.renderedSections = this.visible.size();

        /* 4b. LOD-Regionen: Kachel-AABB zuerst, dann Regionen (nur CPU-Pfad). */
        int tileBlocks = LodMesher.REGION_BLOCKS << LOD_TILE_SHIFT;
        for (int ti = 0, tn = this.lodTiles.tableSize(); ti < tn; ti++) {
            LodTile tile = this.lodTiles.valueAt(ti);
            if (tile == null) continue;
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
        int detailDraws = this.visibleDetail.size();
        int shadowDraws = this.shadowVisible.size();
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
                        + MappedRing.align((long) detailDraws * COMMAND_BYTES)
                        + MappedRing.align((long) translucentDraws * COMMAND_BYTES)
                        + MappedRing.align((long) lodTDraws * COMMAND_BYTES)
                        + MappedRing.align((long) shadowDraws * COMMAND_BYTES) * 4L);
        this.offsetRing.ensureSlotCapacity(
                MappedRing.align((long) opaqueDraws * OFFSET_BYTES)
                        + lodOffCap
                        + MappedRing.align((long) cutoutDraws * OFFSET_BYTES)
                        + MappedRing.align((long) detailDraws * OFFSET_BYTES)
                        + MappedRing.align((long) translucentDraws * OFFSET_BYTES)
                        + MappedRing.align((long) lodTDraws * OFFSET_BYTES)
                        + MappedRing.align((long) shadowDraws * OFFSET_BYTES) * 4L);

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

        /* Kleinvegetations-Segment (CUTOUT-Arena, eigener Draw für die Distanz-Ausdünnung).
           Läuft in BEIDEN Cull-Pfaden über den Mapped-Ring (GPU-Descriptor-Integration: P4). */
        long cmdDetail = cmdCutout + MappedRing.align((long) nCutout * COMMAND_BYTES);
        long offDetail = offCutout + MappedRing.align((long) nCutout * OFFSET_BYTES);
        int nDetail = this.writeDetailSegment(this.visibleDetail,
                cmds, offs, cmdDetail, offDetail, cam);

        /* Cursor für das Translucent-Segment in renderTranslucent merken */
        this.cmdCursor = cmdDetail + MappedRing.align((long) nDetail * COMMAND_BYTES);
        this.offCursor = offDetail + MappedRing.align((long) nDetail * OFFSET_BYTES);

        /* Hinter den fuer renderTranslucent reservierten Hauptkamera-Segmenten liegen die
           drei separaten Licht-Frustum-Segmente. Obergrenzen halten sie auch dann getrennt,
           wenn Translucent-Quads spaeter neu sortiert und umgelagert werden. */
        this.shadowCmdOpaque = this.cmdCursor
                + MappedRing.align((long) translucentDraws * COMMAND_BYTES)
                + MappedRing.align((long) lodTDraws * COMMAND_BYTES);
        this.shadowOffOpaque = this.offCursor
                + MappedRing.align((long) translucentDraws * OFFSET_BYTES)
                + MappedRing.align((long) lodTDraws * OFFSET_BYTES);
        this.shadowNOpaque = this.writeSegment(RenderLayer.OPAQUE, this.shadowVisible,
                cmds, offs, this.shadowCmdOpaque, this.shadowOffOpaque, cam);

        this.shadowCmdCutout = this.shadowCmdOpaque
                + MappedRing.align((long) shadowDraws * COMMAND_BYTES);
        this.shadowOffCutout = this.shadowOffOpaque
                + MappedRing.align((long) shadowDraws * OFFSET_BYTES);
        this.shadowNCutout = this.writeSegment(RenderLayer.CUTOUT, this.shadowVisible,
                cmds, offs, this.shadowCmdCutout, this.shadowOffCutout, cam);

        this.shadowCmdDetail = this.shadowCmdCutout
                + MappedRing.align((long) shadowDraws * COMMAND_BYTES);
        this.shadowOffDetail = this.shadowOffCutout
                + MappedRing.align((long) shadowDraws * OFFSET_BYTES);
        this.shadowNDetail = this.writeDetailSegment(this.shadowVisible,
                cmds, offs, this.shadowCmdDetail, this.shadowOffDetail, cam);

        this.shadowCmdTranslucent = this.shadowCmdDetail
                + MappedRing.align((long) shadowDraws * COMMAND_BYTES);
        this.shadowOffTranslucent = this.shadowOffDetail
                + MappedRing.align((long) shadowDraws * OFFSET_BYTES);
        int shadowNTranslucent = this.writeSegment(RenderLayer.TRANSLUCENT,
                this.shadowVisible, cmds, offs, this.shadowCmdTranslucent,
                this.shadowOffTranslucent, cam);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.WRITE);

        /* Photons Schattenkarte entsteht vor dem Terrain-Lighting desselben Frames. */
        this.renderWaterOcclusion();
        /* In Photon enthaelt shadowtex0 bereits vor dem Deferred-Lighting transparente
           Blocker. Ohne diesen Draw wurde Wasser erst nach dem Terrain in die Shadowmap
           geschrieben; farbige Wassertransmission konnte den Meeresboden daher nie
           erreichen. LOD-Wasser liegt ausserhalb des 128-Block-Nahbereichs. */
        this.appendWaterToShadow(this.shadowCmdTranslucent,
                this.shadowOffTranslucent, shadowNTranslucent, 0L, 0L, 0);

        /* 7. Render-Pässe: opaque & cutout (Alpha-Test bei 0.5) — je EIN Draw-Call.
           u_Textures ist einmalig gesetzt (init); Fog lädt nur bei Wertänderung hoch. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GLSUB);
        this.shader.bind();
        this.shaderPackManager.applySettings(this.shader);
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.shader.setUniformVector3f(this.locCameraPosition,
                (float) cam.x, (float) cam.y, (float) cam.z);
        this.shader.setUniformf(this.locTime,
                (System.nanoTime() - this.animationStartNanos) * 1.0e-9F);
        this.shader.setUniformf("u_Frame", (float) this.frameId);
        this.shader.setUniformMatrix4f("u_ShadowProjectionView", this.waterShadowMatrix);
        this.setFogUniforms();
        this.setLightUniforms();
        this.textures.bind(0);
        this.bindAtmosphereFog();
        this.bindSkySh();
        this.bindWaterNoise();
        this.bindShadowInputs();

        /* GPU-Pfad: Compute-Ergebnisse (Commands/Offsets/Counts) vor den Draws sichtbar machen. */
        if (gpu) {
            this.gpuCull.barrier();
        }

        this.shader.setUniformf(this.locAlphaCutoff, 0.5F);
        this.shader.setUniformf(this.locTranslucentPass, 0F);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SOLID);
        if (gpu) {
            this.drawSegmentGpu(RenderLayer.OPAQUE.ordinal(), GpuCull.SEG_OPAQUE, 0);
        } else {
            this.drawSegment(RenderLayer.OPAQUE.ordinal(), cmdOpaque, offOpaque, nOpaque);
        }
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SOLID);
        if (gpu) {
            /* LOD gemergt: EIN Draw für alle Level (früher K Draws mit je der vollen
               LOD-Descriptor-Zahl). Die per-Level-GPU-Queries gibt es damit im GPU-Pfad
               nicht mehr — per-Level-Zahlen liefert weiterhin die LodManager-Statistik. */
            if (this.gpuCull.hasLod()) {
                this.drawLodSegmentGpu(LOD_OPAQUE, GpuCull.SEG_LOD, 0);
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
            this.drawSegmentGpu(RenderLayer.CUTOUT.ordinal(), GpuCull.SEG_CUTOUT, 0);
        } else {
            this.drawSegment(RenderLayer.CUTOUT.ordinal(), cmdCutout, offCutout, nCutout);
        }
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

        /* Two-Phase-Occlusion, Phase 2: Hi-Z-Pyramide aus dem Phase-1-Depth DIESES Frames
           bauen (nach OPAQUE/CUTOUT/Detail, VOR dem Translucent-Pass — die Wasseroberfläche
           schreibt Depth, darf aber nichts verdecken, sonst cullt sich der Meeresboden gegen
           seine eigene koplanare AABB-Oberkante; BlockEntities/Entities fehlen im Depth: nur
           weniger Culling, nie falsches). Danach testet der Phase-2-Compute ALLE Descriptoren
           gegen diese Same-Frame-Pyramide und zeichnet Nachzügler sofort — die Pyramide ist
           damit am Frame-Ende immer vollständig, der Selbst-Feedback-Loop des Ein-Phasen-
           Hi-Z (LOD-Ring-Flackern in Linien) ist strukturell weg. */
        if (gpu && !gpuSinglePhase) {
            var window = SkyEngine.get().getWindow();
            FrameProfiler.gpuBegin(FrameProfiler.Gpu.HIZ);
            this.gpuCull.buildPyramid(window.getFrameBuffer().getDepthTexture(),
                    window.getWidth(), window.getHeight(), window.getFrameBuffer().getId());
            FrameProfiler.gpuEnd(FrameProfiler.Gpu.HIZ);
            FrameProfiler.gpuBegin(FrameProfiler.Gpu.CULL_P2);
            this.gpuCull.dispatchPhase2(this.frameSlot, camera);
            FrameProfiler.gpuEnd(FrameProfiler.Gpu.CULL_P2);
            this.drawPhase2();
        } else if (gpu) {
            /* Single-Phase: keine zweite Phase — Counts sofort kopieren. */
            this.gpuCull.copyCounts();
        }

        this.shader.unbind();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GLSUB);
    }

    /**
     * Two-Phase-Occlusion, zweiter Draw-Satz: Barrier + die Nachzügler (in diesem Frame sichtbar
     * geworden, in Phase 1 nicht gezeichnet). Läuft innerhalb der GLSUB-Sektion des Aufrufers.
     *
     * <p>Gemessen und VERWORFEN: diese Draws hinter den Entity-Pass zu verschieben (damit die
     * Barrier-Drain hinter echter Arbeit liegt) kostete 22 µs MEHR statt weniger — BlockEntity-
     * und Entity-Pass sind hier &lt;10 µs, es gibt nichts, wohinter sich Latenz verstecken ließe,
     * nur zusätzliche State-Wechsel. Erst wenn diese Pässe real teuer werden, lohnt ein neuer
     * Versuch. */
    private void drawPhase2() {
        this.gpuCull.barrier();
        /* buildPyramid/dispatchPhase2 und der Entity-Pass haben Programm + Texturbindung
           verstellt — Draw-State wiederherstellen (PV/Fog/Cutoff sind Programm-State und
           bleiben gültig). Eigene Query-Sektionen: mit den Phase-1-Sektionen zu teilen hätte
           deren Messung überschrieben (1 Query-Objekt je Sektion und Slot). */
        this.shader.bind();
        this.textures.bind(0);
        this.bindAtmosphereFog();
        this.bindSkySh();
        this.bindWaterNoise();
        this.bindShadowInputs();
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.baseDepthFunc());
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SOLID_P2);
        this.drawSegmentGpu(RenderLayer.OPAQUE.ordinal(), GpuCull.SEG_OPAQUE, 1);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SOLID_P2);
        if (this.gpuCull.hasLod()) {
            FrameProfiler.gpuBegin(FrameProfiler.Gpu.LOD_P2);
            this.drawLodSegmentGpu(LOD_OPAQUE, GpuCull.SEG_LOD, 1);
            FrameProfiler.gpuEnd(FrameProfiler.Gpu.LOD_P2);
        }
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.CUTOUT_P2);
        this.drawSegmentGpu(RenderLayer.CUTOUT.ordinal(), GpuCull.SEG_CUTOUT, 1);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.CUTOUT_P2);
        GL11.glDepthFunc(properties.baseDepthFunc());
        /* Counts beider Phasen in den Read-Ring kopieren (Fenstertitel + Σ-Telemetrie). */
        this.gpuCull.copyCounts();
        this.shader.unbind();
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

        /* Die Photon-Schattenkarte wird in renderSolid genau einmal vor dem Lighting
           aufgebaut. Sie darf hier nicht erneut mit den inzwischen kamera-sortierten
           Translucent-Daten verändert werden: Terrain, Wasser und volumetrischer Nebel
           müssen innerhalb eines Frames dieselbe Shadow-Map sehen. LOD-Wasser liegt
           außerhalb des 128-Block-Shadowbereichs und gehört daher nicht hinein. */

        /* Gleiches Programm wie renderSolid im selben Frame: u_ProjectionView/Fog/u_Textures
           bleiben als Programm-Uniform-State erhalten (die Entity-/BlockEntity-Pässe dazwischen
           nutzen eigene Programme) — kein Re-Upload nötig. Nur die Textur-Unit neu binden,
           die Entity-Renderer binden dort ihre eigenen Texturen. */
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GLSUB);
        this.shader.bind();
        this.shaderPackManager.applySettings(this.shader);
        this.shader.setUniformMatrix4f("u_InvProjectionView", camera.getInvProjectionViewMatrix());
        this.shader.setUniformVector2f("u_Viewport",
                SkyEngine.get().getWindow().getWidth(), SkyEngine.get().getWindow().getHeight());
        this.shader.setUniformf("u_ZeroToOneDepth",
                SkyEngine.get().getWindow().getProperties().isUseInverseDepth() ? 1F : 0F);
        this.shader.setUniformf("u_CameraUnderwater",
                SkyEngine.get().getPostProcessor().isCameraUnderwater() ? 1F : 0F);
        this.textures.bind(0);
        this.bindAtmosphereFog();
        this.bindSkySh();
        this.bindWaterInputs();

        GL11.glEnable(GL11.GL_BLEND);
        /* Photon composites its translucent color target as a premultiplied layer.
           terrain.frag premultiplies non-water translucents for this draw as well. */
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        this.shader.setUniformf(this.locAlphaCutoff, 0.001F);
        this.shader.setUniformf(this.locTranslucentPass, 1F);
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
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

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
               (Fenstertitel + 1-s-Telemetrie) — reine Lesung aus dem Read-Ring, kein GL-Call. */
            if (this.gpuCull.isActive()) {
                int[] counts = this.gpuCull.readCounts(this.frameSlot);
                this.renderedSections = counts[GpuCull.SEG_OPAQUE]
                        + counts[GpuCull.SEGMENTS + GpuCull.SEG_OPAQUE];
                for (int s = 0; s < 2 * GpuCull.SEGMENTS; s++) {
                    if (!this.gpuDrawStatsValid) {
                        this.gpuDrawMin[s] = counts[s];
                        this.gpuDrawMax[s] = counts[s];
                    } else {
                        if (counts[s] < this.gpuDrawMin[s]) this.gpuDrawMin[s] = counts[s];
                        if (counts[s] > this.gpuDrawMax[s]) this.gpuDrawMax[s] = counts[s];
                    }
                }
                for (int s = 0; s < GpuCull.SEGMENTS; s++) {
                    int sum = counts[s] + counts[GpuCull.SEGMENTS + s];
                    if (!this.gpuDrawStatsValid) {
                        this.gpuSumMin[s] = sum;
                        this.gpuSumMax[s] = sum;
                    } else {
                        if (sum < this.gpuSumMin[s]) this.gpuSumMin[s] = sum;
                        if (sum > this.gpuSumMax[s]) this.gpuSumMax[s] = sum;
                    }
                }
                this.gpuDrawStatsValid = true;
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
    /** Zahl hochgeladener LOD-Regionen — Stabilitätssignal für den Messstand (CullBench). */
    public int getLodRegionCount() {
        return this.lodMeshes.size();
    }

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

    /**
     * GPU-Cull-Telemetrie der letzten Sekunde: min/max der Draw-Counts je Segment und Phase
     * als "P1min..P1max+P2min..P2max" (bei statischer Kamera muss die SUMME P1+P2 konstant
     * sein — Flacker-Nachweis) + Occlusion-Enable-Zähler. null ohne aktiven GPU-Pfad.
     */
    public String gpuCullStatsLineAndReset() {
        String telemetrie = this.gpuCull.telemetrieZeileUndReset();
        if (!this.gpuDrawStatsValid) return telemetrie;
        StringBuilder sb = new StringBuilder("GpuCull-Draws: op ");
        this.appendPhaseRange(sb, GpuCull.SEG_OPAQUE);
        sb.append(" | cut ");
        this.appendPhaseRange(sb, GpuCull.SEG_CUTOUT);
        if (this.gpuDrawMax[GpuCull.SEG_LOD] != 0
                || this.gpuDrawMax[GpuCull.SEGMENTS + GpuCull.SEG_LOD] != 0) {
            sb.append(" | lod ");
            this.appendPhaseRange(sb, GpuCull.SEG_LOD);
        }
        this.gpuDrawStatsValid = false;
        if (telemetrie != null) sb.append(" | ").append(telemetrie);
        sb.append('\n').append(this.gpuCull.descStatsLine());
        return sb.toString();
    }

    /**
     * Draw-Spannweite eines Segments als "ΣSummenSpanne (P1-Spanne+P2-Spanne)". Kriterium
     * bei statischer Kamera: die SUMMEN-Spanne muss ein Punkt sein (Σn..n) — nur das
     * beweist, dass kein Objekt in einem Frame ganz fehlt (Phasen-Wechsel sind erlaubt).
     */
    private void appendPhaseRange(StringBuilder sb, int segment) {
        sb.append('Σ').append(this.gpuSumMin[segment]).append("..").append(this.gpuSumMax[segment])
                .append(" (").append(this.gpuDrawMin[segment]).append("..").append(this.gpuDrawMax[segment])
                .append('+').append(this.gpuDrawMin[GpuCull.SEGMENTS + segment])
                .append("..").append(this.gpuDrawMax[GpuCull.SEGMENTS + segment]).append(')');
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
            Chunk chunk = this.chunkManager.getChunks().get(Chunk.key(result.chunkX(), result.chunkZ()));

            /* Aktualitätsprüfung: die uploadQueue (8/Frame) kann viele Sekunden Rückstand
               haben — ein dort wartendes Erst-Mesh darf ein bereits angewendetes, NEUERES
               Priority-Remesh derselben Section nicht überschreiben (das Dirty-Bit ist dann
               schon konsumiert, die falsche Geometrie bliebe bis zum nächsten Edit stehen). */
            if (chunk != null && !chunk.tryApplyMeshSeq(result.sectionY(), result.meshSeq())) continue;

            /* Upload-Bestätigung für die LOD-Maske: erst wenn alle Sections angewendet sind,
               darf das LOD dort weichen (Chunk kann bei Unload-Race schon fehlen). Zählt nur
               tatsächlich angewendete Batches — verworfene Nachzügler würden den Zähler sonst
               verfrüht sättigen und das LOD risse ein Loch vor dem echten Terrain. */
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
            for (int i = 0, n = this.lodMeshes.tableSize(); i < n; i++) {
                LodMesh mesh = this.lodMeshes.valueAt(i);
                if (mesh == null) continue;
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
            /* .w = Positions-Skala. Zweierpotenz -> in float exakt, die Multiplikation im Shader
               ist eine reine Exponenten-Verschiebung: gleicher Rohwert gibt bitidentische Position. */
            offs.put(oi + 3, 1F / ChunkMesher.POS_SCALE);
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
     * Schreibt die Kleinvegetations-Draws (Detail-Region in der CUTOUT-Arena) der sichtbaren
     * Sections als eigenes Segment — analog {@link #writeSegment}, aber über die
     * Detail-Accessoren der SectionMesh.
     *
     * @return Anzahl geschriebener Draws
     */
    private int writeDetailSegment(List<SectionMesh> list, IntBuffer cmds, FloatBuffer offs,
                                   long cmdSegBytes, long offSegBytes,
                                   Vector3d cam) {
        int cmdBase = (int) (cmdSegBytes / Integer.BYTES);
        int offBase = (int) (offSegBytes / Float.BYTES);
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            SectionMesh mesh = list.get(i);
            if (!mesh.hasDetail()) continue;

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
            offs.put(oi + 3, 1F / ChunkMesher.POS_SCALE);
            n++;
        }
        return n;
    }

    /**
     * GPU-Cull-Variante von {@link #drawSegment}: Commands/Offsets kommen aus dem Compute-Pass
     * (Slot = Descriptor-Index, gecullte Slots = Null-Commands), gezeichnet wird mit normalem
     * glMultiDrawElementsIndirect und CPU-bekannter Draw-Zahl. BEWUSST kein
     * glMultiDrawElementsIndirectCount: dessen Count-Buffer-Lesung stallte die Submission im
     * Treiber bis zum Pipeline-Leerlauf (gemessen 76–114 ms bei Upload-Bursts; Null-Draws
     * sind dagegen fast gratis — Superregionen-Messung). {@code phase} wählt den Command-
     * Bereich des Two-Phase-Cull (0 = Letzte-Frame-Sichtbare, 1 = Nachzügler); die Offsets
     * teilen sich beide Phasen.
     */
    private void drawSegmentGpu(int slot, int segment, int phase) {
        int drawCount = this.gpuCull.drawCount(segment);
        if (drawCount == 0) return;
        GL30.glBindVertexArray(this.vaos[slot]);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.gpuCull.getCommandBuffer());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.gpuCull.getOffsetBuffer(),
                this.gpuCull.offsetOffset(this.frameSlot, segment), this.gpuCull.offsetBytes(segment));
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                this.gpuCull.commandOffset(this.frameSlot, segment, phase), drawCount, 0);
        GL30.glBindVertexArray(0);
    }

    /** GPU-Cull-Variante von {@link #drawLodSegment} (Polygon-Offset wie dort). */
    private void drawLodSegmentGpu(int slot, int segment, int phase) {
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(this.lodOffsetFactor, this.lodOffsetUnits);
        this.drawSegmentGpu(slot, segment, phase);
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
            int stride = ChunkMesher.VERTEX_SIZE * Integer.BYTES;
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_INT, stride, 0);
            GL20.glEnableVertexAttribArray(0);
            /* Attribut 1 = der 5. Int (Licht). Ein 5-komponentiges Attribut gibt es nicht,
               daher ein zweites 1-komponentiges bei Offset 16 — Stride bleibt derselbe. */
            GL30.glVertexAttribIPointer(1, 1, GL11.GL_UNSIGNED_INT, stride, 16);
            GL20.glEnableVertexAttribArray(1);
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
        /* Upload nur bei Änderung — die Werte hängen nur an Settings, nicht an
           der Kamera. Uniform-State bleibt im Programm erhalten. */
        if (fogStart == this.lastFogStart && fogEnd == this.lastFogEnd
                && settings.fog == this.lastFogEnabled) {
            return;
        }
        this.shader.setUniformf(this.locFogStart, fogStart);
        this.shader.setUniformf(this.locFogEnd, fogEnd);
        this.shader.setUniformf(this.locFogEnabled, settings.fog ? 1F : 0F);
        this.lastFogStart = fogStart;
        this.lastFogEnd = fogEnd;
        this.lastFogEnabled = settings.fog;
    }

    /**
     * Setzt die Untergrenze des Himmelslichts aus dem Helligkeits-Setting. Reicht EINMAL pro
     * Frame in {@code renderSolid}: alle Pässe (Cutout, Detail, Translucent, LOD, GPU-Cull-
     * Phase 2) teilen sich dasselbe Shader-Programm, und Uniform-State bleibt darin erhalten.
     */
    private void setLightUniforms() {
        int brightness = GameSettings.get().brightness;
        /* AUS = Fullbright: minLight 1.0 macht das Ergebnis unabhängig von allem anderen exakt
           1.0, der Regler-Wert ist dann egal (auf 0 gesetzt, damit der Cache eindeutig bleibt). */
        boolean fullbright = brightness <= 0;
        float minLight = fullbright ? 1.0F : AMBIENT_LIGHT;
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
        return lightFactor(skyLevel, blockLevel, 1F);
    }

    /** Active pack's low-resolution directional atmosphere, owned by {@link SkyRenderer}. */
    public void setAtmosphereFogTexture(int texture) {
        this.atmosphereFogTexture = texture;
    }

    public void setSkyShBuffer(int buffer) {
        this.skyShBuffer = buffer;
    }

    public void setWaterNoiseTexture(int texture) {
        this.waterNoiseTexture = texture;
    }

    public void setSkyReflectionTexture(int texture) {
        this.skyReflectionTexture = texture;
    }

    /** Photons einzelne verzerrte 2048²-Schattenkarte im 128-Block-Nahbereich. */
    private void prepareWaterShadow(Vector3f sunDirection, Vector3d camera) {
        this.shadowVisible.clear();
        if (this.waterShadowSolidFbo == 0 || this.waterShadowShader == null) return;
        float radius = WATER_SHADOW_DISTANCE;
        this.waterShadowLight.set(sunDirection).normalize().mul(radius);
        this.waterShadowTarget.zero();
        this.waterShadowUp.set(0F, Math.abs(sunDirection.y) > 0.9F ? 0F : 1F,
                Math.abs(sunDirection.y) > 0.9F ? 1F : 0F);
        this.waterShadowProjection.identity()
                /* Photon rasterisiert seine Shadowmap im klassischen OpenGL-Clipraum.
                   Das ist absichtlich unabhaengig vom Reversed-Z/zero-to-one-Clipraum der
                   Hauptkamera: SHADOW_DEPTH_SCALE und alle Photon-Sampler erwarten danach
                   z * 0.5 + 0.5 (belegte Tiefe liegt dadurch um 0.5, nicht bei 0..0.2). */
                .ortho(-radius, radius, -radius, radius, 0.1F, radius * 2F, false);
        this.waterShadowView.identity()
                .lookAt(this.waterShadowLight, this.waterShadowTarget, this.waterShadowUp);

        /* Eine frei mit der Kamera gleitende Orthoprojektion verschiebt das komplette
           Shadow-Raster bei jeder noch so kleinen Bewegung und laesst PCSS auf ruhenden
           Flaechen kriseln. Die beiden lichtseitigen Achsen werden deshalb auf ganze
           Shadow-Texel eingerastet. Das entspricht der Stabilisierung, die Minecraft/Iris
           fuer Photons shadowModelView bereitstellt; die Geometrie bleibt kamera-relativ,
           die Abtastphase ist aber in der Welt verankert. */
        double cameraLightX = this.waterShadowView.m00() * camera.x
                + this.waterShadowView.m10() * camera.y
                + this.waterShadowView.m20() * camera.z;
        double cameraLightY = this.waterShadowView.m01() * camera.x
                + this.waterShadowView.m11() * camera.y
                + this.waterShadowView.m21() * camera.z;
        double worldUnitsPerTexel = 2.0 * radius / this.waterShadowSize;
        float phaseX = (float) (cameraLightX
                - Math.rint(cameraLightX / worldUnitsPerTexel) * worldUnitsPerTexel);
        float phaseY = (float) (cameraLightY
                - Math.rint(cameraLightY / worldUnitsPerTexel) * worldUnitsPerTexel);
        this.waterShadowView.m30(phaseX);
        this.waterShadowView.m31(phaseY);
        this.waterShadowProjection.mul(this.waterShadowView, this.waterShadowMatrix);

        for (int i = 0, n = this.meshes.tableSize(); i < n; i++) {
            SectionMesh mesh = this.meshes.valueAt(i);
            if (mesh == null) continue;
            float half = ChunkSection.SIZE * 0.5F;
            float cx = offsetX(mesh, camera) + half;
            float cy = offsetY(mesh, camera) + half;
            float cz = offsetZ(mesh, camera) + half;
            if (this.waterShadowIntersects(cx, cy, cz, half)) this.shadowVisible.add(mesh);
        }
    }

    /** AABB/Clip-Test fuer Photons klassische orthografische [-1,1]-Schattenprojektion. */
    private boolean waterShadowIntersects(float x, float y, float z, float halfExtent) {
        Matrix4f m = this.waterShadowMatrix;
        float cx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
        float cy = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
        float cz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
        float ex = halfExtent * (Math.abs(m.m00()) + Math.abs(m.m10()) + Math.abs(m.m20()));
        float ey = halfExtent * (Math.abs(m.m01()) + Math.abs(m.m11()) + Math.abs(m.m21()));
        float ez = halfExtent * (Math.abs(m.m02()) + Math.abs(m.m12()) + Math.abs(m.m22()));
        float margin = 0.08F;
        return cx + ex >= -1F - margin && cx - ex <= 1F + margin
                && cy + ey >= -1F - margin && cy - ey <= 1F + margin
                && cz + ez >= -1F - margin && cz - ez <= 1F + margin;
    }

    /** Zeichnet die zuvor vorbereitete, kameraunabhaengige Photon-Schattenkarte. */
    public void renderWaterOcclusion() {
        if (this.waterShadowSolidFbo == 0 || this.waterShadowShader == null) return;

        var window = SkyEngine.get().getWindow();
        this.usePhotonShadowClipSpace();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.waterShadowSolidFbo);
        GL11.glViewport(0, 0, this.waterShadowSize, this.waterShadowSize);
        /* Der Shadow-Clear muss immer die komplette Karte erfassen. Ein GUI-Scissor aus
           dem vorherigen Frame wuerde sonst exakt positionsabhaengige, stehenbleibende
           Schattenstreifen erzeugen. */
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glClearDepth(1.0);
        GL11.glColorMask(true, true, true, true);
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{1F, 1F, 1F, 1F});
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        this.waterShadowShader.bind();
        this.shaderPackManager.applySettings(this.waterShadowShader);
        this.waterShadowShader.setUniformMatrix4f("u_LightProjectionView", this.waterShadowMatrix);
        this.waterShadowShader.setUniformf("u_WaterOnly", 0F);
        this.textures.bind(0);
        this.drawSegment(RenderLayer.OPAQUE.ordinal(), this.shadowCmdOpaque,
                this.shadowOffOpaque, this.shadowNOpaque);
        this.drawSegment(RenderLayer.CUTOUT.ordinal(), this.shadowCmdCutout,
                this.shadowOffCutout, this.shadowNCutout);
        this.drawSegment(RenderLayer.CUTOUT.ordinal(), this.shadowCmdDetail,
                this.shadowOffDetail, this.shadowNDetail);
        this.waterShadowShader.unbind();

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.waterShadowSolidFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.waterShadowAllFbo);
        GL30.glBlitFramebuffer(0, 0, this.waterShadowSize, this.waterShadowSize,
                0, 0, this.waterShadowSize, this.waterShadowSize,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.waterShadowAllFbo);
        /* shadowColor wurde im Solid-Pass bereits wie bei Photon beschrieben: Hintergrund
           weiss, opake Blocker schwarz. Nicht erneut loeschen, sonst werden PCF-Raender
           durch das spaetere *4 faelschlich selbstleuchtend. */
        GL11.glColorMask(true, true, true, true);
        window.getFrameBuffer().bind();
        this.restoreMainClipSpace();
        GL11.glViewport(0, 0, window.getWidth(), window.getHeight());
        GL11.glDepthFunc(window.getProperties().baseDepthFunc());
        GL11.glClearDepth(window.getProperties().isUseInverseDepth() ? 0.0 : 1.0);
    }

    private void appendWaterToShadow(long command, long offset, int count,
                                     long lodCommand, long lodOffset, int lodCount) {
        if (this.waterShadowAllFbo == 0 || this.waterShadowShader == null) return;
        var window = SkyEngine.get().getWindow();
        this.usePhotonShadowClipSpace();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.waterShadowAllFbo);
        GL11.glViewport(0, 0, this.waterShadowSize, this.waterShadowSize);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glColorMask(true, true, true, true);
        this.waterShadowShader.bind();
        this.shaderPackManager.applySettings(this.waterShadowShader);
        this.waterShadowShader.setUniformMatrix4f("u_LightProjectionView", this.waterShadowMatrix);
        this.waterShadowShader.setUniformf("u_WaterOnly", 1F);
        this.textures.bind(0);
        this.drawSegment(RenderLayer.TRANSLUCENT.ordinal(), command, offset, count);
        this.drawLodSegment(LOD_TRANSLUCENT, lodCommand, lodOffset, lodCount);
        this.waterShadowShader.unbind();
        GL11.glColorMask(true, true, true, true);
        window.getFrameBuffer().bind();
        this.restoreMainClipSpace();
        GL11.glViewport(0, 0, window.getWidth(), window.getHeight());
        GL11.glDepthFunc(window.getProperties().baseDepthFunc());
        GL11.glClearDepth(window.getProperties().isUseInverseDepth() ? 0.0 : 1.0);
    }

    /** Photons Shadow-Shader und PCSS-Code arbeiten im normalen OpenGL-NDC-Z-Bereich. */
    private void usePhotonShadowClipSpace() {
        if (SkyEngine.get().getWindow().getProperties().isUseInverseDepth()) {
            ARBClipControl.glClipControl(GL20.GL_LOWER_LEFT,
                    ARBClipControl.GL_NEGATIVE_ONE_TO_ONE);
        }
    }

    /** Stellt den fuer die Hauptkamera einmalig konfigurierten Reversed-Z-Clipraum wieder her. */
    private void restoreMainClipSpace() {
        if (SkyEngine.get().getWindow().getProperties().isUseInverseDepth()) {
            ARBClipControl.glClipControl(GL20.GL_LOWER_LEFT, ARBClipControl.GL_ZERO_TO_ONE);
        }
    }

    public int waterShadowDepthAll() {
        return this.waterShadowDepthAll;
    }

    public int waterShadowDepthSolid() {
        return this.waterShadowDepthSolid;
    }

    public int waterShadowColor() {
        return this.waterShadowColor;
    }

    public Matrix4f waterShadowMatrix() {
        return this.waterShadowMatrix;
    }

    public Matrix4f waterShadowView() {
        return this.waterShadowView;
    }

    private void createWaterShadowTarget() {
        this.waterShadowDepthAll = this.createWaterShadowDepth("Photon shadowtex0");
        this.waterShadowDepthSolid = this.createWaterShadowDepth("Photon shadowtex1");
        this.waterShadowColor = this.createWaterShadowColor();
        this.waterShadowAllFbo = this.createWaterShadowFbo(
                this.waterShadowDepthAll, this.waterShadowColor);
        /* Dieselbe Color-Textur darf an beiden FBOs haengen. So schreibt der Solid-Pass
           opake Transmission (schwarz) einmal zusammen mit shadowtex1; anschliessend wird
           nur die Tiefe nach shadowtex0 kopiert und Wasser dort ergaenzt. */
        this.waterShadowSolidFbo = this.createWaterShadowFbo(
                this.waterShadowDepthSolid, this.waterShadowColor);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void disposeWaterShadowTarget() {
        if (this.waterShadowAllFbo != 0) GL30.glDeleteFramebuffers(this.waterShadowAllFbo);
        if (this.waterShadowSolidFbo != 0) GL30.glDeleteFramebuffers(this.waterShadowSolidFbo);
        if (this.waterShadowDepthAll != 0) GL11.glDeleteTextures(this.waterShadowDepthAll);
        if (this.waterShadowDepthSolid != 0) GL11.glDeleteTextures(this.waterShadowDepthSolid);
        if (this.waterShadowColor != 0) GL11.glDeleteTextures(this.waterShadowColor);
        this.waterShadowAllFbo = 0;
        this.waterShadowSolidFbo = 0;
        this.waterShadowDepthAll = 0;
        this.waterShadowDepthSolid = 0;
        this.waterShadowColor = 0;
    }

    private int createWaterShadowDepth(String label) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                this.waterShadowSize, this.waterShadowSize, 0, GL11.GL_DEPTH_COMPONENT,
                GL11.GL_FLOAT, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlDebug.labelTexture(texture, label);
        return texture;
    }

    private int createWaterShadowColor() {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                this.waterShadowSize, this.waterShadowSize, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlDebug.labelTexture(texture, "Photon shadowcolor0");
        return texture;
    }

    private int createWaterShadowFbo(int depthTexture, int colorTexture) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);
        if (colorTexture != 0) {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTexture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        } else {
            GL11.glDrawBuffer(GL11.GL_NONE);
        }
        GL11.glReadBuffer(GL11.GL_NONE);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Photon-Schattenkarte ist unvollständig");
        }
        return fbo;
    }

    private void bindAtmosphereFog() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATMOSPHERE_FOG_UNIT);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, this.atmosphereFogTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void bindSkySh() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKY_SH_BINDING,
                this.skyShBuffer);
    }

    private void bindWaterInputs() {
        var frameBuffer = SkyEngine.get().getWindow().getFrameBuffer();
        this.bindWaterNoise();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + OPAQUE_COLOR_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, frameBuffer.getOpaqueColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + OPAQUE_DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, frameBuffer.getOpaqueDepthTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SKY_REFLECTION_UNIT);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, this.skyReflectionTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void bindWaterNoise() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + WATER_NOISE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterNoiseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void bindShadowInputs() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_DEPTH_SOLID_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterShadowDepthSolid);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_DEPTH_ALL_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterShadowDepthAll);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_COLOR_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterShadowColor);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public static float lightFactor(int skyLevel, int blockLevel, float skyIntensity) {
        int brightness = GameSettings.get().brightness;
        if (brightness <= 0) return 1.0F; // Fullbright
        float sky = Math.clamp(skyLevel, 0, 15) / 15.0F * Math.clamp(skyIntensity, 0F, 1F);
        float block = Math.clamp(blockLevel, 0, 15) / 15.0F;
        float f = Math.max(sky, block);
        float light = f / (4.0F - 3.0F * f);
        light = AMBIENT_LIGHT + (1.0F - AMBIENT_LIGHT) * light;
        float inv = 1.0F - light;
        float inv2 = inv * inv;
        float lifted = 1.0F - inv2 * inv2;
        return light + (lifted - light) * (brightness / 100F); // = mix(light, lifted, brightness)
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
        if (mesh.hasLayer(RenderLayer.TRANSLUCENT)) {
            mesh.translucentIdx = this.translucentMeshes.size();
            this.translucentMeshes.add(mesh);
        }
        if (mesh.hasDetail()) {
            mesh.detailIdx = this.detailMeshes.size();
            this.detailMeshes.add(mesh);
        }
    }

    /* Swap-Remove über den im Mesh gespeicherten Index statt ArrayList.remove(Object):
       der Referenz-Scan war O(Listengröße) pro Entfernung. Der Index hängt NICHT an
       hasLayer/hasDetail (dispose() nullt die Regionen vor dem Unregister!), sondern nur
       an der tatsächlichen Listen-Mitgliedschaft — Semantik wie das frühere ungeguardete
       remove: enthalten → raus, sonst No-op. Die Listen sind reihenfolge-unabhängig
       (Translucent wird pro Frame neu distanz-sortiert, Detail pro Frame neu gecullt). */
    private void translucentListRemove(SectionMesh mesh) {
        int idx = mesh.translucentIdx;
        if (idx < 0) return;
        mesh.translucentIdx = -1;
        SectionMesh last = this.translucentMeshes.remove(this.translucentMeshes.size() - 1);
        if (last != mesh) {
            this.translucentMeshes.set(idx, last);
            last.translucentIdx = idx;
        }
    }

    private void detailListRemove(SectionMesh mesh) {
        int idx = mesh.detailIdx;
        if (idx < 0) return;
        mesh.detailIdx = -1;
        SectionMesh last = this.detailMeshes.remove(this.detailMeshes.size() - 1);
        if (last != mesh) {
            this.detailMeshes.set(idx, last);
            last.detailIdx = idx;
        }
    }

    private void unregisterSectionMesh(SectionMesh mesh) {
        if (mesh.gpuSlotOpaque >= 0) this.gpuCull.removeSection(GpuCull.SEG_OPAQUE, mesh.gpuSlotOpaque);
        if (mesh.gpuSlotCutout >= 0) this.gpuCull.removeSection(GpuCull.SEG_CUTOUT, mesh.gpuSlotCutout);
        mesh.gpuSlotOpaque = -1;
        mesh.gpuSlotCutout = -1;
        /* IMMER entfernen (nicht über hasLayer/hasDetail gaten): dispose() läuft an beiden
           Aufrufstellen VOR dem Unregister und nullt die Regionen — die Flags wären dann schon
           false und das tote Mesh bliebe für immer in den Listen. Der GPU-Cull-Pfad iteriert
           diese Listen direkt und crashte damit beim Block-Edit (baseVertexDetail auf
           null-Region). Die *ListRemove-Helfer gaten nur auf Listen-Mitgliedschaft (Idx). */
        this.translucentListRemove(mesh);
        this.detailListRemove(mesh);
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
    /** Alle Spalten-Gates neu aus dem LOD-Zustand setzen (nach dem Einschalten des GPU-Pfads). */
    private void refreshAllGates() {
        for (int ci = 0, cn = this.cullColumns.tableSize(); ci < cn; ci++) {
            CullColumn col = this.cullColumns.valueAt(ci);
            if (col == null || col.gateSlot < 0) continue;
            this.gpuCull.setGate(col.gateSlot,
                    this.lodManager != null && this.lodManager.lodShowsCell(col.chunkX, col.chunkZ));
        }
    }

    private void refreshGateForRegion(int rx, int rz, int sizeRegions) {
        /* Das Sicht-Gate ist reine GPU-Pfad-Infrastruktur — der CPU-Pfad fragt lodShowsCell
           direkt ab (s. renderSolid). Bei ausgeschaltetem GPU-Cull ist die Schleife also
           komplett umsonst; beim LOD-Umschalten waren das ~54.000 Hash-Lookups in EINEM Frame. */
        if (!this.gpuCull.isActive()) return;
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
        long key = Chunk.key(mesh.chunkX, mesh.chunkZ);
        CullColumn col = this.cullColumns.get(key);
        if (col == null) {
            col = new CullColumn(mesh.chunkX, mesh.chunkZ);
            this.cullColumns.put(key, col);
        }
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
        long tileKey = LodManager.key(mesh.rx >> LOD_TILE_SHIFT, mesh.rz >> LOD_TILE_SHIFT);
        LodTile tile = this.lodTiles.get(tileKey);
        if (tile == null) {
            tile = new LodTile(mesh.rx >> LOD_TILE_SHIFT, mesh.rz >> LOD_TILE_SHIFT);
            this.lodTiles.put(tileKey, tile);
        }
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

    /** Section-Key im geteilten 26/26/12-Layout ({@code BlockPos.asLong}; y = sectionY 0..15). */
    private static long sectionKey(int x, int y, int z) {
        return de.skyengine.game.world.block.BlockPos.asLong(x, y, z);
    }

    public void dispose() {
        if (this.shaderPackManager != null) this.shaderPackManager.unregister(this);
        for (int i = 0, n = this.meshes.tableSize(); i < n; i++) {
            SectionMesh mesh = this.meshes.valueAt(i);
            if (mesh != null) mesh.dispose(this.arenas, this.frameId);
        }
        this.meshes.clear();
        this.cullColumns.clear();
        for (int i = 0, n = this.lodMeshes.tableSize(); i < n; i++) {
            LodMesh mesh = this.lodMeshes.valueAt(i);
            if (mesh != null) mesh.dispose(this.arenas[LOD_OPAQUE], this.arenas[LOD_TRANSLUCENT], this.frameId);
        }
        this.lodMeshes.clear();
        this.lodTiles.clear();
        this.lodOversize.clear();
        this.translucentMeshes.clear();
        this.lodTranslucentMeshes.clear();
        this.detailMeshes.clear();
        this.gpuCull.dispose();
        for (long fence : this.fences) {
            if (fence != 0L) GL32.glDeleteSync(fence);
        }
        for (int vao : this.vaos) GL30.glDeleteVertexArrays(vao);
        if (this.sharedEbo != 0) GL15.glDeleteBuffers(this.sharedEbo);
        this.disposeWaterShadowTarget();
        if (this.commandRing != null) this.commandRing.dispose();
        if (this.offsetRing != null) this.offsetRing.dispose();
        for (VertexArena arena : this.arenas) {
            if (arena != null) arena.dispose();
        }
        /* Atlas (textures/animations) NICHT disposen — gehört dem GameContainer und
           überlebt Welt-Austritte (Hauptmenü braucht ihn für Item-Icons). */
        if (this.shader != null) this.shader.dispose();
        if (this.waterShadowShader != null) this.waterShadowShader.dispose();
    }

    private static final class PreparedTerrain implements ShaderPackManager.Prepared {
        private ShaderProgram terrain;
        private ShaderProgram shadow;
        private final int shadowSize;

        private PreparedTerrain(ShaderProgram terrain, ShaderProgram shadow, int shadowSize) {
            this.terrain = terrain;
            this.shadow = shadow;
            this.shadowSize = shadowSize;
        }

        private int shadowSize() {
            return this.shadowSize;
        }

        private ShaderProgram takeTerrain() {
            ShaderProgram result = this.terrain;
            this.terrain = null;
            return result;
        }

        private ShaderProgram takeShadow() {
            ShaderProgram result = this.shadow;
            this.shadow = null;
            return result;
        }

        @Override
        public void dispose() {
            if (this.terrain != null) this.terrain.dispose();
            if (this.shadow != null) this.shadow.dispose();
        }
    }

    /* Gepacktes Vertex-Format (20 Bytes, siehe ChunkMesher.VERTEX_SIZE):
       x: posX | posY<<16 (u16 fixed 6.10, Bias +1) — y: posZ | u<<16 (uv fixed 6.10, Bias +1)
       z: v | layer<<16 — w: rgb8
       (5. Int reserviert für farbiges Licht, vom Shader aktuell ungenutzt — Stride wächst
       automatisch über ChunkMesher.VERTEX_SIZE, a_data liest weiterhin nur die ersten 4 Ints)
       Section-Origin (kamerarelativ) kommt pro Draw aus dem SSBO, indiziert via gl_DrawID. */
    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in uvec4 a_data;
            /* 5. Int des Vertex-Formats: Skylight 0..15 in Bits 0-3 (Rest reserviert). */
            layout(location = 1) in uint a_light;

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
            out vec3 v_viewDirection;

            void main() {
                /* Positions-Skala pro Draw aus .w (Sections + normales LOD: 1/256; LOD-
                   Superregionen: 1/64 -- u16-Fixed-Point traegt sonst nur ~255 Bloecke). */
                vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16), float(a_data.y & 0xFFFFu)) * u_DrawOffsets[gl_DrawID].w - 1.0;
                vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu)) * (1.0 / 1024.0) - 1.0;
                float layer = float(a_data.z >> 16);
                vec3 color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu), float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);

                v_texCoord = vec3(uv, layer);
                v_color = color;
                /* Himmels- und Blocklicht je 0..1, interpoliert -> weiche Verlaeufe (Smooth
                   Lighting). Muss VOR dem Ausduennungs-Block stehen, der mit return aussteigt. */
                v_light = vec2(float(a_light & 0xFu), float((a_light >> 4) & 0xFu)) * (1.0 / 15.0);
                /* Occlusion-Debug (GpuCull.DEBUG_TINT): der Compute markiert Verdeckt-Verdikte
                   ueber baseInstance=1 statt sie zu cullen -> rot tinten. */
                if (gl_BaseInstance != 0) {
                    v_color = mix(v_color, vec3(1.0, 0.1, 0.1), 0.7);
                }
                /* Positionen sind kamerarelativ und welt-achsen-ausgerichtet -> length(rel.xz) =
                   horizontale Sichtdistanz fuer ZYLINDRISCHEN Fog (wie MC 1.18+): Hochfliegen
                   schiebt das Terrain unter dem Spieler nicht in den Nebel, die horizontale
                   Ladekante bleibt verdeckt. Rotationsinvariant, kein "Atmen" beim Umschauen. */
                vec3 rel = pos + u_DrawOffsets[gl_DrawID].xyz;
                v_viewDist = length(rel.xz);
                v_viewDirection = normalize(rel);

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
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            """ + de.skyengine.graphics.shader.ShaderColorSpace.GLSL + """
            in vec3 v_texCoord;
            in vec3 v_color;
            in vec2 v_light;    // x = Himmelslicht, y = Blocklicht (je 0..1)
            in float v_viewDist;
            in vec3 v_viewDirection;

            uniform sampler2DArray u_Textures;
            uniform samplerCube u_AtmosphereFog;
            uniform float u_AlphaCutoff;
            uniform float u_FogStart;
            uniform float u_FogEnd;
            uniform float u_FogEnabled;
            uniform float u_TranslucentPass;
            layout(std140, binding = 1) uniform Environment {
                vec4 u_SunDirection;
                vec4 u_MoonDirection;
                vec4 u_SkyLightColor;
                vec4 u_EnvFogColor;
                vec4 u_SkyTint;
            };
            /* Grundhelligkeit bei Lichtlevel 0. 1.0 = Fullbright: dann ist das Ergebnis fuer
               JEDES v_light exakt 1.0, also bit-identisch zum Bild ohne Lichtsystem — deshalb
               braucht Fullbright weder Shader-Zweig noch Remesh. */
            uniform float u_MinLight;
            /* Helligkeitsregler 0..1 (Minecraft-Brightness). */
            uniform float u_Brightness;

            out vec4 fragColor;

            /* Minecraft-Helligkeitskurve (lightBrightnessTable): staucht mittlere Licht-Level
               nach unten. Linear waere der Abfall fast ueberall zu grell. */
            float lightCurve(float f) { return f / (4.0 - 3.0 * f); }

            void main() {
                vec4 color = texture(u_Textures, v_texCoord);
                if (color.a < u_AlphaCutoff) discard;
                /* Monochrom wie in Minecraft: der hellere der beiden Werte gewinnt. Erst DANACH
                   die Kurve — die Reihenfolge darunter bleibt unangetastet. */
                float skyLight = v_light.x * u_SunDirection.w;
                float blockLight = v_light.y;
                float light = lightCurve(clamp(max(skyLight, blockLight), 0.0, 1.0));
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
                float skyDominance = smoothstep(blockLight, blockLight + 0.05, skyLight);
                vec3 lightTint = mix(vec3(1.0), u_SkyLightColor.rgb,
                        skyDominance * (1.0 - u_MinLight));
                vec3 lit = seSrgbToWorking(color.rgb) * clamp(v_color, 0.0, 1.0) * light * lightTint;
                /* Linearer Distanz-Fog Richtung Clear-Color: nimmt dem Horizont den Kontrast
                   (Sub-Pixel-Flimmern des Fernterrains) und versteckt die Far-Plane-Kante. */
                float edgeFog = clamp((v_viewDist - u_FogStart) / (u_FogEnd - u_FogStart), 0.0, 1.0);
                /* Aerial perspective is a distance effect, not a full-screen wash. Keep the
                   foreground and most of the mid-range untouched, then introduce the pack's
                   atmospheric colour gradually before the edge-fog hides the load boundary. */
                float aerialOnset = smoothstep(u_FogEnd * 0.20, u_FogEnd * 0.65, v_viewDist);
                float aerialFog = (1.0 - exp(-u_EnvFogColor.w * v_viewDist))
                        * aerialOnset * u_FogEnabled;
                float fog = 1.0 - (1.0 - edgeFog) * (1.0 - aerialFog);
                vec3 directionalFog = texture(u_AtmosphereFog, normalize(v_viewDirection)).rgb;
                /* Exact same atmosphere as the active sky pack. The neutral environment fog
                   remains a safety fallback while a pack is loading or returns black. */
                float atmosphereEnergy = dot(directionalFog, vec3(0.2627, 0.6780, 0.0593));
                vec3 fogColor = mix(u_EnvFogColor.rgb, directionalFog,
                        smoothstep(0.0001, 0.002, atmosphereEnergy));
                /* Alpha carries a free material marker through the HDR scene. Opaque/cutout
                   terrain is graded like the old master renderer near the camera and fades
                   back to the pack transform together with atmospheric fog. Translucent
                   geometry must retain its real alpha for blending. */
                float materialMarker = mix(1.0 - fog, color.a, u_TranslucentPass);
                fragColor = vec4(mix(lit, fogColor, fog), materialMarker);
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
