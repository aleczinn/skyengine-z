package de.skyengine.game.world.chunk;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.state.BlockState;

import java.util.Arrays;

public class ChunkMesher {

    /**
     * Ints pro Vertex (gepacktes Format, 20 Bytes statt 36):
     * <pre>
     * int0: posX | posY &lt;&lt; 16     (u16 fixed-point 6.10, Bias +1 Block, section-lokal)
     * int1: posZ | u    &lt;&lt; 16     (u als u16 fixed-point 6.10, Bias +1)
     * int2: v    | layer &lt;&lt; 16    (v wie u; layer = Texture-Array-Layer)
     * int3: r | g &lt;&lt; 8 | b &lt;&lt; 16  (Farbe = Helligkeit * AO * Tint, je u8)
     * int4: gemitteltes Skylight 0..255 in Bits 0-7, Blocklicht 0..255 in Bits 8-15,
     *       {@link #FLAT_SOURCE_FLUID_TOP} in Bit 16,
     *       Bits 17-31 reserviert; liest der Vertex-Shader als eigenes Attribut 1
     * </pre>
     * Entpackt wird im Vertex-Shader des ChunkRenderers. Ein Quad = 4 Vertices (A,B,C,D),
     * Triangulierung über den geteilten Index-Buffer (0,1,2, 2,3,0).
     */
    public static final int VERTEX_SIZE = 5;

    /**
     * Vertex-Flag für horizontal gerenderte Quell-Fluid-Tops. Der Shader rekonstruiert deren
     * Nachkommastelle analytisch, damit Section-, LOD- und Superregion-Packung dieselbe Ebene
     * ergeben statt an den unterschiedlichen Fixed-Point-Skalen feine Risse zu erzeugen.
     */
    public static final int FLAT_SOURCE_FLUID_TOP = 1 << VertexLight.FIRST_FLAG_BIT;

    /**
     * Bias/Skalierung des Positions-Fixed-Points (1/1024 Block; Bias fängt Offsets bis -1 ab).
     *
     * <p>6.10 statt 8.8: Eine Section braucht nur den Bereich −1…33, also 6 Integer-Bits — die
     * restlichen 10 gehen in die Nachkommastellen. Das kostet KEIN Bit: das Vertex-Layout bleibt
     * unverändert, nur die Interpretation ändert sich. Nötig, weil MC-Modelle koplanare Flächen mit
     * winzigen Offsets trennen; bei 1/256 war der kleinste darstellbare Versatz (1/16 px) selbst
     * schon sichtbar. Größter section-lokaler Wert: (32 + MAX_OFFSET + 1) × 1024 = 35072 von 65535.
     *
     * <p>Das volumetrische LOD verwendet ein separates gepacktes Quadformat.
     */
    public static final float POS_SCALE = 1024F;
    /** Skalierung des UV-Fixed-Points (1/1024; reicht für Greedy-UVs bis 32+). */
    public static final float UV_SCALE = 1024F;

    /* Face-Indizes: 0=top, 1=bottom, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */
    private static final int[][] FACE_OFFSET = {
            { 0,  1,  0}, { 0, -1,  0},
            { 0,  0, -1}, { 0,  0,  1},
            {-1,  0,  0}, { 1,  0,  0}
    };

    /** Maximaler horizontaler Versatz für Cross-Blöcke (wie Minecraft: +/- 0.25). */
    private static final float MAX_OFFSET = 0.25F;

    /**
     * Mesh-Ergebnis einer Section, getrennt nach RenderLayer. Arrays sind null wenn leer.
     * {@code detail} = Kleinvegetation (Cross-Blöcke mit Random-Offset: Gras, Blumen, Pilze,
     * Setzlinge) — landet in der CUTOUT-Arena, aber als eigene Region/eigenes Draw-Segment,
     * damit der Renderer sie distanzabhängig ausdünnen/skippen kann.
     */
    public static final class MeshData {
        public final int[] opaque;
        public final int[] cutout;
        public final int[] translucent;
        public final int[] detail;
        /* Tatsaechlich emittierte Rand-Faces dieser Section. Layout:
           (face - NORTH) * 32 + Tangentialkoordinate, Bits = lokales Y 0..31.
           Das LOD-Stitching verwendet damit exakt dieselbe Ownership wie das sichtbare
           L0-Mesh statt die Cull-Entscheidung spaeter aus Blockdaten zu erraten. */
        private final int[] boundaryFaces;

        MeshData(int[] opaque, int[] cutout, int[] translucent, int[] detail,
                 int[] boundaryFaces) {
            this.opaque = opaque;
            this.cutout = cutout;
            this.translucent = translucent;
            this.detail = detail;
            this.boundaryFaces = boundaryFaces;
        }

        public boolean isEmpty() {
            return this.opaque == null && this.cutout == null && this.translucent == null
                    && this.detail == null;
        }

        /** Unveraenderlicher Snapshot der vier horizontalen Section-Raender. */
        public int[] boundaryFaces() {
            return this.boundaryFaces;
        }

        public boolean rendersBoundaryFace(int face, int tangent, int localY) {
            if (face < 2 || face > 5 || tangent < 0 || tangent >= ChunkSection.SIZE
                    || localY < 0 || localY >= ChunkSection.SIZE) return false;
            int index = (face - 2) * ChunkSection.SIZE + tangent;
            return (this.boundaryFaces[index] & (1 << localY)) != 0;
        }
    }

    /* Ein wiederverwendeter Buffer pro RenderLayer (Index = RenderLayer.ordinal()) + 1 für
       das Kleinvegetations-Segment (Index DETAIL_BUFFER). */
    private static final int DETAIL_BUFFER = RenderLayer.VALUES.length;
    private final VertexBuffer[] buffers = {new VertexBuffer(), new VertexBuffer(), new VertexBuffer(), new VertexBuffer()};

    /* Kontext des laufenden mesh()-Aufrufs (für AO-Sampling über Chunk-Grenzen).
       Mesher ist ThreadLocal -> keine Nebenläufigkeit; wird am Ende genullt (kein Chunk-Leak). */
    private Chunk chunk, north, south, west, east;
    private Chunk[] diagonals;

    /* Wiederverwendeter AO-Puffer (4 Eckwerte des aktuellen Quads) */
    private final float[] aoCorners = new float[4];
    /* Wiederverwendeter Licht-Puffer (4 gepackte Eckwerte des aktuellen Quads, s. computeCornerLight) */
    private final int[] lightCorners = new int[4];
    /* AO an den 4 Ecken der EINHEITS-Face, Index = v << 1 | u (u/v = Tangenten-Vorzeichen 0/1).
       Wird bilinear auf die echten Quad-Ecken interpoliert (Teilblöcke), s. computeAo. */
    private final float[] aoUnit = new float[4];

    /** Toleranz für „Quad-Ebene liegt bündig auf der Blockgrenze" (wie Minecraft). */
    private static final float FLUSH_EPS = 1.0E-4F;

    /* Ambient-Occlusion-Setting, einmal pro mesh()-Aufruf gelesen (konsistent pro Section) */
    private boolean ambientOcclusion = true;

    /* LeavesQuality LOW: Laub-Faces gegen JEDES Nachbar-Laub cullen (auch fremde Laub-Arten —
       MC-„Schnelle Grafik"). Einmal pro mesh()-Aufruf gelesen, konsistent pro Section. */
    private boolean cullLeaves = false;

    /* Pflanzen-Hash (0..255) der gerade emittierten Kleinvegetation: wandert in die oberen
       8 Bit von int3 (dort ungenutzt; int4 bleibt fürs Lichtsystem reserviert). Der Shader
       dünnt damit ferne Pflanzen deterministisch aus. 0 = kein Detail-Quad (alle anderen
       Pfade schreiben weiter bit-identische Vertices). */
    private int plantHash = 0;

