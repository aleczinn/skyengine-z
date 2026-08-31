package de.skyengine.game.world.chunk.debug;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.light.LightEngine;

/** Deterministic, GL-free chunk fixtures shared by mesher diagnostics. */
public final class MesherFixture {
    public static final int SEED = 123;
    public static final int CENTER_X = 3;
    public static final int CENTER_Z = -7;

    private MesherFixture() {}

    public static Grid generated() {
        Chunk[] chunks = emptyGrid();
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(SEED);
        for (Chunk chunk : chunks) generator.generate(chunk);
        Grid grid = new Grid(chunks);
        light(grid);
        return grid;
    }

    public static Grid solidFullCube() {
        Chunk[] chunks = emptyGrid();
        Chunk center = chunks[4];
        int baseY = 8 * ChunkSection.SIZE;
        for (int y = baseY; y < baseY + ChunkSection.SIZE; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) center.setBlock(x, y, z, Blocks.STONE);
            }
        }
        Grid grid = new Grid(chunks);
        light(grid);
        return grid;
    }

    public static Grid mixedModels() {
        Chunk[] chunks = emptyGrid();
        Chunk center = chunks[4];
        int y = 256;
        for (int z = 3; z < 29; z++) for (int x = 3; x < 29; x++) {
            center.setBlock(x, y, z, Blocks.GRASS_BLOCK);
        }
        for (int x = 5; x < 27; x++) {
            center.setBlock(x, y + 1, 5, Blocks.STONE);
            center.setBlock(x, y + 1, 26, Blocks.STONE_SLAB);
            center.setBlock(x, y + 2, 12, Blocks.STONE_STAIRS);
        }
        for (int z = 8; z < 24; z++) {
            center.setBlock(8, y + 1, z, Blocks.STONE_STAIRS);
            center.setBlock(23, y + 1, z, Blocks.STONE_SLAB);
        }
        for (int z = 14; z < 20; z++) for (int x = 14; x < 20; x++) {
            center.setBlock(x, y + 1, z, Blocks.WATER);
        }
        Grid grid = new Grid(chunks);
        light(grid);
        return grid;
    }

    private static Chunk[] emptyGrid() {
        Chunk[] chunks = new Chunk[9];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            chunks[(dz + 1) * 3 + dx + 1] = new Chunk(CENTER_X + dx, CENTER_Z + dz);
        }
        return chunks;
    }

    private static void light(Grid grid) {
        LightEngine engine = new LightEngine();
        for (int i = 0; i < grid.chunks.length; i++) {
            Chunk chunk = grid.chunks[i];
            engine.lightInitial(chunk, grid.at(i, 0, -1), grid.at(i, 0, 1),
                    grid.at(i, -1, 0), grid.at(i, 1, 0), grid.diagonals(i));
            chunk.status = ChunkStatus.LIT;
        }
        for (int i = 0; i < grid.chunks.length; i++) {
            Chunk chunk = grid.chunks[i];
            engine.exchangeBorders(chunk, grid.at(i, 0, -1), grid.at(i, 0, 1),
                    grid.at(i, -1, 0), grid.at(i, 1, 0), grid.diagonals(i));
        }
    }

    public static final class Grid {
        private final Chunk[] chunks;

        private Grid(Chunk[] chunks) {
            this.chunks = chunks;
        }

        public Chunk[] chunks() { return this.chunks; }

        public Chunk at(int index, int dx, int dz) {
            int gx = index % 3 + dx, gz = index / 3 + dz;
            return gx < 0 || gx > 2 || gz < 0 || gz > 2 ? null : this.chunks[gz * 3 + gx];
        }

        public Chunk[] diagonals(int index) {
            return new Chunk[]{this.at(index, -1, -1), this.at(index, 1, -1),
                    this.at(index, -1, 1), this.at(index, 1, 1)};
        }
    }
}
