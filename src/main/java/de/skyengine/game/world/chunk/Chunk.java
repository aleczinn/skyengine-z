package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.game.world.tick.SavedTick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /* Nur waehrend DECORATING: letzter Structure-BE-Wunsch pro Position. */
    private Map<Integer, StructureTemplate.BlockEntitySnapshot> pendingStructureBlockEntities;

    /* Welt-Entities (fallende Blöcke, gedroppte Items) in diesem Chunk. Lazy; nur READY-Chunks
       ticken/rendern. Entities, die den Chunk wechseln, werden umgehängt (siehe Dimension.tickEntities). */
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

    /* Vom Renderer angewendete Section-Uploads. READY heißt nur "Batches eingereiht";
       erst nach allen angewendeten Sections ist der Chunk vollständig sichtbar. */
    private int uploadedSectionMask;
    private long renderGeneration;

    /* Vom ChunkSerializer beim Laden übergebene Scheduled-Ticks. KEIN Persistenzspeicher,
       sondern nur ein temporärer Übergabepuffer zwischen Lade-Worker und Tick-Thread:
       Publikation über das volatile status-Publish des Load-Jobs; der Tick-Thread plant
       die Einträge ab READY in die ScheduledTickQueue ein und nullt das Feld. */
    public List<SavedTick> pendingScheduledTicks;

    /* Der Tick-/Render-Thread erhoeht die Mutationsepoch. Der IO-Thread bestaetigt nach einem
       erfolgreichen Write nur die Epoch seines Snapshots. Spaetere Mutationen bleiben dirty. */
    private volatile long modificationEpoch;
    private volatile long savedEpoch;
    /* true zwischen Einreihen und Abschluss eines Save-Jobs — der Unload wartet darauf
       (Chunk bleibt bis zum fertigen Save in der Map). Tick-Thread setzt, IO-Thread löscht. */
    public volatile boolean saveQueued;

    /* true, sobald Dimension.processReadyChunks den transienten Beobachter-Zustand initialisiert hat.
       Redstone-Kanten werden bei späteren READY-Meldungen erneut abgeglichen, dieses Flag
       verhindert dabei ausschließlich ein erneutes Observer-Seeding nach remeshAll. Ein neu
       geladener Chunk ist ein neues Objekt und startet automatisch false. Nur Tick-Thread. */
    public boolean loadSeeded;

    /* Beim Unload eines Nachbarn vorgemerkte offene Redstone-Kanten (Direction.faceIndex-Bits).
       Nicht-READY-Chunks sind noch nicht editierbar; Dimension arbeitet die Maske beim nächsten
       READY auf dem Tick-Thread ab. Ein neues Chunk-Objekt startet automatisch leer. */
    public int pendingRedstoneBoundaryMask;

    /* Schützt die Section-Container (PalettedContainer + sections[]-Allokation) gegen
       gleichzeitige Worker-Mesh-Reads und Render-Thread-Writes. Mesh-Jobs nehmen den
       Read-Lock, Dimension.setBlockRaw den Write-Lock. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /* Zuletzt angewendete Mesh-Sequenz je Section (nur Render-Thread, via applyBatch).
       Verhindert, dass ein älterer Batch (Erst-Mesh hinter einem Priority-Remesh in der
       Upload-FIFO) ein bereits angewendetes neueres Mesh derselben Section überschreibt. */
    private final long[] appliedMeshSeq = new long[SECTIONS];
    /**
     * true, wenn {@code meshSeq} neuer ist als das zuletzt angewendete Mesh dieser Section —
     * dann wird die Sequenz übernommen. false = Batch ist veraltet und muss verworfen werden.
     */
    public boolean tryApplyMeshSeq(int sectionY, long meshSeq) {
        if (meshSeq <= this.appliedMeshSeq[sectionY]) return false;
        this.appliedMeshSeq[sectionY] = meshSeq;
        return true;
    }

    public boolean tryApplyMeshSection(int sectionY, long meshSeq) {
        return this.tryApplyMeshSection(this.renderGeneration, sectionY, meshSeq);
    }

    /** Rejects uploads produced for a DimensionView that has already been destroyed. */
    public boolean tryApplyMeshSection(long generation, int sectionY, long meshSeq) {
        if (generation != this.renderGeneration) return false;
        return this.tryApplyMeshSeq(sectionY, meshSeq);
    }

    /** Renderer meldet einen angewendeten Section-Upload (Remesh-Batches sättigen harmlos). */
    void beginRenderGeneration(long generation) {
        this.renderGeneration = generation;
        this.uploadedSectionMask = 0;
        Arrays.fill(this.appliedMeshSeq, 0L);
    }

    void endRenderGeneration(long generation) {
        if (generation == this.renderGeneration) this.uploadedSectionMask = 0;
    }

    public void markSectionUploaded(long generation, int sectionY) {
        if (generation != this.renderGeneration || sectionY < 0 || sectionY >= SECTIONS) return;
        this.uploadedSectionMask |= 1 << sectionY;
    }

    /** true, sobald alle 16 Initial-Section-Meshes vom Renderer angewendet wurden. */
    public boolean isFullyUploaded() {
        return this.uploadedSectionMask == (1 << SECTIONS) - 1;
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

    /* Anmeldung „dieser Chunk hat BlockEntities": setBlockEntity meldet den Chunk beim Manager
       an (auch vom Worker beim Chunk-Restore!), der daraus seine chunksWithBlockEntities-Menge
       pflegt — BE-Renderer und -Ticker iterieren dann nur noch diese statt ALLER Chunks.
       Vom ChunkManager beim Anlegen gesetzt; null in Tools/Tests. */
    ConcurrentLinkedQueue<Chunk> blockEntityAnnounceQueue;

    public void setBlockEntity(int x, int y, int z, BlockEntity entity) {
        if (this.blockEntities == null) this.blockEntities = new HashMap<>();
        this.blockEntities.put(beKey(x, y, z), entity);
        ConcurrentLinkedQueue<Chunk> queue = this.blockEntityAnnounceQueue;
        if (queue != null) queue.add(this);
    }

    public void removeBlockEntity(int x, int y, int z) {
        if (this.blockEntities != null) this.blockEntities.remove(beKey(x, y, z));
    }

    public Collection<BlockEntity> blockEntities() {
        return this.blockEntities == null ? Collections.emptyList() : this.blockEntities.values();
    }

    /** Merkt eine Structure-BE vor; null bedeutet eine leere Default-BlockEntity. */
    public void queueStructureBlockEntity(int x, int y, int z,
                                          StructureTemplate.BlockEntitySnapshot snapshot) {
        if (this.pendingStructureBlockEntities == null) this.pendingStructureBlockEntities = new HashMap<>();
        this.pendingStructureBlockEntities.put(beKey(x, y, z), snapshot);
    }

    /** Materialisiert nach ALLEN Feature-Writes nur Eintraege, deren finaler BlockState noch passt. */
    public void materializeStructureBlockEntities() {
        if (this.pendingStructureBlockEntities == null) return;
        for (Map.Entry<Integer, StructureTemplate.BlockEntitySnapshot> entry
                : this.pendingStructureBlockEntities.entrySet()) {
            int packed = entry.getKey();
            int lx = packed & 31, lz = (packed >> 5) & 31, y = (packed >> 10) & 511;
            int stateId = this.getBlock(lx, y, lz);
            BlockEntityType<?> type = Blocks.getState(stateId).getBlock().getBlockEntityType();
            StructureTemplate.BlockEntitySnapshot snapshot = entry.getValue();
            if (type == null || snapshot != null
                    && type != Registries.BLOCK_ENTITY.get(snapshot.type())) continue;
            BlockPos pos = new BlockPos((this.chunkX << ChunkSection.SHIFT) + lx, y,
                    (this.chunkZ << ChunkSection.SHIFT) + lz);
            BlockEntity entity = type.create(pos, Blocks.getState(stateId));
            if (snapshot != null) entity.load(snapshot.data());
            this.setBlockEntity(lx, y, lz, entity);
        }
        this.pendingStructureBlockEntities = null;
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
                    this.markModified();
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

    /* Announce „dieser Chunk bringt gespeicherte Scheduled-Ticks mit": der Load-Worker
       (ChunkSerializer.deserialize) meldet den Chunk an, Dimension.restorePendingScheduledTicks
       pollt nur noch die Queue statt jeden Tick alle Chunks zu scannen. Vom ChunkManager
       beim Anlegen gesetzt; null in Tools/Tests. */
    ConcurrentLinkedQueue<Chunk> tickRestoreQueue;

    public void announceTickRestore() {
        ConcurrentLinkedQueue<Chunk> queue = this.tickRestoreQueue;
        if (queue != null && this.pendingScheduledTicks != null) queue.add(this);
    }

    /* Remesh-Anmeldung: markSectionDirty reiht den Chunk beim Manager ein (einmalig,
       CAS-geschützt gegen Doppel-Einträge — Markierungen kommen vom Render-Thread UND von
       Worker-Threads wie dem Licht-Randaustausch), statt dass processRemeshes jeden Frame
       ALLE Chunks scannt. Vom ChunkManager beim Anlegen gesetzt; null in Tools/Tests. */
    ConcurrentLinkedQueue<Chunk> remeshQueue;
    private final AtomicBoolean remeshEnqueued = new AtomicBoolean(false);
    private static final int PLAYER_DIRTY_BIT = 1 << 30;

    public record DirtySections(int mask, boolean player) {}

    public void markSectionDirty(int sectionIndex) {
        this.markSectionsDirty(1 << sectionIndex, false);
    }

    public void markSectionDirty(int sectionIndex, boolean player) {
        this.markSectionsDirty(1 << sectionIndex, player);
    }

    /** Mehrere Sections auf einmal dirty markieren (Massen-Edits: EIN CAS statt n).
     *  Expliziter CAS-Loop statt getAndUpdate — das capturing Lambda allozierte pro Aufruf. */
    public void markSectionsDirty(int mask) {
        this.markSectionsDirty(mask, false);
    }

    public void markSectionsDirty(int mask, boolean player) {
        if (mask == 0) return;
        int marked = mask | (player ? PLAYER_DIRTY_BIT : 0);
        int prev;
        do {
            prev = this.dirtySections.get();
            if ((prev | marked) == prev) break; // schon gesetzt
        } while (!this.dirtySections.compareAndSet(prev, prev | marked));
        this.enqueueRemesh();
    }

    /** In die Remesh-Queue einreihen, falls nicht schon eingereiht. */
    void enqueueRemesh() {
        ConcurrentLinkedQueue<Chunk> queue = this.remeshQueue;
        if (queue != null && this.remeshEnqueued.compareAndSet(false, true)) queue.add(this);
    }

    /** Buchführung nach dem Poll austragen — ab jetzt reihen neue Markierungen wieder ein. */
    void clearRemeshEnqueued() {
        this.remeshEnqueued.set(false);
    }

    public boolean hasDirtySections() {
        return (this.dirtySections.get() & ~PLAYER_DIRTY_BIT) != 0;
    }

    /**
     * Holt die Maske ab und setzt sie atomar auf 0.
     */
    public DirtySections consumeDirtySections() {
        int state = this.dirtySections.getAndSet(0);
        return new DirtySections(state & ~PLAYER_DIRTY_BIT, (state & PLAYER_DIRTY_BIT) != 0);
    }

    /** A full view rebuild supersedes every queued partial remesh. */
    void discardDirtySectionsForFullRemesh() {
        this.dirtySections.set(0);
        this.remeshEnqueued.set(false);
    }

    /** Markiert eine persistente Mutation. Nur der Tick-/Render-Thread darf diese Methode aufrufen. */
    public void markModified() {
        this.modificationEpoch++;
    }

    public boolean isModified() {
        return this.modificationEpoch != this.savedEpoch;
    }

    /** Epoch, die ein unter dem Read-Lock gezogener Save-Snapshot repraesentiert. */
    public long modificationEpoch() {
        return this.modificationEpoch;
    }

    /** Bestaetigt nur den tatsaechlich geschriebenen Stand; neuere Mutationen bleiben dirty. */
    public void markSaved(long snapshotEpoch) {
        if (snapshotEpoch > this.savedEpoch) this.savedEpoch = snapshotEpoch;
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
