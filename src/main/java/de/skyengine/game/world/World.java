package de.skyengine.game.world;

import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
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
        this.tickBlockEntities();
    }

    /** Tickt alle tickenden BlockEntities geladener Chunks (Maschinen, Pipes, ...). */
    private void tickBlockEntities() {
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
            var entities = chunk.blockEntities();
            if (entities.isEmpty()) continue;
            /* Snapshot: ein tick() darf Blöcke setzen und die Map verändern. */
            for (BlockEntity be : new ArrayList<>(entities)) {
                if (be.getType().isTicking()) be.tick();
            }
        }
    }

    /** BlockEntity an Weltkoordinaten oder null. */
    public BlockEntity getBlockEntity(int x, int y, int z) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return null;
        return chunk.getBlockEntity(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
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

    /** Setzt einen Block (mit Nachbar-Updates für Verbindungen/Treppen-Ecken). */
    public void setBlock(int x, int y, int z, short block) {
        this.setBlock(x, y, z, block, true);
    }

    /**
     * @param updateNeighbors true: betroffene Nachbarn (Zäune, Panes, Treppen)
     *                        rechnen ihren State neu. false vermeidet Rekursion
     *                        bei den dadurch ausgelösten Folge-Updates.
     */
    public void setBlock(int x, int y, int z, short block, boolean updateNeighbors) {
        short old = this.getBlock(x, y, z);
        if (!this.setBlockRaw(x, y, z, block)) return;
        this.manageBlockEntity(x, y, z, old, block);
        if (updateNeighbors) this.updateNeighbors(x, y, z);
    }

    /**
     * Legt die BlockEntity an oder entfernt sie, wenn sich der BlockEntity-Typ ändert.
     * Reine State-Änderungen am selben Block (Verbindungen, Treppen-Ecken) lassen die
     * vorhandene BlockEntity unberührt.
     */
    private void manageBlockEntity(int x, int y, int z, short oldId, short newId) {
        BlockEntityType<?> oldType = Blocks.getState(oldId).getBlock().getBlockEntityType();
        BlockEntityType<?> newType = Blocks.getState(newId).getBlock().getBlockEntityType();
        if (oldType == newType) return;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return;
        int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;

        if (oldType != null) chunk.removeBlockEntity(lx, y, lz);
        if (newType != null) {
            BlockEntity be = newType.create(new BlockPos(x, y, z), Blocks.getState(newId));
            be.setWorld(this);
            chunk.setBlockEntity(lx, y, lz, be);
        }
    }

    /** Schreibt den Block und markiert Chunks fürs Remeshing. true bei Erfolg. */
    private boolean setBlockRaw(int x, int y, int z, short block) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;

        int cx = x >> ChunkSection.SHIFT;
        int cz = z >> ChunkSection.SHIFT;

        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        /* Nur fertige Chunks editieren - vermeidet Races mit laufenden Mesh-Jobs */
        if (chunk == null || chunk.status != ChunkStatus.READY) return false;

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
        return true;
    }

    /**
     * Lässt den geänderten Block und seine 4 horizontalen Nachbarn ihren State
     * neu berechnen (Verbindungen, Treppen-Ecken). Nur ein Ring - keine Kaskade.
     */
    private void updateNeighbors(int x, int y, int z) {
        this.updateStateAt(x, y, z);
        for (Direction d : Direction.horizontal()) {
            this.updateStateAt(x + d.offsetX(), y, z + d.offsetZ());
        }
        /* Vertikale Nachbarn fürs 6-dir-Connection-System (Pipes/Cables). */
        this.updateStateAt(x, y + 1, z);
        this.updateStateAt(x, y - 1, z);
    }

    private void updateStateAt(int x, int y, int z) {
        short id = this.getBlock(x, y, z);
        if (id == Blocks.AIR) return;
        BlockState current = Blocks.getState(id);
        BlockState updated = current.getBlock().getStateForNeighborUpdate(this, x, y, z, current);
        if (updated != current) {
            this.setBlock(x, y, z, updated.getId(), false);
        }
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
        /* Eins tiefer scannen: höhere Shapes (Zaun = 1.5) eines Blocks darunter erfassen */
        int y0 = (int) Math.floor(area.minY) - 1;
        int y1 = (int) Math.floor(area.maxY);
        int z0 = (int) Math.floor(area.minZ);
        int z1 = (int) Math.floor(area.maxZ);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockShape shape = this.getCollisionShape(x, y, z);
                    if (shape.isEmpty()) continue;
                    for (AABB local : shape.boxes()) {
                        boxes.add(local.copy().move(x, y, z));
                    }
                }
            }
        }
        return boxes;
    }

    /**
     * Kollisionsform an Weltkoordinaten. Ungeladene/ungenerierte Chunks zählen
     * als voller Würfel (siehe {@link #isBlockSolidForCollision}).
     */
    public BlockShape getCollisionShape(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return BlockShape.EMPTY;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return BlockShape.FULL_CUBE;

        ChunkStatus status = chunk.status;
        if (status == ChunkStatus.NEW || status == ChunkStatus.GENERATING) return BlockShape.FULL_CUBE;

        short id = chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
        return Blocks.getState(id).getCollisionShape();
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
