package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;

public class ChunkMesher {

    /** Floats pro Vertex: pos(3) + uv(2) + layer(1) + brightness(1) */
    public static final int VERTEX_SIZE = 7;

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
        public final float[] opaque;
        public final float[] cutout;
        public final float[] translucent;

        MeshData(float[] opaque, float[] cutout, float[] translucent) {
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

    /**
     * Mesht eine Section. Läuft auf einem Worker-Thread - reine Daten, kein GL.
     *
     * @return MeshData oder null, wenn die Section komplett leer ist
     */
    public MeshData mesh(Chunk chunk, int sectionIndex, Chunk north, Chunk south, Chunk west, Chunk east) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.isEmpty()) return null;

        for (VertexBuffer buffer : this.buffers) buffer.reset();

        int baseY = sectionIndex << ChunkSection.SHIFT;

        for (int y = 0; y < ChunkSection.SIZE; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    short stateId = section.getBlock(x, y, z);
                    if (stateId == Blocks.AIR) continue;

                    BlockState state = BlockRegistry.getState(stateId);
                    BakedQuad[] quads = state.getModel();
                    if (quads.length == 0) continue;

                    VertexBuffer buffer = this.buffers[state.getRenderLayer().ordinal()];
                    int worldY = baseY + y;

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

                            short neighborId = getBlock(chunk, north, south, west, east, nx, ny, nz);
                            if (!shouldRenderFace(state, neighborId)) continue;
                        }
                        this.emitQuad(buffer, quad, x, worldY, z, offsetX, offsetZ);
                    }
                }
            }
        }

        MeshData data = new MeshData(
                this.buffers[0].copyOrNull(),
                this.buffers[1].copyOrNull(),
                this.buffers[2].copyOrNull()
        );
        return data.isEmpty() ? null : data;
    }

    /**
     * Culling-Regeln:
     * 1. Nachbar ist ein opaker Full-Cube -> Face unsichtbar
     * 2. Nachbar ist DERSELBE Block und der Block cullt gegen sich selbst
     *    (Glas-an-Glas, später Wasser-an-Wasser) -> Face unsichtbar
     */
    private static boolean shouldRenderFace(BlockState state, short neighborId) {
        BlockState neighbor = BlockRegistry.getState(neighborId);
        if (neighbor.isOpaqueCube()) return false;
        if (neighbor.getBlock() == state.getBlock() && state.cullsSameBlock()) return false;
        return true;
    }

    private void emitQuad(VertexBuffer buffer, BakedQuad quad, int x, int y, int z, float offsetX, float offsetZ) {
        buffer.ensure(6 * VERTEX_SIZE);
        float[] verts = quad.vertices();
        float layer = quad.textureLayer();
        float brightness = quad.brightness();

        for (int v = 0; v < 6; v++) {
            int i = v * 5;
            buffer.data[buffer.count++] = verts[i] + x + offsetX;
            buffer.data[buffer.count++] = verts[i + 1] + y;
            buffer.data[buffer.count++] = verts[i + 2] + z + offsetZ;
            buffer.data[buffer.count++] = verts[i + 3];
            buffer.data[buffer.count++] = verts[i + 4];
            buffer.data[buffer.count++] = layer;
            buffer.data[buffer.count++] = brightness;
        }
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
    private static short getBlock(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, int x, int y, int z) {
        if (x < 0)  return west  != null ? west.getBlock(ChunkSection.SIZE - 1, y, z) : 0;
        if (x >= ChunkSection.SIZE) return east != null ? east.getBlock(0, y, z) : 0;
        if (z < 0)  return north != null ? north.getBlock(x, y, ChunkSection.SIZE - 1) : 0;
        if (z >= ChunkSection.SIZE) return south != null ? south.getBlock(x, y, 0) : 0;
        return chunk.getBlock(x, y, z);
    }

    /* ------------------------------------------------------------------ */

    private static final class VertexBuffer {
        float[] data = new float[16384];
        int count = 0;

        void reset() {
            this.count = 0;
        }

        void ensure(int additional) {
            if (this.count + additional > this.data.length) {
                float[] bigger = new float[Math.max(this.data.length * 2, this.count + additional)];
                System.arraycopy(this.data, 0, bigger, 0, this.count);
                this.data = bigger;
            }
        }

        float[] copyOrNull() {
            if (this.count == 0) return null;
            float[] out = new float[this.count];
            System.arraycopy(this.data, 0, out, 0, this.count);
            return out;
        }
    }
}