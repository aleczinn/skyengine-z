package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;

public class ChunkMesher {

    /* Brightness per face: top, bottom, north(-z), south(+z), west(-x), east(+x) */
    private static final float[] FACE_BRIGHTNESS = {1.0F, 0.5F, 0.8F, 0.8F, 0.6F, 0.6F};

    /* Neighbor offsets per face */
    private static final int[][] FACE_OFFSET = {
            { 0,  1,  0}, { 0, -1,  0},
            { 0,  0, -1}, { 0,  0,  1},
            {-1,  0,  0}, { 1,  0,  0}
    };

    /* 6 vertices (2 triangles, CCW seen from outside) per face: x, y, z, u, v */
    private static final float[][] FACE_VERTICES = {
            // top (y+)
            {0,1,0, 0,0,  0,1,1, 0,1,  1,1,1, 1,1,  1,1,1, 1,1,  1,1,0, 1,0,  0,1,0, 0,0},
            // bottom (y-)
            {0,0,0, 0,0,  1,0,0, 1,0,  1,0,1, 1,1,  1,0,1, 1,1,  0,0,1, 0,1,  0,0,0, 0,0},
            // north (z-)
            {1,0,0, 0,1,  0,0,0, 1,1,  0,1,0, 1,0,  0,1,0, 1,0,  1,1,0, 0,0,  1,0,0, 0,1},
            // south (z+)
            {0,0,1, 0,1,  1,0,1, 1,1,  1,1,1, 1,0,  1,1,1, 1,0,  0,1,1, 0,0,  0,0,1, 0,1},
            // west (x-)
            {0,0,0, 0,1,  0,0,1, 1,1,  0,1,1, 1,0,  0,1,1, 1,0,  0,1,0, 0,0,  0,0,0, 0,1},
            // east (x+)
            {1,0,1, 0,1,  1,0,0, 1,1,  1,1,0, 1,0,  1,1,0, 1,0,  1,1,1, 0,0,  1,0,1, 0,1}
    };

    /** Floats per vertex: pos(3) + uv(2) + layer(1) + brightness(1) */
    public static final int VERTEX_SIZE = 7;

    private float[] data = new float[16384];
    private int floatCount = 0;

    /**
     * Mesh one section. Neighbor chunks are needed for faces at the chunk border.
     * Runs on a worker thread - pure data, no GL.
     *
     * @return vertex float array (copy, exact size) or null if the mesh is empty
     */
    public float[] mesh(Chunk chunk, int sectionIndex, Chunk north, Chunk south, Chunk west, Chunk east) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.isEmpty()) return null;

        this.floatCount = 0;
        int baseY = sectionIndex << ChunkSection.SHIFT;

        for (int y = 0; y < ChunkSection.SIZE; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    short block = section.getBlock(x, y, z);
                    if (block == Blocks.AIR) continue;

                    int worldY = baseY + y;

                    for (int face = 0; face < 6; face++) {
                        int nx = x + FACE_OFFSET[face][0];
                        int ny = worldY + FACE_OFFSET[face][1];
                        int nz = z + FACE_OFFSET[face][2];

                        short neighbor = getBlock(chunk, north, south, west, east, nx, ny, nz);
                        if (Blocks.isOpaque(neighbor)) continue; // face hidden

                        this.emitFace(face, x, baseY + y, z, Blocks.getTextureLayer(block, face));
                    }
                }
            }
        }

        if (this.floatCount == 0) return null;
        float[] result = new float[this.floatCount];
        System.arraycopy(this.data, 0, result, 0, this.floatCount);
        return result;
    }

    /** Resolve a block including across chunk borders. x/z are section-local and may be -1 or 32. */
    private static short getBlock(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, int x, int y, int z) {
        if (x < 0)  return west  != null ? west.getBlock(ChunkSection.SIZE - 1, y, z) : 0;
        if (x >= ChunkSection.SIZE) return east != null ? east.getBlock(0, y, z) : 0;
        if (z < 0)  return north != null ? north.getBlock(x, y, ChunkSection.SIZE - 1) : 0;
        if (z >= ChunkSection.SIZE) return south != null ? south.getBlock(x, y, 0) : 0;
        return chunk.getBlock(x, y, z);
    }

    private void emitFace(int face, int x, int y, int z, int textureLayer) {
        this.ensureCapacity(6 * VERTEX_SIZE);
        float[] verts = FACE_VERTICES[face];
        float brightness = FACE_BRIGHTNESS[face];

        for (int v = 0; v < 6; v++) {
            int i = v * 5;
            this.data[this.floatCount++] = verts[i] + x;
            this.data[this.floatCount++] = verts[i + 1] + y;
            this.data[this.floatCount++] = verts[i + 2] + z;
            this.data[this.floatCount++] = verts[i + 3];
            this.data[this.floatCount++] = verts[i + 4];
            this.data[this.floatCount++] = textureLayer;
            this.data[this.floatCount++] = brightness;
        }
    }

    private void ensureCapacity(int additional) {
        if (this.floatCount + additional > this.data.length) {
            float[] bigger = new float[this.data.length * 2];
            System.arraycopy(this.data, 0, bigger, 0, this.floatCount);
            this.data = bigger;
        }
    }
}