    /* Eindeutige Ecken A,B,C,D im 6-Vertex-Quad der BakedQuads (A,B,C,C,D,A) */
    private static final int[] UNIQUE_VERTS = {0, 1, 2, 4};
    /* Emissions-Reihenfolge der 4 Ecken: normal = Diagonale A-C, geflippt = B-D (AO-Anisotropie-Fix;
       der Index-Buffer trianguliert immer 0,1,2 / 2,3,0 über die emittierte Reihenfolge) */
    private static final int[] EMIT_NORMAL = {0, 1, 2, 3};
    private static final int[] EMIT_FLIPPED = {1, 2, 3, 0};

    /* Achsen je Face (0=x, 1=y, 2=z): N = Normale, T1/T2 = Tangenten der Face-Ebene.
       Face-Reihenfolge wie FACE_OFFSET: top, bottom, north, south, west, east. */
    private static final int[] AXIS_N = {1, 1, 2, 2, 0, 0};
    private static final int[] AXIS_T1 = {0, 0, 0, 0, 1, 1};
    private static final int[] AXIS_T2 = {2, 2, 1, 1, 2, 2};

    /* Halo-Snapshot der Section (34³ = Section + 1-Zellen-Rand): deckt exakt die
       Sampling-Reichweite von Cull, AO und Corner-Licht ab (jede Abfrage liegt ±1 um eine
       Section-Zelle). Befüllt wird der Rand über den NeighborSampler — die geteilte
       Auflösungs-Konvention (FluidGeometry!) bleibt Single Source of Truth. Ersetzt bis zu
       28 Paletten-Reads pro Quad durch Array-Reads; die Opazität wird beim Befüllen gleich
       mitberechnet. Licht wird LAZY memoisiert (Sentinel -1): der erste Zugriff ist exakt
       der bisherige NeighborSampler-Aufruf, Wiederholungen (benachbarte Quads teilen sich
       die Ecksamples) sind Array-Reads — leere Sections zahlen so keinen Vorab-Preis. */
    private static final int HALO = ChunkSection.SIZE + 2; // 34
    private final int[] haloBlocks = new int[HALO * HALO * HALO];
    /* AO-/Ecklicht-Verschattung (AO_OCCLUDER-Flag, i.d.R. = opak) — NUR von occludes()
       gelesen; Culling und Greedy fragen weiterhin isOpaqueCube. */
    private final boolean[] haloOccluder = new boolean[HALO * HALO * HALO];
    private final int[] haloLight = new int[HALO * HALO * HALO];
    /* Basis-Welt-y der aktuellen Section (sample/occludes rechnen globales y → halo-lokal). */
    private int sectionBaseY;

    /* occludesAo je State-ID (lazy wie greedyCache): erspart occludes() den Umweg über
       das BlockState-Objekt (volatile statesById-Load + Pointer-Chase im heißesten Loop). */
    private boolean[] occluderCache = new boolean[0];
    /* Merge-Grid einer Slice: 0 = leer/schon emittiert, sonst Merge-Schlüssel */
    private final long[] keyGrid = new long[ChunkSection.SIZE * ChunkSection.SIZE];
    /* Markiert Fluid-Zellen, deren flach-stilles Top-Face der gemergte Wasser-Pass schon
       emittiert hat -> Pass 2 (FluidGeometry.build) lässt dort den Top aus. Pro mesh() neu. */
    private final boolean[] mergedWaterTop = new boolean[ChunkSection.VOLUME];
    /* Pro mesh()-Aufruf aufgebauter Ownership-Snapshot fuer NORTH/SOUTH/WEST/EAST. */
    private final int[] boundaryFaces = new int[4 * ChunkSection.SIZE];
    /* Wiederverwendete Zellkoordinate (Achsen-indiziert) */
    private final int[] cellPos = new int[3];
    private final float[] vertPos = new float[3];

    /* Greedy-Eignung + Face-Quads je BlockState-ID (lazy; Mesher ist ThreadLocal).
       Array statt HashMap: der Lookup läuft ~200k-mal pro Section (Pass 1 + Pass 2) —
       das Autoboxing der State-IDs würde pro Lookup allozieren. */
    private GreedyFaces[] greedyCache = new GreedyFaces[0];

    /**
     * Vorberechnete Daten eines greedy-fähigen States: die 6 Full-Cube-Face-Quads und je Face
     * die UV-Orientierung (läuft u entlang T1 oder T2 — entscheidet, ob u mit Breite oder Höhe
     * des gemergten Quads skaliert). {@link #NONE} = State ist nicht greedy-fähig.
     */
    private static final class GreedyFaces {
        static final GreedyFaces NONE = new GreedyFaces(null, null, null, null);

        final BlockState state;
        final BakedQuad[] quads;     // Index = Face
        final boolean[] uAlongT1;    // Index = Face
        final BakedQuad[] overlays;  // Index = Face, null = keine Overlays (Normalfall)

        GreedyFaces(BlockState state, BakedQuad[] quads, boolean[] uAlongT1, BakedQuad[] overlays) {
            this.state = state;
            this.quads = quads;
            this.uAlongT1 = uAlongT1;
            this.overlays = overlays;
        }
    }

