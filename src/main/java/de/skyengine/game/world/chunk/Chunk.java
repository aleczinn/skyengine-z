package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.save.SavedBlockEntity;
import de.skyengine.game.world.tick.SavedTick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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

    /* Himmelslicht je Zelle (lazy pro Section, lock-frei — siehe LightStorage). Geschrieben vom
       LIGHTING-Job und von den Edit-Updates der LightEngine, gelesen vom Mesher. */
    public final de.skyengine.game.world.light.LightStorage light =
            new de.skyengine.game.world.light.LightStorage();

    /* Blocklicht je Zelle (Fackeln, Lava) — zweite, voellig unabhaengige Ebene neben dem
       Himmelslicht, mit demselben lock-freien Vertrag. Der Uniform-Default 0 passt exakt:
       eine Section ohne Leuchtblock materialisiert nie und kostet kein Byte. */
    public final de.skyengine.game.world.light.LightStorage blockLight =
            new de.skyengine.game.world.light.LightStorage();

    /* Heightmap fürs Himmelslicht: Y des höchsten licht-blockierenden Blocks + 1 je Säule
       (Index (z << 5) | x, 0 = Säule frei). Vom LIGHTING-Job berechnet — publiziert über den
       volatilen status = LIT wie grassTintCorners über status = GENERATED —, danach nur noch
       vom Render-Thread gepflegt (setBlock-Edits). */
    public int[] heightmap;

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

    /* Vom ChunkSerializer beim Laden übergebene Scheduled-Ticks. KEIN Persistenzspeicher,
       sondern nur ein temporärer Übergabepuffer zwischen Lade-Worker und Tick-Thread:
       Publikation über das volatile status-Publish des Load-Jobs; der Tick-Thread plant
       die Einträge ab READY in die ScheduledTickQueue ein und nullt das Feld. */
    public List<SavedTick> pendingScheduledTicks;

    /* Zum Save: Queue-Snapshot dieses Chunks, vom Tick-Thread im Enqueue-Moment gesetzt
       (WorldStorage.enqueueSave — einziger Ort!), vom IO-Thread im Read-Lock-Fenster
       gelesen und genullt (happens-before über die Executor-Übergabe). */
    public List<SavedTick> scheduledTickSnapshot;

    /* Zum Save: fertig serialisierte BlockEntity-Tags dieses Chunks, vom Tick-Thread im
       Enqueue-Moment gezogen (WorldStorage.enqueueSave — einziger Ort!). Der IO-Thread schreibt
       nur diese Kopie, statt be.save() auf dem Live-Zustand zu lesen (Race mit GUI-Mutationen,
       z.B. Truhen-Inventar). Vom IO-Thread im Read-Lock-Fenster gelesen und genullt. */
    public List<SavedBlockEntity> blockEntitySnapshot;

    /* Persistenz: seit dem letzten Save verändert (Edits/BlockEntity-Mutationen). Gesetzt auf
       dem Tick-Thread, zurückgesetzt NUR im Save-Job (IO-Thread, im Read-Lock-Fenster) —
       volatile für die Sichtbarkeit zwischen beiden. */
    public volatile boolean modified;
    /* true zwischen Einreihen und Abschluss eines Save-Jobs — der Unload wartet darauf
       (Chunk bleibt bis zum fertigen Save in der Map). Tick-Thread setzt, IO-Thread löscht. */
    public volatile boolean saveQueued;

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
     * Wandelt fallende Block-Entities dieses Chunks in echte Blöcke um (vor Unload-/Exit-Save;
     * Tick-Thread). Beim periodischen Autosave bewusst NICHT aufrufen — live fallender Sand
     * würde sichtbar in der Luft einrasten. Zellen, die inzwischen belegt sind, bleiben
     * Entity (geht dann wie Item-Entities bewusst nicht mit ins Save).
     */
    public void materializeFallingBlocks() {
        if (this.entities == null || this.entities.isEmpty()) return;
        this.writeLock().lock();
        try {
            for (Iterator<Entity> it = this.entities.iterator(); it.hasNext(); ) {
                Entity entity = it.next();
                if (!(entity instanceof FallingBlockEntity falling) || entity.isRemoved()) continue;
                int lx = ((int) Math.floor(entity.x)) & ChunkSection.MASK;
                int y = (int) Math.floor(entity.y);
                int lz = ((int) Math.floor(entity.z)) & ChunkSection.MASK;
                if (y >= 0 && y < HEIGHT && Blocks.canFallInto(this.getBlock(lx, y, lz))) {
                    this.setBlock(lx, y, lz, falling.getBlockId());
                    this.modified = true;
                    it.remove();
                }
            }
        } finally {
            this.writeLock().unlock();
        }
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