package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ChunkMesher {

    /**
     * Ints pro Vertex (gepacktes Format, 16 Bytes statt 36):
     * <pre>
     * int0: posX | posY &lt;&lt; 16     (u16 fixed-point 8.8, Bias +1 Block, section-lokal)
     * int1: posZ | u    &lt;&lt; 16     (u als u16 fixed-point 6.10, Bias +1)
     * int2: v    | layer &lt;&lt; 16    (v wie u; layer = Texture-Array-Layer)
     * int3: r | g &lt;&lt; 8 | b &lt;&lt; 16  (Farbe = Helligkeit * AO * Tint, je u8)
     * </pre>
     * Entpackt wird im Vertex-Shader des ChunkRenderers. Ein Quad = 4 Vertices (A,B,C,D),
     * Triangulierung über den geteilten Index-Buffer (0,1,2, 2,3,0).
     */
    public static final int VERTEX_SIZE = 4;

    /** Bias/Skalierung des Positions-Fixed-Points (1/256 Block; Bias fängt Offsets bis -1 ab). */
    public static final float POS_SCALE = 256F;
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

    /** Mesh-Ergebnis einer Section, getrennt nach RenderLayer. Arrays sind null wenn leer. */
    public static final class MeshData {
        public final int[] opaque;
        public final int[] cutout;
        public final int[] translucent;

        MeshData(int[] opaque, int[] cutout, int[] translucent) {
            this.opaque = opaque;
            this.cutout = cutout;
            this.translucent = translucent;
        }

        public boolean isEmpty() {
            return this.opaque == null && this.cutout == null && this.translucent == null;
        }
    }

    /* Ein wiederverwendeter Buffer pro RenderLayer (Index = RenderLayer.ordinal()) */
    private final VertexBuffer[] buffers = {new VertexBuffer(), new VertexBuffer(), new VertexBuffer()};

    /* Kontext des laufenden mesh()-Aufrufs (für AO-Sampling über Chunk-Grenzen).
       Mesher ist ThreadLocal -> keine Nebenläufigkeit; wird am Ende genullt (kein Chunk-Leak). */
    private Chunk chunk, north, south, west, east;
    private Chunk[] diagonals;

    /* Wiederverwendeter AO-Puffer (4 Eckwerte des aktuellen Quads) */
    private final float[] aoCorners = new float[4];

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

    /* Snapshot der Section-Blöcke (ein Palette-Read pro Zelle statt sechs im Greedy-Pass) */
    private final int[] blockSnapshot = new int[ChunkSection.VOLUME];
    /* Merge-Grid einer Slice: 0 = leer/schon emittiert, sonst Merge-Schlüssel */
    private final long[] keyGrid = new long[ChunkSection.SIZE * ChunkSection.SIZE];
    /* Wiederverwendete Zellkoordinate (Achsen-indiziert) */
    private final int[] cellPos = new int[3];
    private final float[] vertPos = new float[3];

    /* Greedy-Eignung + Face-Quads je BlockState-ID (lazy; Mesher ist ThreadLocal) */
    private final Map<Integer, GreedyFaces> greedyCache = new HashMap<>();

    /**
     * Vorberechnete Daten eines greedy-fähigen States: die 6 Full-Cube-Face-Quads und je Face
     * die UV-Orientierung (läuft u entlang T1 oder T2 — entscheidet, ob u mit Breite oder Höhe
     * des gemergten Quads skaliert). {@link #NONE} = State ist nicht greedy-fähig.
     */
    private static final class GreedyFaces {
        static final GreedyFaces NONE = new GreedyFaces(null, null, null);

        final BlockState state;
        final BakedQuad[] quads;     // Index = Face
        final boolean[] uAlongT1;    // Index = Face

        GreedyFaces(BlockState state, BakedQuad[] quads, boolean[] uAlongT1) {
            this.state = state;
            this.quads = quads;
            this.uAlongT1 = uAlongT1;
        }
    }

    /**
     * Mesht eine Section. Läuft auf einem Worker-Thread - reine Daten, kein GL.
     *
     * @return MeshData oder null, wenn die Section komplett leer ist
     */
    public MeshData mesh(Chunk chunk, int sectionIndex, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.isEmpty()) return null;

        for (VertexBuffer buffer : this.buffers) buffer.reset();

        this.chunk = chunk;
        this.north = north;
        this.south = south;
        this.west = west;
        this.east = east;
        this.diagonals = diagonals;

        int baseY = sectionIndex << ChunkSection.SHIFT;

        /* Snapshot der eigenen Section: der Greedy-Pass liest jede Zelle 6x — ein Array-Read
           ist deutlich billiger als der Paletten-Zugriff. */
        int[] blocks = this.blockSnapshot;
        for (int y = 0; y < ChunkSection.SIZE; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    blocks[snapIndex(x, y, z)] = section.getBlock(x, y, z);
                }
            }
        }

        /* Pass 1: Greedy Meshing für opake Full-Cube-Faces */
        this.greedyPass(baseY);

        /* Pass 2: alles andere (Fluids, Cross, Slabs, Stairs, ... sowie Cubes mit un-greedy-baren
           Modellen) über den klassischen Pfad */
        for (int y = 0; y < ChunkSection.SIZE; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    int stateId = blocks[snapIndex(x, y, z)];
                    if (stateId == Blocks.AIR) continue;
                    if (this.greedyFaces(stateId) != GreedyFaces.NONE) continue; // in Pass 1 erledigt

                    BlockState state = BlockRegistry.getState(stateId);
                    int worldY = baseY + y;

                    /* Fluids: Geometrie hängt von Nachbar-Leveln ab -> dynamisch statt gebackenes Modell.
                       Die Diagonal-Chunks braucht nur die Fluid-Eckhöhe an Chunk-Ecken. */
                    BakedQuad[] quads = state.isFluid()
                            ? FluidGeometry.build(state, chunk, north, south, west, east, diagonals, x, worldY, z)
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
                    }

                    for (BakedQuad quad : quads) {
                        int cullFace = quad.cullFace();
                        if (cullFace != BakedQuad.NO_CULL) {
                            int nx = x + FACE_OFFSET[cullFace][0];
                            int ny = worldY + FACE_OFFSET[cullFace][1];
                            int nz = z + FACE_OFFSET[cullFace][2];

                            int neighborId = getBlock(chunk, north, south, west, east, nx, ny, nz);
                            if (!shouldRenderFace(state, neighborId)) continue;
                        }
                        this.emitQuad(buffer, quad, x, y, worldY, z, offsetX, offsetZ);
                    }
                }
            }
        }

        this.chunk = this.north = this.south = this.west = this.east = null;
        this.diagonals = null;

        MeshData data = new MeshData(
                this.buffers[0].copyOrNull(),
                this.buffers[1].copyOrNull(),
                this.buffers[2].copyOrNull()
        );
        return data.isEmpty() ? null : data;
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

                        int stateId = this.blockSnapshot[snapIndex(x, y, z)];
                        if (stateId == Blocks.AIR) continue;
                        GreedyFaces gf = this.greedyFaces(stateId);
                        if (gf == GreedyFaces.NONE) continue;

                        int worldY = baseY + y;
                        int neighborId = this.sample(x + offX, worldY + offY, z + offZ);
                        if (!shouldRenderFace(gf.state, neighborId)) continue;

                        BakedQuad quad = gf.quads[face];
                        this.computeAo(quad, x, worldY, z, this.aoCorners);
                        float ao = this.aoCorners[0];
                        if (ao == this.aoCorners[1] && ao == this.aoCorners[2] && ao == this.aoCorners[3]) {
                            /* +1, damit der Schlüssel nie 0 (= leer) ist */
                            int aoIdx = Math.round((ao - 0.4F) * 5F);
                            grid[b << ChunkSection.SHIFT | a] = (((long) stateId << 2) | aoIdx) + 1L;
                            any = true;
                        } else {
                            /* Uneinheitliches AO (Kante/Ecke): einzeln, mit Ecken-AO + Flip */
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

                        int stateId = (int) ((key - 1L) >>> 2);
                        float ao = 0.4F + ((key - 1L) & 3L) * 0.2F;
                        this.emitGreedyQuad(buffer, this.greedyFaces(stateId), face, slice, a, b, w, h, ao);
                        a += w - 1;
                    }
                }
            }
        }
    }

    /** Emittiert ein gemergtes w×h-Quad; Geometrie und UV-Orientierung kommen aus dem Face-Quad. */
    private void emitGreedyQuad(VertexBuffer buffer, GreedyFaces gf, int face, int slice,
                                int a, int b, int w, int h, float ao) {
        BakedQuad quad = gf.quads[face];
        float[] verts = quad.vertices();
        int axisN = AXIS_N[face], axisT1 = AXIS_T1[face], axisT2 = AXIS_T2[face];
        boolean uAlongT1 = gf.uAlongT1[face];

        int tint = quad.tint();
        float shade = quad.brightness() * ao;
        float r = shade * ((tint >> 16) & 0xFF) / 255F;
        float g = shade * ((tint >> 8) & 0xFF) / 255F;
        float bl = shade * (tint & 0xFF) / 255F;

        buffer.ensure(4 * VERTEX_SIZE);
        float[] p = this.vertPos;
        for (int c = 0; c < 4; c++) {
            int i = UNIQUE_VERTS[c] * 5;
            p[axisN] = slice + verts[i + axisN];
            p[axisT1] = a + verts[i + axisT1] * w;
            p[axisT2] = b + verts[i + axisT2] * h;
            /* Ecken-UVs sind 0/1; Skalierung mit w bzw. h lässt die Textur pro Block kacheln
               und erhält Spiegelung/Rotation des Original-Mappings (Periodizität). */
            float u = verts[i + 3] * (uAlongT1 ? w : h);
            float v = verts[i + 4] * (uAlongT1 ? h : w);
            putVertex(buffer, p[0], p[1], p[2], u, v, quad.textureLayer(), r, g, bl);
        }
    }

    /** Greedy-Eignung eines States (lazy gecacht). */
    private GreedyFaces greedyFaces(int stateId) {
        GreedyFaces gf = this.greedyCache.get(stateId);
        if (gf == null) {
            gf = buildGreedyFaces(stateId);
            this.greedyCache.put(stateId, gf);
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
            float u00 = 0F, u10 = 0F;
            for (int c = 0; c < 4; c++) {
                int i = UNIQUE_VERTS[c] * 5;
                if (verts[i + axisN] != plane) return GreedyFaces.NONE;
                float c1 = verts[i + axisT1], c2 = verts[i + axisT2];
                float u = verts[i + 3], v = verts[i + 4];
                if ((c1 != 0F && c1 != 1F) || (c2 != 0F && c2 != 1F)) return GreedyFaces.NONE;
                if ((u != 0F && u != 1F) || (v != 0F && v != 1F)) return GreedyFaces.NONE;
                cornerMask |= 1 << ((c1 == 1F ? 1 : 0) | (c2 == 1F ? 2 : 0));
                if (c1 == 0F && c2 == 0F) u00 = u;
                if (c1 == 1F && c2 == 0F) u10 = u;
            }
            if (cornerMask != 0b1111) return GreedyFaces.NONE;
            uAlongT1[face] = u00 != u10;
        }
        return new GreedyFaces(state, quads, uAlongT1);
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
     */
    private static boolean shouldRenderFace(BlockState state, int neighborId) {
        BlockState neighbor = BlockRegistry.getState(neighborId);
        if (neighbor.isOpaqueCube()) return false;
        if (neighbor.getBlock() == state.getBlock() && state.cullsSameBlock()) return false;
        return true;
    }

    private void emitQuad(VertexBuffer buffer, BakedQuad quad, int x, int localY, int worldY, int z, float offsetX, float offsetZ) {
        buffer.ensure(4 * VERTEX_SIZE);
        float[] verts = quad.vertices();
        int layer = quad.textureLayer();
        float brightness = quad.brightness();

        /* Per-Vertex-Farbe = Helligkeit * AO * Tint (0xRRGGBB). Tint ist normal weiß (neutral),
           Wasser bringt seine Blaufarbe mit. So bleibt der Shader-Multiply unverändert. */
        int tint = quad.tint();
        float r = brightness * ((tint >> 16) & 0xFF) / 255F;
        float g = brightness * ((tint >> 8) & 0xFF) / 255F;
        float b = brightness * (tint & 0xFF) / 255F;

        /* AO nur für Quads, die bündig an einem Nachbarn liegen (cullFace gesetzt).
           NO_CULL-Quads (Cross-Modelle, Fluids) bleiben unverändert hell. */
        int[] emitOrder = EMIT_NORMAL;
        float[] ao = null;
        if (quad.cullFace() != BakedQuad.NO_CULL) {
            ao = this.aoCorners;
            this.computeAo(quad, x, worldY, z, ao);
            /* Anisotropie-Fix: Diagonale durch das hellere Eckpaar legen, sonst
               kippt der Interpolations-Gradient je nach Triangulierung sichtbar. */
            if (ao[1] + ao[3] > ao[0] + ao[2]) emitOrder = EMIT_FLIPPED;
        }

        for (int v = 0; v < 4; v++) {
            int corner = emitOrder[v];
            int i = UNIQUE_VERTS[corner] * 5;
            float aoValue = ao != null ? ao[corner] : 1F;
            putVertex(buffer,
                    verts[i] + x + offsetX, verts[i + 1] + localY, verts[i + 2] + z + offsetZ,
                    verts[i + 3], verts[i + 4], layer,
                    r * aoValue, g * aoValue, b * aoValue);
        }
    }

    /** Packt einen Vertex ins 4-Int-Format (siehe {@link #VERTEX_SIZE}). */
    private static void putVertex(VertexBuffer buffer, float px, float py, float pz,
                                  float u, float v, int layer, float r, float g, float b) {
        int xi = fixedPos(px), yi = fixedPos(py), zi = fixedPos(pz);
        int ui = fixedUv(u), vi = fixedUv(v);
        int ri = (int) (r * 255F + 0.5F);
        int gi = (int) (g * 255F + 0.5F);
        int bi = (int) (b * 255F + 0.5F);
        buffer.data[buffer.count++] = xi | yi << 16;
        buffer.data[buffer.count++] = zi | ui << 16;
        buffer.data[buffer.count++] = vi | layer << 16;
        buffer.data[buffer.count++] = ri | gi << 8 | bi << 16;
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
     * Ambient Occlusion nach Minecraft-Art: pro Quad-Ecke die zwei Kanten-Nachbarn und der
     * Eck-Nachbar in der Ebene VOR der Face (also im Nachbar-Layer). 4 Stufen, Ecke voll
     * eingeschlossen (beide Kanten opak) = dunkelste Stufe.
     *
     * @param out die 4 AO-Werte in der Reihenfolge der eindeutigen Quad-Ecken A,B,C,D
     */
    private void computeAo(BakedQuad quad, int x, int y, int z, float[] out) {
        int face = quad.cullFace();
        int ox = FACE_OFFSET[face][0], oy = FACE_OFFSET[face][1], oz = FACE_OFFSET[face][2];

        /* Tangentenachsen der Face-Ebene: 0=x, 1=y, 2=z */
        int t1, t2;
        if (oy != 0) { t1 = 0; t2 = 2; }        // top/bottom -> x,z
        else if (oz != 0) { t1 = 0; t2 = 1; }   // north/south -> x,y
        else { t1 = 1; t2 = 2; }                // west/east -> y,z

        float[] verts = quad.vertices();
        for (int c = 0; c < 4; c++) {
            int i = UNIQUE_VERTS[c] * 5;
            /* Vorzeichen der Ecke entlang beider Tangenten (Koordinate 0 -> -1, Koordinate 1 -> +1) */
            int s = verts[i + t1] >= 0.5F ? 1 : -1;
            int t = verts[i + t2] >= 0.5F ? 1 : -1;

            int s1x = ox + (t1 == 0 ? s : 0), s1y = oy + (t1 == 1 ? s : 0), s1z = oz + (t1 == 2 ? s : 0);
            int s2x = ox + (t2 == 0 ? t : 0), s2y = oy + (t2 == 1 ? t : 0), s2z = oz + (t2 == 2 ? t : 0);

            boolean side1 = this.occludes(x + s1x, y + s1y, z + s1z);
            boolean side2 = this.occludes(x + s2x, y + s2y, z + s2z);

            int level;
            if (side1 && side2) {
                level = 0; // Ecke komplett eingeschlossen, Eck-Block egal
            } else {
                boolean corner = this.occludes(x + s1x + s2x - ox, y + s1y + s2y - oy, z + s1z + s2z - oz);
                level = 3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (corner ? 1 : 0);
            }
            out[c] = 0.4F + level * 0.2F;
        }
    }

    private boolean occludes(int x, int y, int z) {
        return BlockRegistry.getState(this.sample(x, y, z)).isOpaqueCube();
    }

    /** Block-Sample inkl. Diagonal-Chunks (x/z dürfen -1..32 sein); außerhalb geladener Chunks Luft. */
    private int sample(int x, int y, int z) {
        int size = ChunkSection.SIZE;
        if (x < 0 || x >= size) {
            if (z < 0 || z >= size) { // Diagonal-Ecke über zwei Chunk-Grenzen
                Chunk c = this.diagonals[(z < 0 ? 0 : 2) + (x < 0 ? 0 : 1)];
                return c != null ? c.getBlock(x < 0 ? size - 1 : 0, y, z < 0 ? size - 1 : 0) : 0;
            }
            Chunk c = x < 0 ? this.west : this.east;
            return c != null ? c.getBlock(x < 0 ? size - 1 : 0, y, z) : 0;
        }
        if (z < 0 || z >= size) {
            Chunk c = z < 0 ? this.north : this.south;
            return c != null ? c.getBlock(x, y, z < 0 ? size - 1 : 0) : 0;
        }
        return this.chunk.getBlock(x, y, z);
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

    /** Resolve a block including across chunk borders. x/z are section-local and may be -1 or 32. */
    private static int getBlock(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, int x, int y, int z) {
        if (x < 0)  return west  != null ? west.getBlock(ChunkSection.SIZE - 1, y, z) : 0;
        if (x >= ChunkSection.SIZE) return east != null ? east.getBlock(0, y, z) : 0;
        if (z < 0)  return north != null ? north.getBlock(x, y, ChunkSection.SIZE - 1) : 0;
        if (z >= ChunkSection.SIZE) return south != null ? south.getBlock(x, y, 0) : 0;
        return chunk.getBlock(x, y, z);
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