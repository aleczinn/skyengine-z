package de.skyengine.game.world;

import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.world.ChunkRenderer;

import java.util.ArrayList;
import java.util.List;

public class World implements IInitializable, IDisposable {

    private final String name;

    private final WorldGenerator generator;
    private final ChunkManager chunkManager;
    private final ChunkRenderer chunkRenderer;

    public World(String name) {
        this.name = name;
        this.generator = new WorldGenerator(123);
        this.chunkManager = new ChunkManager(this.generator);
        this.chunkRenderer = new ChunkRenderer(this.chunkManager);
    }

    public String getName() {
        return name;
    }

    @Override
    public void init() {
        this.chunkRenderer.init();
    }

    public void update(Input input, EntityPlayer player) {
        this.chunkManager.update(player);
    }

    public void render(Camera camera, float partialTick) {
        this.chunkManager.processRemeshes();
        this.chunkRenderer.render(camera);
    }

    @Override
    public void dispose() {
        this.chunkRenderer.dispose();
        this.chunkManager.dispose();
    }

    /** Block an Weltkoordinaten. Ungeladene Chunks zählen als Luft. */
    public short getBlock(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null || chunk.status == ChunkStatus.NEW || chunk.status == ChunkStatus.GENERATING) {
            return Blocks.AIR;
        }
        return chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    /** Setzt einen Block und markiert betroffene Chunks fürs Remeshing. */
    public void setBlock(int x, int y, int z, short block) {
        if (y < 0 || y >= Chunk.HEIGHT) return;

        int cx = x >> ChunkSection.SHIFT;
        int cz = z >> ChunkSection.SHIFT;

        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        /* Nur fertige Chunks editieren - vermeidet Races mit laufenden Mesh-Jobs */
        if (chunk == null || chunk.status != ChunkStatus.READY) return;

        int lx = x & ChunkSection.MASK;
        int lz = z & ChunkSection.MASK;

        int sy = y >> ChunkSection.SHIFT;

        chunk.setBlock(lx, y, lz, block);
        chunk.markSectionDirty(sy);

        /* Vertikale Section-Grenzen */
        if ((y & ChunkSection.MASK) == 0 && sy > 0) chunk.markSectionDirty(sy - 1);
        if ((y & ChunkSection.MASK) == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) chunk.markSectionDirty(sy + 1);


        /* An Chunk-Grenzen muss der Nachbar mit-remeshen, sonst bleiben dort falsche Faces */
        if (lx == 0) this.markDirty(cx - 1, cz, sy);
        if (lx == ChunkSection.MASK) this.markDirty(cx + 1, cz, sy);
        if (lz == 0) this.markDirty(cx, cz - 1, sy);
        if (lz == ChunkSection.MASK) this.markDirty(cx, cz + 1, sy);
    }

    private void markDirty(int cx, int cz, int sectionY) {
        Chunk chunk = this.chunkManager.getChunk(cx, cz);

        if (chunk != null && chunk.status == ChunkStatus.READY) {
            chunk.markSectionDirty(sectionY);
        }
    }

    /**
     * Sammelt alle soliden Block-AABBs innerhalb der Broadphase-Box.
     * Wird vom Kollisionssystem (Entity.move) aufgerufen.
     */
    public List<AABB> getCollisionBoxes(AABB area) {
        List<AABB> boxes = new ArrayList<>();

        int x0 = (int) Math.floor(area.minX);
        int x1 = (int) Math.floor(area.maxX);
        int y0 = (int) Math.floor(area.minY);
        int y1 = (int) Math.floor(area.maxY);
        int z0 = (int) Math.floor(area.minZ);
        int z1 = (int) Math.floor(area.maxZ);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (this.isBlockSolidForCollision(x, y, z)) {
                        boxes.add(new AABB(x, y, z, x + 1, y + 1, z + 1));
                    }
                }
            }
        }
        return boxes;
    }

    /**
     * Kollisionsabfrage. Ungeladene/ungenerierte Chunks zählen als SOLIDE,
     * damit der Spieler beim Laden der Welt nicht durch den Boden fällt.
     * (Bewusste Design-Entscheidung: man "klebt" stattdessen an einer
     * unsichtbaren Wand am Weltrand, bis der Chunk generiert ist.)
     */
    public boolean isBlockSolidForCollision(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return true;

        ChunkStatus status = chunk.status;
        if (status == ChunkStatus.NEW || status == ChunkStatus.GENERATING) return true;

        return Blocks.isSolid(chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK));
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public ChunkRenderer getChunkRenderer() {
        return chunkRenderer;
    }
}
