package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.world.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Chunk {

    public static final int HEIGHT = 512;
    public static final int SECTIONS = HEIGHT / ChunkSection.SIZE;

    public final int chunkX, chunkZ;
    private final ChunkSection[] sections = new ChunkSection[SECTIONS];

    /* BlockEntities, sparse; Key = lokale Position (x 0..31, z 0..31, y 0..511). Lazy. */
    private Map<Integer, BlockEntity> blockEntities;

    /* Welt-Entities (fallende Blöcke, gedroppte Items) in diesem Chunk. Lazy; nur READY-Chunks
       ticken/rendern. Entities, die den Chunk wechseln, werden umgehängt (siehe World.tickEntities). */
    private List<Entity> entities;

    /* Biome-Tint-Eckwerte (33x33, Index cx*33+cz, 0xRRGGBB) — vom Generator in generate()
       berechnet (pure Funktionswerte, an gemeinsamen Raendern chunk-uebergreifend identisch),
       vom Mesher pro Vertex bilinear gelesen. null = Generator ohne Biome-Tints (V1). */
    public int[] grassTintCorners;
    public int[] foliageTintCorners;

    /* volatile: written by workers, read by render thread */
    public volatile ChunkStatus status = ChunkStatus.NEW;
    private final AtomicInteger dirtySections = new AtomicInteger(0);

    /* Vom Renderer angewendete Section-Uploads. READY heißt nur "Batches eingereiht" —
       erst ab SECTIONS angewendeten Batches ist der Chunk wirklich sichtbar (die LOD-Maske
       wartet darauf, sonst reißt sie Löcher vor dem Upload). Nur der Render-Thread schreibt
       (applyBatch), gelesen wird auf demselben Thread — keine Synchronisation nötig. */
    private int uploadedSections;

    /* Unload-Gate: Chunk liegt außerhalb der Unload-Distanz, wartet aber, bis das LOD
       seine Zelle deckt (symmetrisch zum Lade-Gate der LOD-Maske). Nur Tick-Thread. */
    public boolean pendingUnload;

    /* Vom ChunkSerializer beim Laden gesammelte Positionen fließender Fluide (gepackt wie
       beKey) — der Tick-Thread plant daraus Scheduled-Ticks und nullt das Feld wieder
       (Sichtbarkeit über das volatile status-Publish des Load-Jobs). */
    public int[] pendingFluidTicks;

    /* Schützt die Section-Container (PalettedContainer + sections[]-Allokation) gegen
       gleichzeitige Worker-Mesh-Reads und Render-Thread-Writes. Mesh-Jobs nehmen den
       Read-Lock, World.setBlockRaw den Write-Lock. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /** Renderer meldet einen angewendeten Section-Upload (Remesh-Batches sättigen harmlos). */
    public void markSectionUploaded() {
        this.uploadedSections++;
    }

    /** true, sobald alle 16 Initial-Section-Meshes vom Renderer angewendet wurden. */
    public boolean isFullyUploaded() {
        return this.uploadedSections >= SECTIONS;
    }

    /**
     * local coords: x/z 0-31, y 0-511
     */
    public int getBlock(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT) return 0;
        ChunkSection section = this.sections[y >> ChunkSection.SHIFT];
        if (section == null) return 0;
        return section.getBlock(x, y & ChunkSection.MASK, z);
    }

    public void setBlock(int x, int y, int z, int block) {
        if (y < 0 || y >= HEIGHT) return;
        int sectionIndex = y >> ChunkSection.SHIFT;
        ChunkSection section = this.sections[sectionIndex];
        if (section == null) {
            if (block == 0) return;
            section = this.sections[sectionIndex] = new ChunkSection();
        }
        section.setBlock(x, y & ChunkSection.MASK, z, block);
    }

    public ChunkSection getSection(int index) {
        return this.sections[index];
    }

    /**
     * Setzt eine komplett aufgebaute Section ein. NUR für den Persistenz-Load-Pfad,
     * bevor der Chunk per Status-Publish lesbar wird — danach wäre das ein Race.
     */
    public void installSection(int index, ChunkSection section) {
        this.sections[index] = section;
    }

    /* --- BlockEntities --- */

    private static int beKey(int x, int y, int z) {
        return (x & 31) | ((z & 31) << 5) | ((y & 511) << 10);
    }

    public BlockEntity getBlockEntity(int x, int y, int z) {
        return this.blockEntities == null ? null : this.blockEntities.get(beKey(x, y, z));
    }

    public void setBlockEntity(int x, int y, int z, BlockEntity entity) {
        if (this.blockEntities == null) this.blockEntities = new HashMap<>();
        this.blockEntities.put(beKey(x, y, z), entity);
    }

    public void removeBlockEntity(int x, int y, int z) {
        if (this.blockEntities != null) this.blockEntities.remove(beKey(x, y, z));
    }

    public Collection<BlockEntity> blockEntities() {
        return this.blockEntities == null ? Collections.emptyList() : this.blockEntities.values();
    }

    /* --- Entities --- */

    public void addEntity(Entity entity) {
        if (this.entities == null) this.entities = new ArrayList<>();
        this.entities.add(entity);
    }

    public void removeEntity(Entity entity) {
        if (this.entities != null) this.entities.remove(entity);
    }

    public List<Entity> entities() {
        return this.entities == null ? Collections.emptyList() : this.entities;
    }

    /**
     * Pack chunk coords into a single long map key - no object allocation for lookups
     */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void markSectionDirty(int sectionIndex) {
        this.dirtySections.getAndUpdate(m -> m | (1 << sectionIndex));
    }

    public boolean hasDirtySections() {
        return this.dirtySections.get() != 0;
    }

    /**
     * Holt die Maske ab und setzt sie atomar auf 0.
     */
    public int consumeDirtySections() {
        return this.dirtySections.getAndSet(0);
    }

    /** Read-Lock für Worker-Mesh-Reads (mehrere Reader gleichzeitig erlaubt). */
    public Lock readLock() {
        return this.lock.readLock();
    }

    /** Write-Lock für Block-Edits auf dem Render-Thread (exklusiv gegen Mesh-Reads). */
    public Lock writeLock() {
        return this.lock.writeLock();
    }
}