    /**
     * Mesht eine Section. Läuft auf einem Worker-Thread - reine Daten, kein GL.
     *
     * @return MeshData; auch eine leere Section liefert einen leeren Ownership-Snapshot,
     *         damit ein Remesh zuvor publizierte Rand-Faces sicher loeschen kann
     */
    public MeshData mesh(Chunk chunk, int sectionIndex, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals) {
        ChunkSection section = chunk.getSection(sectionIndex);
        Arrays.fill(this.boundaryFaces, 0);
        if (section == null || section.isEmpty()) {
            return new MeshData(null, null, null, null, this.boundaryFaces.clone());
        }

        for (VertexBuffer buffer : this.buffers) buffer.reset();

        this.chunk = chunk;
        this.north = north;
        this.south = south;
        this.west = west;
        this.east = east;
        this.diagonals = diagonals;
        this.ambientOcclusion = GameSettings.get().ambientOcclusion;
        this.cullLeaves = GameSettings.get().leavesQuality == GameSettings.LeavesQuality.LOW;

        int baseY = sectionIndex << ChunkSection.SHIFT;
        this.sectionBaseY = baseY;

        /* Halo-Snapshot: Innenbereich direkt aus der Section (wie der frühere blockSnapshot),
           der 1-Zellen-Rand über den NeighborSampler (vertikale Nachbar-Sections desselben
           Chunks, Kardinale UND Diagonalen — exakt die bisherige Auflösung). */
        int[] blocks = this.haloBlocks;
        boolean[] occluder = this.haloOccluder;
        int size = ChunkSection.SIZE;
        for (int y = -1; y <= size; y++) {
            for (int z = -1; z <= size; z++) {
                for (int x = -1; x <= size; x++) {
                    int id = (x | y | z) >= 0 && x < size && y < size && z < size
                            ? section.getBlock(x, y, z)
                            : NeighborSampler.sample(chunk, north, south, west, east, diagonals, x, baseY + y, z);
                    int idx = haloIndex(x, y, z);
                    blocks[idx] = id;
                    occluder[idx] = this.occluderById(id);
                }
            }
        }
        Arrays.fill(this.haloLight, -1);

        /* Pass 1: Greedy Meshing für opake Full-Cube-Faces */
        this.greedyPass(baseY);

        /* Pass 1.5: flach-stille Wasser-Tops (Meeresoberfläche) greedy zu großen TRANSLUCENT-Quads
           zusammenfassen; markiert die betroffenen Zellen für Pass 2. */
        this.waterTopPass(baseY);

        /* Pass 2: alles andere (Fluids, Cross, Slabs, Stairs, ... sowie Cubes mit un-greedy-baren
           Modellen) über den klassischen Pfad */
        for (int y = 0; y < ChunkSection.SIZE; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    int stateId = blocks[haloIndex(x, y, z)];
                    if (stateId == Blocks.AIR) continue;
                    if (this.greedyFaces(stateId) != GreedyFaces.NONE) continue; // in Pass 1 erledigt

                    BlockState state = BlockRegistry.getState(stateId);
                    int worldY = baseY + y;

                    /* Fluids: Geometrie hängt von Nachbar-Leveln ab -> dynamisch statt gebackenes Modell.
                       Die Diagonal-Chunks braucht nur die Fluid-Eckhöhe an Chunk-Ecken. Ein bereits
                       im Wasser-Pass gemergtes flach-stilles Top wird hier ausgelassen. */
                    BakedQuad[] quads = state.isFluid()
                            ? FluidGeometry.build(state, chunk, north, south, west, east, diagonals, x, worldY, z,
                                    this.mergedWaterTop[snapIndex(x, y, z)])
                            : state.getModel();
                    if (quads.length == 0) continue;

                    VertexBuffer buffer = this.buffers[state.getRenderLayer().ordinal()];

                    float offsetX = 0F, offsetZ = 0F;
                    if (state.hasRandomOffset()) {
                        int worldX = (chunk.chunkX << ChunkSection.SHIFT) + x;
                        int worldZ = (chunk.chunkZ << ChunkSection.SHIFT) + z;
                        long seed = posSeed(worldX, worldZ);
                        offsetX = offsetFor(seed & 15L);
                        offsetZ = offsetFor((seed >> 8) & 15L);

                        /* Kleinvegetation (Cross mit Random-Offset) -> eigenes Detail-Segment
                           mit Pflanzen-Hash für die Distanz-Ausdünnung im Shader. Der Hash
                           kommt aus dem XZ-Seed: beide Hälften einer Tall-Grass-Pflanze teilen
                           ihn und verschwinden gemeinsam. 1..255 (0 = "kein Detail"). */
                        if (state.getRenderLayer() == RenderLayer.CUTOUT) {
                            buffer = this.buffers[DETAIL_BUFFER];
                            this.plantHash = (int) ((seed >>> 16) & 0xFF) | 1;
                        }
                    }

                    for (BakedQuad quad : quads) {
                        int cullFace = quad.cullFace();
                        if (cullFace != BakedQuad.NO_CULL) {
                            int nx = x + FACE_OFFSET[cullFace][0];
                            int ny = worldY + FACE_OFFSET[cullFace][1];
                            int nz = z + FACE_OFFSET[cullFace][2];

                            int neighborId = this.sample(nx, ny, nz);
                            if (!shouldRenderFace(state, neighborId)) continue;
                            this.markBoundaryFace(cullFace, x, y, z);
                        } else if (state.isFluid()) {
                            /* FluidGeometry hat die Nachbarentscheidung bereits getroffen und
                               liefert nur wirklich sichtbare Seiten, allerdings bewusst mit
                               NO_CULL. Die planare Seitenrichtung wird deshalb aus dem Quad
                               gelesen; Tops/Bottoms ergeben NO_CULL und werden ignoriert. */
                            this.markBoundaryFace(fluidSideFace(quad), x, y, z);
                        }
                        int vertexFlags = state.isFluid() && FluidGeometry.isFlatSourceTop(quad)
                                ? FLAT_SOURCE_FLUID_TOP : 0;
                        this.emitQuad(buffer, quad, x, y, worldY, z, offsetX, offsetZ,
                                vertexFlags);
                    }
                    this.plantHash = 0; // nur die Detail-Quads tragen den Hash

                    /* Seiten-Overlays (Sicherheitsnetz für nicht-greedy-fähige Overlay-Blöcke):
                       gleiche Cull-Prüfung, Ziel immer CUTOUT. */
                    for (BakedQuad quad : state.getOverlay()) {
                        int cullFace = quad.cullFace();
                        int nx = x + FACE_OFFSET[cullFace][0];
                        int ny = worldY + FACE_OFFSET[cullFace][1];
                        int nz = z + FACE_OFFSET[cullFace][2];
                        int neighborId = this.sample(nx, ny, nz);
                        if (!shouldRenderFace(state, neighborId)) continue;
                        this.markBoundaryFace(cullFace, x, y, z);
                        this.emitQuad(this.buffers[RenderLayer.CUTOUT.ordinal()], quad, x, y, worldY, z, offsetX, offsetZ);
                    }
                }
            }
        }

        this.chunk = this.north = this.south = this.west = this.east = null;
        this.diagonals = null;

        MeshData data = new MeshData(
                this.buffers[0].copyOrNull(),
                this.buffers[1].copyOrNull(),
                this.buffers[2].copyOrNull(),
                this.buffers[DETAIL_BUFFER].copyOrNull(),
                this.boundaryFaces.clone()
        );
        return data;
    }

    /* ------------------------- Greedy Meshing ------------------------- */

    /**
     * Pass 1: fasst benachbarte, identisch aussehende Full-Cube-Faces pro Face-Richtung und
     * Slice zu großen Quads zusammen (Textur kachelt über UV &gt; 1). Merge-Schlüssel =
     * State-ID + uniformer AO-Wert; Zellen mit uneinheitlichem AO (Kanten/Ecken) werden
     * einzeln mit per-Vertex-AO emittiert. Nur der OPAQUE-Layer — greedy-fähige States
     * sind per Definition opak.
     */
    private void greedyPass(int baseY) {
        VertexBuffer buffer = this.buffers[RenderLayer.OPAQUE.ordinal()];
        long[] grid = this.keyGrid;
        int[] pos = this.cellPos;
        int size = ChunkSection.SIZE;

        for (int face = 0; face < 6; face++) {
            int axisN = AXIS_N[face], axisT1 = AXIS_T1[face], axisT2 = AXIS_T2[face];
            int offX = FACE_OFFSET[face][0], offY = FACE_OFFSET[face][1], offZ = FACE_OFFSET[face][2];

            for (int slice = 0; slice < size; slice++) {
                Arrays.fill(grid, 0L);
                boolean any = false;

                /* Sichtbare, greedy-fähige Zellen der Slice einsammeln */
                for (int b = 0; b < size; b++) {
                    for (int a = 0; a < size; a++) {
                        pos[axisN] = slice;
                        pos[axisT1] = a;
                        pos[axisT2] = b;
                        int x = pos[0], y = pos[1], z = pos[2];

                        int stateId = this.haloBlocks[haloIndex(x, y, z)];
                        if (stateId == Blocks.AIR) continue;
                        GreedyFaces gf = this.greedyFaces(stateId);
                        if (gf == GreedyFaces.NONE) continue;

                        int worldY = baseY + y;
                        int neighborId = this.sample(x + offX, worldY + offY, z + offZ);
                        if (!shouldRenderFace(gf.state, neighborId)) continue;
                        this.markBoundaryFace(face, x, y, z);

                        /* Seiten-Overlay (Grasblock): Basis-Face EINZELN emittieren (nicht mergen)
                           + koplanares Overlay in den CUTOUT-Layer. Identische Vertices in derselben
                           Section => identische Tiefenwerte (GL-Invarianz); der CUTOUT-Pass zeichnet
                           mit "or-equal"-Depth-Func, damit das Overlay exakt gewinnt. */
                        if (gf.overlays != null && gf.overlays[face] != null) {
                            this.emitQuad(buffer, gf.quads[face], x, y, worldY, z, 0F, 0F);
                            this.emitQuad(this.buffers[RenderLayer.CUTOUT.ordinal()],
                                    gf.overlays[face], x, y, worldY, z, 0F, 0F);
                            continue;
                        }

                        BakedQuad quad = gf.quads[face];
                        /* Das gepackte Licht (Himmel + Block, 16 Bit) geht in den Merge-Schlüssel
                           ein: nur Flächen mit gleichem Licht dürfen zusammengefasst werden.
                           Ganzzahlig — der ==-Vergleich ist hier anders als beim AO nicht
                           ULP-empfindlich. */
                        this.computeCornerLight(quad, x, worldY, z, this.lightCorners);
                        int packedLight = this.lightCorners[0];
                        boolean lightUniform = packedLight == this.lightCorners[1]
                                && packedLight == this.lightCorners[2]
                                && packedLight == this.lightCorners[3];

                        if (!this.ambientOcclusion) {
                            /* AO aus: alle Zellen uniform hell (aoIdx 3 = 1.0) -> mergen maximal,
                               solange auch das Licht uniform ist. */
                            if (lightUniform) {
                                grid[b << ChunkSection.SHIFT | a] = (((long) stateId << 18) | ((long) packedLight << 2) | 3L) + 1L;
                                any = true;
                            } else {
                                this.emitQuad(buffer, quad, x, y, worldY, z, 0F, 0F);
                            }
                            continue;
                        }
                        this.computeAo(quad, x, worldY, z, this.aoCorners);
                        float ao = this.aoCorners[0];
                        if (lightUniform && ao == this.aoCorners[1] && ao == this.aoCorners[2]
                                && ao == this.aoCorners[3]) {
                            /* +1, damit der Schlüssel nie 0 (= leer) ist */
                            int aoIdx = Math.round((ao - 0.4F) * 5F);
                            grid[b << ChunkSection.SHIFT | a] = (((long) stateId << 18) | ((long) packedLight << 2) | aoIdx) + 1L;
                            any = true;
                        } else {
                            /* Uneinheitliches AO oder Licht (Kante/Ecke/Höhleneingang):
                               einzeln, mit Ecken-Werten + Flip */
                            this.emitQuad(buffer, quad, x, y, worldY, z, 0F, 0F);
                        }
                    }
                }
                if (!any) continue;

                /* Mergen: Breite zuerst, dann Höhe (klassisches Greedy) */
                for (int b = 0; b < size; b++) {
                    for (int a = 0; a < size; a++) {
                        long key = grid[b << ChunkSection.SHIFT | a];
                        if (key == 0L) continue;

                        int w = 1;
                        while (a + w < size && grid[b << ChunkSection.SHIFT | (a + w)] == key) w++;

                        int h = 1;
                        expand:
                        while (b + h < size) {
                            for (int i = 0; i < w; i++) {
                                if (grid[(b + h) << ChunkSection.SHIFT | (a + i)] != key) break expand;
                            }
                            h++;
                        }

                        for (int j = 0; j < h; j++) {
                            for (int i = 0; i < w; i++) grid[(b + j) << ChunkSection.SHIFT | (a + i)] = 0L;
                        }

                        /* Schlüssel-Layout: stateId << 18 | packedLight << 2 | aoIdx.
                           Wer das Licht-Feld verbreitert, muss ALLE DREI Stellen anfassen
                           (beide Bau-Zweige oben und diese Auflösung) — sonst kollidieren
                           Schlüssel und Flächen werden mit falscher Helligkeit gemergt. */
                        int stateId = (int) ((key - 1L) >>> 18);
                        int packedLight = (int) (((key - 1L) >>> 2) & 0xFFFFL);
                        float ao = 0.4F + ((key - 1L) & 3L) * 0.2F;
                        this.emitGreedyQuad(buffer, this.greedyFaces(stateId), face, slice, a, b, w, h, ao, packedLight);
                        a += w - 1;
                    }
                }
            }
        }
    }

    /** Emittiert ein gemergtes w×h-Quad; Geometrie und UV-Orientierung kommen aus dem Face-Quad. */
    private void emitGreedyQuad(VertexBuffer buffer, GreedyFaces gf, int face, int slice,
                                int a, int b, int w, int h, float ao, int light) {
        BakedQuad quad = gf.quads[face];
        float[] verts = quad.vertices();
        int axisN = AXIS_N[face], axisT1 = AXIS_T1[face], axisT2 = AXIS_T2[face];
        boolean uAlongT1 = gf.uAlongT1[face];

        int tint = quad.tint();
        float shade = quad.brightness() * ao;
        float r = shade * ((tint >> 16) & 0xFF) / 255F;
        float g = shade * ((tint >> 8) & 0xFF) / 255F;
        float bl = shade * (tint & 0xFF) / 255F;

        /* Biome-Tint pro VERTEX aus dem Eck-Grid: gemergte Quads bekommen einen glatten
           Farbverlauf, der Tint bleibt aus dem Merge-Schlüssel raus (Greedy unangetastet). */
        boolean biomeTint = quad.tintType() != BakedQuad.TINT_NONE && this.chunk.grassTintCorners != null;

        buffer.ensure(4 * VERTEX_SIZE);
        float[] p = this.vertPos;
        for (int c = 0; c < 4; c++) {
            int i = UNIQUE_VERTS[c] * 5;
            p[axisN] = slice + verts[i + axisN];
            p[axisT1] = a + verts[i + axisT1] * w;
            p[axisT2] = b + verts[i + axisT2] * h;
            if (biomeTint) {
                int t = this.biomeTint(quad.tintType(), p[0], p[2], tint);
                r = shade * ((t >> 16) & 0xFF) / 255F;
                g = shade * ((t >> 8) & 0xFF) / 255F;
                bl = shade * (t & 0xFF) / 255F;
            }
            /* Ecken-UVs sind 0/1; Skalierung mit w bzw. h lässt die Textur pro Block kacheln
               und erhält Spiegelung/Rotation des Original-Mappings (Periodizität). */
            float u = verts[i + 3] * (uAlongT1 ? w : h);
            float v = verts[i + 4] * (uAlongT1 ? h : w);
            putVertex(buffer, p[0], p[1], p[2], u, v, quad.textureLayer(), r, g, bl, light);
        }
    }

    /**
     * Pass 1.5: fasst benachbarte, flach-stille Fluid-Quell-Tops (typisch die Meeresoberfläche)
     * pro y-Slice zu großen Quads zusammen (Textur kachelt über UV &gt; 1, wie im Greedy-Pass) und
     * emittiert sie in den TRANSLUCENT-Layer. Merge-Schlüssel = State-ID (trennt Wasser/Lava und
     * unterschiedliche Level automatisch — mergefähig ist ohnehin nur Level 0). Betroffene Zellen
     * werden in {@link #mergedWaterTop} markiert, damit {@link FluidGeometry#build} ihr Top auslässt.
     * Boden, Seiten und fließende/schräge Tops bleiben in Pass 2.
     */
    private void waterTopPass(int baseY) {
        Arrays.fill(this.mergedWaterTop, false);
        VertexBuffer buffer = this.buffers[RenderLayer.TRANSLUCENT.ordinal()];
        long[] grid = this.keyGrid;
        int size = ChunkSection.SIZE;

        for (int y = 0; y < size; y++) {
            int worldY = baseY + y;
            Arrays.fill(grid, 0L);
            boolean any = false;

            /* Mergefähige flach-stille Tops der Slice einsammeln */
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int stateId = this.haloBlocks[haloIndex(x, y, z)];
                    if (stateId == Blocks.AIR) continue;
                    BlockState state = BlockRegistry.getState(stateId);
                    if (!state.isFluid()) continue;
                    if (!FluidGeometry.isMergeableFlatStillTop(state, this.chunk, this.north, this.south,
                            this.west, this.east, this.diagonals, x, worldY, z)) continue;
                    boolean backward = FluidGeometry.shouldRenderBackwardUpFace(this.chunk, this.north,
                            this.south, this.west, this.east, this.diagonals,
                            x, worldY, z, state.getBlock());
                    long key = ((((long) stateId) + 1L) << 1) | (backward ? 1L : 0L);
                    /* Ein einziges großes Wasser-Quad kann nicht zugleich vor und hinter den
                       einzelnen Faces eines transparenten Vollblocks sortiert werden. Nur am
                       direkten Kontakt zu Eis/Glas bekommt die Zelle deshalb einen eindeutigen
                       oberen Schlüssel und bleibt 1x1; der gesamte offene Rest bleibt greedy. */
                    if (this.touchesSolidTranslucent(x, worldY, z)) {
                        key |= ((long) ((z << ChunkSection.SHIFT | x) + 1)) << 32;
                    }
                    grid[z << ChunkSection.SHIFT | x] = key;
                    this.mergedWaterTop[snapIndex(x, y, z)] = true;
                    any = true;
                }
            }
            if (!any) continue;

            /* Mergen: Breite zuerst, dann Höhe (klassisches Greedy, s. greedyPass) */
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    long key = grid[z << ChunkSection.SHIFT | x];
                    if (key == 0L) continue;

                    int w = 1;
                    while (x + w < size && grid[z << ChunkSection.SHIFT | (x + w)] == key) w++;

                    int h = 1;
                    expand:
                    while (z + h < size) {
                        for (int i = 0; i < w; i++) {
                            if (grid[(z + h) << ChunkSection.SHIFT | (x + i)] != key) break expand;
                        }
                        h++;
                    }

                    for (int j = 0; j < h; j++) {
                        for (int i = 0; i < w; i++) grid[(z + j) << ChunkSection.SHIFT | (x + i)] = 0L;
                    }

                    boolean backward = (key & 1L) != 0L;
                    int stateId = (int) (((key & 0xFFFFFFFFL) >>> 1) - 1L);
                    this.emitWaterTop(buffer, stateId, backward, x, y, worldY, z, w, h);
                    x += w - 1;
                }
            }
        }
    }

    /** Konfliktzone, in der ein Greedy-Top lokal pro Fluidzelle sortierbar bleiben muss. */
    private boolean touchesSolidTranslucent(int x, int worldY, int z) {
        return isSolidTranslucent(this.sample(x - 1, worldY, z))
                || isSolidTranslucent(this.sample(x + 1, worldY, z))
                || isSolidTranslucent(this.sample(x, worldY, z - 1))
                || isSolidTranslucent(this.sample(x, worldY, z + 1))
                || isSolidTranslucent(this.sample(x, worldY + 1, z));
    }

    private static boolean isSolidTranslucent(int stateId) {
        BlockState state = BlockRegistry.getState(stateId);
        return state.isSolid() && !state.isFluid() && state.getRenderLayer() == RenderLayer.TRANSLUCENT;
    }

    /**
     * Emittiert ein flach-stilles Wasser-Top als w×h-Quad auf {@link FluidGeometry#SOURCE_HEIGHT}.
     * Winding/UV wie das Einzel-Top in {@link FluidGeometry}: A(0,0) B(0,h) C(w,h) D(w,0), Still-
     * Textur pro Block gekachelt. NO_CULL-Charakter — kein AO (volle Face-Helligkeit).
     *
     * <p>Licht flach aus der Zelle ÜBER der Oberfläche (dort steht der Himmelswert, nicht im
     * Wasser selbst). Bewusst NICHT im Merge-Schlüssel: auf offener See ist es ohnehin überall
     * 15, und ein Licht-Schlüssel würde die großen Ozean-Quads an jeder Uferschattierung
     * zerreißen.</p>
     */
    private void emitWaterTop(VertexBuffer buffer, int stateId, boolean backward,
                              int x, int localY, int worldY, int z, int w, int h) {
        BlockState state = BlockRegistry.getState(stateId);
        FluidInfo info = state.getBlock().getFluidInfo();
        int layer = info.stillLayer;
        int tint = info.lava ? BakedQuad.WHITE : FluidGeometry.WATER_TINT;
        float brightness = BlockModels.FACE_BRIGHTNESS[0];
        float r = brightness * ((tint >> 16) & 0xFF) / 255F;
        float g = brightness * ((tint >> 8) & 0xFF) / 255F;
        float b = brightness * (tint & 0xFF) / 255F;
        float y = localY + FluidGeometry.SOURCE_RENDER_HEIGHT;
        int light = VertexLight.fromStoragePacked(this.samplePackedLight(x, worldY + 1, z))
                | FLAT_SOURCE_FLUID_TOP;

        buffer.ensure((backward ? 8 : 4) * VERTEX_SIZE);
        putVertex(buffer, x, y, z, 0F, 0F, layer, r, g, b, light);
        putVertex(buffer, x, y, z + h, 0F, h, layer, r, g, b, light);
        putVertex(buffer, x + w, y, z + h, w, h, layer, r, g, b, light);
        putVertex(buffer, x + w, y, z, w, 0F, layer, r, g, b, light);
        if (backward) {
            putVertex(buffer, x + w, y, z, w, 0F, layer, r, g, b, light);
            putVertex(buffer, x + w, y, z + h, w, h, layer, r, g, b, light);
            putVertex(buffer, x, y, z + h, 0F, h, layer, r, g, b, light);
            putVertex(buffer, x, y, z, 0F, 0F, layer, r, g, b, light);
        }
    }

    /**
     * Biome-Tint an einer chunk-lokalen (x,z)-Position: bilinear zwischen den umliegenden
     * Eckwerten des 33x33-Grids (siehe {@link Chunk#grassTintCorners}). Fallback = gebackener
     * Platzhalter-Tint, falls der Generator keine Grids liefert.
     */
    private int biomeTint(int tintType, float px, float pz, int fallback) {
        int[] grid = tintType == BakedQuad.TINT_FOLIAGE ? this.chunk.foliageTintCorners : this.chunk.grassTintCorners;
        if (grid == null) return fallback;
        int n = ChunkSection.SIZE + 1;
        int x0 = Math.clamp((int) px, 0, ChunkSection.SIZE - 1);
        int z0 = Math.clamp((int) pz, 0, ChunkSection.SIZE - 1);
        float fx = px - x0, fz = pz - z0;
        int c00 = grid[x0 * n + z0], c01 = grid[x0 * n + z0 + 1];
        int c10 = grid[(x0 + 1) * n + z0], c11 = grid[(x0 + 1) * n + z0 + 1];
        int r = (int) lerp(lerp((c00 >> 16) & 0xFF, (c01 >> 16) & 0xFF, fz), lerp((c10 >> 16) & 0xFF, (c11 >> 16) & 0xFF, fz), fx);
        int g = (int) lerp(lerp((c00 >> 8) & 0xFF, (c01 >> 8) & 0xFF, fz), lerp((c10 >> 8) & 0xFF, (c11 >> 8) & 0xFF, fz), fx);
        int b = (int) lerp(lerp(c00 & 0xFF, c01 & 0xFF, fz), lerp(c10 & 0xFF, c11 & 0xFF, fz), fx);
        return (r << 16) | (g << 8) | b;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Greedy-Eignung eines States (lazy gecacht; Index = State-ID). */
    private GreedyFaces greedyFaces(int stateId) {
        GreedyFaces[] cache = this.greedyCache;
        if (stateId >= cache.length) {
            /* Größe steht nach dem Registry-Bake fest; wächst nur beim allerersten Zugriff */
            cache = Arrays.copyOf(cache, BlockRegistry.getStateCount());
            this.greedyCache = cache;
        }
        GreedyFaces gf = cache[stateId];
        if (gf == null) {
            gf = buildGreedyFaces(stateId);
            cache[stateId] = gf;
        }
        return gf;
    }

    /**
     * Greedy-fähig = opaker Full-Cube im OPAQUE-Layer ohne Random-Offset, dessen Modell aus
     * exakt 6 Full-Face-Quads (eines je Face, Ecken-Koordinaten und -UVs auf 0/1) besteht.
     * Alles andere (Slabs, Stairs, Custom-Modelle, ...) läuft über den klassischen Pfad.
     */
    private static GreedyFaces buildGreedyFaces(int stateId) {
        BlockState state = BlockRegistry.getState(stateId);
        if (!state.isOpaqueCube() || state.isFluid() || state.hasRandomOffset()
                || state.getRenderLayer() != RenderLayer.OPAQUE) {
            return GreedyFaces.NONE;
        }
        BakedQuad[] model = state.getModel();
        if (model == null || model.length != 6) return GreedyFaces.NONE;

        BakedQuad[] quads = new BakedQuad[6];
        for (BakedQuad quad : model) {
            int face = quad.cullFace();
            if (face < 0 || face >= 6 || quads[face] != null) return GreedyFaces.NONE;
            quads[face] = quad;
        }

        boolean[] uAlongT1 = new boolean[6];
        for (int face = 0; face < 6; face++) {
            BakedQuad quad = quads[face];
            int axisN = AXIS_N[face], axisT1 = AXIS_T1[face], axisT2 = AXIS_T2[face];
            float plane = FACE_OFFSET[face][0] + FACE_OFFSET[face][1] + FACE_OFFSET[face][2] > 0 ? 1F : 0F;
            float[] verts = quad.vertices();

            int cornerMask = 0;
            /* UV je geometrischer Ecke, indiziert wie cornerMask: (t1==1) | (t2==1) << 1 */
            float[] cu = new float[4], cv = new float[4];
            for (int c = 0; c < 4; c++) {
                int i = UNIQUE_VERTS[c] * 5;
                if (verts[i + axisN] != plane) return GreedyFaces.NONE;
                float c1 = verts[i + axisT1], c2 = verts[i + axisT2];
                float u = verts[i + 3], v = verts[i + 4];
                if ((c1 != 0F && c1 != 1F) || (c2 != 0F && c2 != 1F)) return GreedyFaces.NONE;
                if ((u != 0F && u != 1F) || (v != 0F && v != 1F)) return GreedyFaces.NONE;
                int corner = (c1 == 1F ? 1 : 0) | (c2 == 1F ? 2 : 0);
                cornerMask |= 1 << corner;
                cu[corner] = u;
                cv[corner] = v;
            }
            if (cornerMask != 0b1111) return GreedyFaces.NONE;

            /* Separierbarkeit über ALLE vier Ecken prüfen: u muss an genau einer Tangente hängen
               und v an der anderen. emitGreedyQuad skaliert u mit der Breite ODER der Höhe des
               gemergten Rechtecks — ein Mapping, das an beiden Achsen hängt, würde dort still
               falsch kacheln (erst bei w != h sichtbar). Seit die Blockstate-Rotation die UVs
               mitdreht, ist das der Sicherheitsgurt gegen fehlerhafte Modelle. */
            boolean uT1 = cu[0] != cu[1];
            if (uT1) {
                if (cu[2] != cu[0] || cu[3] != cu[1] || cv[0] != cv[1] || cv[2] != cv[3]
                        || cv[0] == cv[2]) {
                    return GreedyFaces.NONE;
                }
            } else {
                if (cu[0] != cu[1] || cu[2] != cu[3] || cu[0] == cu[2]
                        || cv[0] != cv[2] || cv[1] != cv[3] || cv[0] == cv[1]) {
                    return GreedyFaces.NONE;
                }
            }
            uAlongT1[face] = uT1;
        }

        /* Seiten-Overlays (Grasblock) je Face einsortieren — sie werden pro sichtbarer Zelle
           einzeln in den CUTOUT-Layer emittiert und mergen nicht (Greedy bleibt OPAQUE-only). */
        BakedQuad[] overlays = null;
        for (BakedQuad quad : state.getOverlay()) {
            if (overlays == null) overlays = new BakedQuad[6];
            overlays[quad.cullFace()] = quad;
        }
        return new GreedyFaces(state, quads, uAlongT1, overlays);
    }

    private static int snapIndex(int x, int y, int z) {
        return (y << (ChunkSection.SHIFT * 2)) | (z << ChunkSection.SHIFT) | x;
    }

    /* ------------------------------------------------------------------ */

    /**
     * Culling-Regeln:
     * 1. Nachbar ist ein opaker Full-Cube -> Face unsichtbar
     * 2. Nachbar ist DERSELBE Block und der Block cullt gegen sich selbst
     *    (Glas-an-Glas, später Wasser-an-Wasser) -> Face unsichtbar
     * 3. LeavesQuality LOW: Laub-an-Laub (auch fremde Laub-Arten) -> Face unsichtbar
     */
    private boolean shouldRenderFace(BlockState state, int neighborId) {
        BlockState neighbor = BlockRegistry.getState(neighborId);
        if (neighbor.isOpaqueCube()) return false;
        if (neighbor.getBlock() == state.getBlock() && state.cullsSameBlock()) return false;
        if (this.cullLeaves && state.isLeaves() && neighbor.isLeaves()) return false;
        return true;
    }

    private void emitQuad(VertexBuffer buffer, BakedQuad quad, int x, int localY, int worldY,
                          int z, float offsetX, float offsetZ) {
        this.emitQuad(buffer, quad, x, localY, worldY, z, offsetX, offsetZ, 0);
    }

    /** Merkt nur Faces, die wirklich an einem horizontalen Section-Rand emittiert werden. */
    private void markBoundaryFace(int face, int x, int y, int z) {
        int tangent;
        switch (face) {
            case 2 -> {
                if (z != 0) return;
                tangent = x;
            }
            case 3 -> {
                if (z != ChunkSection.MASK) return;
                tangent = x;
            }
            case 4 -> {
                if (x != 0) return;
                tangent = z;
            }
            case 5 -> {
                if (x != ChunkSection.MASK) return;
                tangent = z;
            }
            default -> {
                return;
            }
        }
        this.boundaryFaces[(face - 2) * ChunkSection.SIZE + tangent] |= 1 << y;
    }

    private static int fluidSideFace(BakedQuad quad) {
        float[] vertices = quad.vertices();
        float x = vertices[0], z = vertices[2];
        boolean constantX = true, constantZ = true;
        for (int p = 5; p < vertices.length; p += 5) {
            constantX &= Math.abs(vertices[p] - x) <= FLUSH_EPS;
            constantZ &= Math.abs(vertices[p + 2] - z) <= FLUSH_EPS;
        }
        if (constantX) return x < 0.5F ? 4 : 5;
        if (constantZ) return z < 0.5F ? 2 : 3;
        return BakedQuad.NO_CULL;
    }

    private void emitQuad(VertexBuffer buffer, BakedQuad quad, int x, int localY, int worldY,
                          int z, float offsetX, float offsetZ, int vertexFlags) {
        buffer.ensure(4 * VERTEX_SIZE);
        float[] verts = quad.vertices();
        int layer = quad.textureLayer();
        float brightness = quad.brightness();

        /* Per-Vertex-Farbe = Helligkeit * AO * Tint (0xRRGGBB). Tint ist normal weiß (neutral),
           Wasser bringt seine Blaufarbe mit. So bleibt der Shader-Multiply unverändert. */
        int tint = quad.tint();
        /* Biome-Tint am Blockzentrum (Cross/Leaves/Overlay sind blockweise Quads) */
        if (quad.tintType() != BakedQuad.TINT_NONE) {
            tint = this.biomeTint(quad.tintType(), x + 0.5F, z + 0.5F, tint);
        }
        float r = brightness * ((tint >> 16) & 0xFF) / 255F;
        float g = brightness * ((tint >> 8) & 0xFF) / 255F;
        float b = brightness * (tint & 0xFF) / 255F;

        /* AO für jedes Quad mit achsenparalleler Richtung — auch für Innenflächen von Teilblöcken
           (Slab-Oberseite, Treppen-Trittfläche), die kein cullFace haben. Quads ohne Richtung
           (Cross-Pflanzen, nicht-planare Fluid-Geometrie) bleiben unverändert hell. */
        int[] emitOrder = EMIT_NORMAL;
        float[] ao = null;
        int[] light = this.lightCorners;
        this.computeCornerLight(quad, x, worldY, z, light);
        if (this.ambientOcclusion && quad.face() >= 0) {
            ao = this.aoCorners;
            this.computeAo(quad, x, worldY, z, ao);
        }
        /* AO und Licht gemeinsam bewerten: getrennte Entscheidungen konnten an Lichtkanten die
           sichtbar dunklere Diagonale waehlen und ein Dreieck als Spike in den Verlauf ziehen. */
        if (shouldFlipForSmoothLighting(ao, light)) {
            emitOrder = EMIT_FLIPPED;
        }

        for (int v = 0; v < 4; v++) {
            int corner = emitOrder[v];
            int i = UNIQUE_VERTS[corner] * 5;
            float aoValue = ao != null ? ao[corner] : 1F;
            putVertex(buffer,
                    verts[i] + x + offsetX, verts[i + 1] + localY, verts[i + 2] + z + offsetZ,
                    verts[i + 3], verts[i + 4], layer,
                    r * aoValue, g * aoValue, b * aoValue, light[corner] | vertexFlags);
        }
    }

    /** Statischer Shader-Anteil fuer eine stabile Diagonalwahl ohne Remesh bei Reglerwechsel. */
    private static float visibleCornerScore(float[] ao, int[] light, int corner) {
        float f = VertexLight.effective(light[corner]) / 255F;
        float curved = f / (4F - 3F * f);
        float ambient = 0.04F + 0.96F * curved;
        return (ao != null ? ao[corner] : 1F) * ambient;
    }

    /** Paketintern fuer einen deterministischen Regressionstest beider Quad-Diagonalen. */
    static boolean shouldFlipForSmoothLighting(float[] ao, int[] light) {
        return visibleCornerScore(ao, light, 1) + visibleCornerScore(ao, light, 3)
                > visibleCornerScore(ao, light, 0) + visibleCornerScore(ao, light, 2);
    }

    /**
     * <b>Gepacktes</b> Licht an den 4 Quad-Ecken (Reihenfolge A,B,C,D wie {@link #computeAo}):
     * Himmelslicht in Bits 0-7, Blocklicht in Bits 8-15, beide 0..255. Je Kanal das Mittel der vier
     * Zellen im Layer VOR der Face, die die Ecke berühren. Wie bei Minecraft ersetzt die
     * Basiszelle vor der Face jedes okkludierte Kanten-/Diagonal-Sample; der Nenner bleibt vier.
     * Sind beide Kanten-Nachbarn massiv, wird die Diagonale ebenfalls durch die Basis ersetzt,
     * damit eine eingemauerte Lichtquelle nicht über Eck ins Bild leckt.
     * Quads ohne achsenparallele Richtung (Cross-Pflanzen,
     * nicht-planare Fluid-Geometrie) bekommen flach das Licht der eigenen Zelle.
     *
     * <p>Dass hier schon gepackt wird, hält alles dahinter unverändert: {@link #putVertex},
     * {@link #emitGreedyQuad} und {@code emitWaterTop} reichen den einen int einfach durch.</p>
     *
     * <p><b>Anders als {@link #computeAo}</b> wird die Basiszelle IMMER eine Zelle in
     * Face-Richtung verschoben, nie {@code flush}-abhängig. Übernähme man die AO-Logik, wäre bei
     * einer Slab-Oberseite (y = 0,5, nicht bündig) die Basiszelle der Slab-Block selbst, alle
     * vier Zellen wären okkludiert und die Fläche würde schwarz.</p>
     */
    private void computeCornerLight(BakedQuad quad, int x, int y, int z, int[] out) {
        int face = quad.face();
        if (face < 0) {
            int own = VertexLight.fromStoragePacked(this.samplePackedLight(x, y, z));
            out[0] = out[1] = out[2] = out[3] = own;
            return;
        }
        int fx = x + FACE_OFFSET[face][0];
        int fy = y + FACE_OFFSET[face][1];
        int fz = z + FACE_OFFSET[face][2];

        int t1 = AXIS_T1[face], t2 = AXIS_T2[face];
        float[] verts = quad.vertices();
        for (int c = 0; c < 4; c++) {
            int i = UNIQUE_VERTS[c] * 5;
            int s = verts[i + t1] >= 0.5F ? 1 : -1;
            int t = verts[i + t2] >= 0.5F ? 1 : -1;
            int s1x = t1 == 0 ? s : 0, s1y = t1 == 1 ? s : 0, s1z = t1 == 2 ? s : 0;
            int s2x = t2 == 0 ? t : 0, s2y = t2 == 1 ? t : 0, s2z = t2 == 2 ? t : 0;

            int center = this.samplePackedLight(fx, fy, fz);
            int skySum = center & 0xF;
            int blockSum = (center >>> 4) & 0xF;
            /* Sind BEIDE Kanten-Nachbarn massiv, führt von dieser Ecke aus kein Weg zur
               Diagonalen. Minecrafts Blend-Semantik ersetzt dann alle blockierten Samples durch
               die Basiszelle vor der Face. Das verhindert Eck-Lecks eingemauerter Lichtquellen,
               ohne den festen Vierer-Nenner oder gemeinsame Eckwerte zu verändern. */
            boolean side1 = this.occludes(fx + s1x, fy + s1y, fz + s1z);
            boolean side2 = this.occludes(fx + s2x, fy + s2y, fz + s2z);
            for (int cell = 1; cell < 4; cell++) {
                boolean useS1 = cell == 1 || cell == 3;
                boolean useS2 = cell == 2 || cell == 3;
                int cx = fx + (useS1 ? s1x : 0) + (useS2 ? s2x : 0);
                int cy = fy + (useS1 ? s1y : 0) + (useS2 ? s2y : 0);
                int cz = fz + (useS1 ? s1z : 0) + (useS2 ? s2z : 0);
                boolean blocked = cell == 1 ? side1 : cell == 2 ? side2
                        : (side1 && side2) || this.occludes(cx, cy, cz);
                int packed = blocked ? center : this.samplePackedLight(cx, cy, cz);
                skySum += packed & 0xF;
                blockSum += (packed >>> 4) & 0xF;
            }
            out[c] = VertexLight.average(skySum, blockSum, 4);
        }
    }

    /**
     * Packt einen Vertex ins 5-Int-Format (siehe {@link #VERTEX_SIZE}); {@code light} = gepacktes
     * Licht (Himmel in Bits 0-7, Block in Bits 8-15) aus {@link #computeCornerLight}.
     */
    private void putVertex(VertexBuffer buffer, float px, float py, float pz,
                                  float u, float v, int layer, float r, float g, float b, int light) {
        int xi = fixedPos(px), yi = fixedPos(py), zi = fixedPos(pz);
        int ui = fixedUv(u), vi = fixedUv(v);
        int ri = (int) (r * 255F + 0.5F);
        int gi = (int) (g * 255F + 0.5F);
        int bi = (int) (b * 255F + 0.5F);
        buffer.data[buffer.count++] = xi | yi << 16;
        buffer.data[buffer.count++] = zi | ui << 16;
        buffer.data[buffer.count++] = vi | layer << 16;
        buffer.data[buffer.count++] = ri | gi << 8 | bi << 16 | this.plantHash << 24;
        /* Skylight in Bits 0-7, Blocklicht in Bits 8-15; Bit 16 markiert flache Quell-Fluid-Tops,
           17-31 bleiben reserviert. Bewusst NICHT in int3 einmultipliziert: der Helligkeits-Regler
           und die Lichtkurve laufen im Shader, sonst wäre jede Helligkeitsänderung ein Voll-Remesh. */
        buffer.data[buffer.count++] = light;
    }

    private static int fixedPos(float f) {
        int v = Math.round((f + 1F) * POS_SCALE);
        return v < 0 ? 0 : Math.min(v, 0xFFFF);
    }

    private static int fixedUv(float f) {
        int v = Math.round((f + 1F) * UV_SCALE);
        return v < 0 ? 0 : Math.min(v, 0xFFFF);
    }

    /**
     * Ambient Occlusion nach Minecraft-Art ({@code ModelBlockRenderer.AmbientOcclusionFace}):
     * zuerst die 4 Ecken der EINHEITS-Face (volles 1x1-Quadrat) aus je zwei Kanten-Nachbarn und
     * dem Eck-Nachbarn, 4 Stufen, Ecke voll eingeschlossen (beide Kanten opak) = dunkelste Stufe.
     * Diese 4 Werte werden anschließend BILINEAR auf die tatsächlichen Quad-Ecken interpoliert —
     * nur so bekommen Teilblöcke (Treppenstufe, Slab-Seite) den richtigen Ausschnitt des Verlaufs
     * statt eines auf die Quad-Größe gestauchten oder vierfach identischen Wertes.
     *
     * <p>Die Sample-Ebene hängt davon ab, ob das Quad bündig auf der Blockgrenze liegt:
     * bündig (normales Würfel-Face) -> Nachbar-Layer VOR der Face; nicht bündig (Slab-Oberseite
     * bei y=0.5, Treppen-Trittfläche) -> die Schicht des Blocks SELBST. Genau das erzeugt in
     * Minecraft das dunkle Band am hinteren Rand einer Treppenstufe.
     *
     * @param quad Quad mit achsenparalleler Richtung ({@code quad.face() >= 0})
     * @param out  die 4 AO-Werte in der Reihenfolge der eindeutigen Quad-Ecken A,B,C,D
     */
    private void computeAo(BakedQuad quad, int x, int y, int z, float[] out) {
        int face = quad.face();
        int axisN = AXIS_N[face], t1 = AXIS_T1[face], t2 = AXIS_T2[face];
        float[] verts = quad.vertices();

        /* Bündig? Die Quad-Ebene ist planar, ein Vertex reicht für die Lage entlang der Normalen. */
        float plane = FACE_OFFSET[face][axisN] > 0 ? 1F : 0F;
        boolean flush = Math.abs(verts[UNIQUE_VERTS[0] * 5 + axisN] - plane) < FLUSH_EPS;

        /* Basiszelle der 3x3-Nachbarschaft: bündig eine Zelle in Face-Richtung, sonst der Block selbst. */
        int bx = x, by = y, bz = z;
        if (flush) {
            bx += FACE_OFFSET[face][0];
            by += FACE_OFFSET[face][1];
            bz += FACE_OFFSET[face][2];
        }
        /* Bei einer bündigen Face ist die Zelle direkt davor Vanillas vierter
           Shade-Brightness-Beitrag. Normalerweise ist das unsichtbar, weil ein opaker
           Vollwürfel die Face komplett cullt. Nicht-vollständige AO-Okkludierer wie eine
           ausgefahrene 12/16-Kolbenbasis lassen die Face jedoch teilweise sichtbar: ohne
           diesen Beitrag blieb die komplette Bodenfläche darunter voll hell, obwohl ihre
           Kontaktkanten bereits AO bekamen. Innenflächen von Slabs/Treppen dürfen den eigenen
           Block ausdrücklich nicht als zusätzlichen Okkludierer zählen. */
        boolean direct = flush && this.occludes(bx, by, bz);

        /* AO an den 4 Ecken der Einheits-Face; Offsets ausschließlich in den Tangentenachsen. */
        for (int cv = 0; cv < 2; cv++) {
            int t = cv == 0 ? -1 : 1;
            for (int cu = 0; cu < 2; cu++) {
                int s = cu == 0 ? -1 : 1;

                int s1x = t1 == 0 ? s : 0, s1y = t1 == 1 ? s : 0, s1z = t1 == 2 ? s : 0;
                int s2x = t2 == 0 ? t : 0, s2y = t2 == 1 ? t : 0, s2z = t2 == 2 ? t : 0;

                boolean side1 = this.occludes(bx + s1x, by + s1y, bz + s1z);
                boolean side2 = this.occludes(bx + s2x, by + s2y, bz + s2z);

                int level;
                if (side1 && side2) {
                    level = 0; // Ecke komplett eingeschlossen, Eck-Block egal
                } else {
                    boolean corner = this.occludes(bx + s1x + s2x, by + s1y + s2y, bz + s1z + s2z);
                    level = Math.max(0, 3 - (direct ? 1 : 0)
                            - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (corner ? 1 : 0));
                }
                this.aoUnit[cv << 1 | cu] = 0.4F + level * 0.2F;
            }
        }

        /* Bilinear auf die echten Quad-Ecken. Bewusst in Multiplikationsform (nicht lerp()):
           bei u/v aus {0,1} ist das Ergebnis bit-exakt der Eckwert, was der Uniformitäts-
           Vergleich des Greedy-Passes (== auf den 4 Werten) zwingend braucht. */
        for (int c = 0; c < 4; c++) {
            int i = UNIQUE_VERTS[c] * 5;
            float u = Math.clamp(verts[i + t1], 0F, 1F);
            float v = Math.clamp(verts[i + t2], 0F, 1F);
            float a = this.aoUnit[0] * (1F - u) + this.aoUnit[1] * u;
            float b = this.aoUnit[2] * (1F - u) + this.aoUnit[3] * u;
            out[c] = a * (1F - v) + b * v;
        }
    }

    private boolean occludes(int x, int y, int z) {
        int ly = y - this.sectionBaseY;
        if (inHalo(x, ly, z)) return this.haloOccluder[haloIndex(x, ly, z)];
        /* Außerhalb des Halos (nach der Reichweiten-Analyse unerreichbar — defensiv für
           künftige Aufrufer): der bisherige Live-Pfad. */
        return BlockRegistry.getState(NeighborSampler.sample(this.chunk, this.north, this.south,
                this.west, this.east, this.diagonals, x, y, z)).occludesAo();
    }

    /** Block-Sample inkl. Diagonal-Chunks (x/z dürfen -1..32 sein) — Werte aus dem Halo-Snapshot,
     *  dessen Rand über den geteilten {@link NeighborSampler} befüllt wurde. */
    private int sample(int x, int y, int z) {
        int ly = y - this.sectionBaseY;
        if (inHalo(x, ly, z)) return this.haloBlocks[haloIndex(x, ly, z)];
        return NeighborSampler.sample(this.chunk, this.north, this.south, this.west, this.east,
                this.diagonals, x, y, z);
    }

    /** Gepacktes Licht-Sample (Himmel Bits 0-3, Block 4-7), lazy memoisiert im Halo:
     *  der erste Zugriff je Zelle ist exakt der bisherige NeighborSampler-Aufruf. */
    private int samplePackedLight(int x, int y, int z) {
        int ly = y - this.sectionBaseY;
        if (inHalo(x, ly, z)) {
            int idx = haloIndex(x, ly, z);
            int v = this.haloLight[idx];
            if (v < 0) {
                v = NeighborSampler.samplePackedLight(this.chunk, this.north, this.south,
                        this.west, this.east, this.diagonals, x, y, z);
                this.haloLight[idx] = v;
            }
            return v;
        }
        return NeighborSampler.samplePackedLight(this.chunk, this.north, this.south, this.west, this.east,
                this.diagonals, x, y, z);
    }

    /** Halo-Koordinaten: x/z section-lokal -1..32, ly = y - sectionBaseY ebenfalls -1..32. */
    private static boolean inHalo(int x, int ly, int z) {
        return x >= -1 && x <= ChunkSection.SIZE && z >= -1 && z <= ChunkSection.SIZE
                && ly >= -1 && ly <= ChunkSection.SIZE;
    }

    private static int haloIndex(int x, int y, int z) {
        return ((y + 1) * HALO + (z + 1)) * HALO + (x + 1);
    }

    /** occludesAo je State-ID, lazy auf Registry-Größe gewachsen (Muster greedyCache). */
    private boolean occluderById(int stateId) {
        boolean[] cache = this.occluderCache;
        if (stateId >= cache.length) {
            cache = new boolean[BlockRegistry.getStateCount()];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = BlockRegistry.getState(i).occludesAo();
            }
            this.occluderCache = cache;
        }
        return cache[stateId];
    }

    /** Liefert aus 4 Seed-Bits einen Versatz in [-MAX_OFFSET, +MAX_OFFSET]. */
    private static float offsetFor(long bits) {
        return (bits / 15F - 0.5F) * 2F * MAX_OFFSET;
    }

    /**
     * Deterministischer Positions-Hash (entspricht Minecrafts Mth.getSeed mit y=0).
     * Gleiche Weltposition -> gleicher Versatz, stabil über Chunk-Grenzen und Remeshes.
     */
    private static long posSeed(int x, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    /* ------------------------------------------------------------------ */

    private static final class VertexBuffer {
        int[] data = new int[16384];
        int count = 0;

        void reset() {
            this.count = 0;
        }

        void ensure(int additional) {
            if (this.count + additional > this.data.length) {
                int[] bigger = new int[Math.max(this.data.length * 2, this.count + additional)];
                System.arraycopy(this.data, 0, bigger, 0, this.count);
                this.data = bigger;
            }
        }

        int[] copyOrNull() {
            if (this.count == 0) return null;
            int[] out = new int[this.count];
            System.arraycopy(this.data, 0, out, 0, this.count);
            return out;
        }
    }
}
