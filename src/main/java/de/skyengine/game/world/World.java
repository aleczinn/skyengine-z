package de.skyengine.game.world;

import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.lod.LodBlockAppearance;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.lod.WorldLodDataSource;
import de.skyengine.game.world.tick.ScheduledTickQueue;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.ChestRenderer;
import de.skyengine.graphics.blockentity.EnchantingTableRenderer;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.entity.EntityRenderer;
import de.skyengine.graphics.world.ChunkRenderer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

public class World implements IInitializable, IDisposable {

    private final String name;

    private final WorldGenerator generator;
    private final ChunkManager chunkManager;
    private final ChunkRenderer chunkRenderer;
    /* Heightmap-LOD jenseits der Render-Distanz; erst in init() erzeugt (braucht gebackene Modelle) */
    private LodManager lodManager;
    private final BlockEntityRenderDispatcher blockEntityRenderer = new BlockEntityRenderDispatcher();
    private final EntityRenderer entityRenderer = new EntityRenderer();

    /** Reentranzsicherer Puffer: Spawns aus einem laufenden Tick werden erst danach in den Chunk übernommen. */
    private final List<Entity> pendingEntities = new ArrayList<>();
    /** Zwischenpuffer für Entities, die in diesem Tick ihren Chunk wechseln (Umhängen nach dem Reconcile). */
    private final List<Entity> transferBuffer = new ArrayList<>();

    /** Wiederverwendeter Snapshot-Puffer fürs BlockEntity-Ticking (keine Allokation pro Chunk/Tick). */
    private final List<BlockEntity> tickScratch = new ArrayList<>();

    /** Der Spieler dieses Ticks (für BlockEntities, die ihn brauchen, z.B. das Zaubertisch-Buch). */
    private EntityPlayer player;

    /** Spielzeit in Ticks (20 TPS), bei jedem update() erhöht - Basis für geplante Ticks. */
    private long gameTime;
    private final Random random = new Random();
    private final ScheduledTickQueue scheduledTicks = new ScheduledTickQueue();

    /** Zufalls-Ticks pro nicht-leerer Section pro Tick (Wachstum, Verfall). 0 = aus. */
    private static final int RANDOM_TICK_SPEED = 3;

    /** Verzögerung, mit der geplante Ticks außerhalb der Simulations-Distanz erneut vorgemerkt werden. */
    private static final int OUT_OF_SIM_RESCHEDULE = 20;

    /** Nur Chunks in diesem Radius (in Chunks) um den Spieler ticken (Random/Scheduled/Entities). */
    private int simulationDistance = 10;
    /* Spieler-Chunk des laufenden Ticks - Basis für isSimulated(). */
    private int playerChunkX, playerChunkZ;

    public World(String name) {
        this.name = name;
        this.generator = new AlphaWorldGeneratorV2(123);
        /* Feature-Pass (Dekoration): biome-abhaengige Baeume (featureId 0) */
        this.chunkManager = new ChunkManager(this.generator,
                new ChunkDecorator(this.generator, List.of(new BiomeTreeFeature())));
        this.chunkRenderer = new ChunkRenderer(this.chunkManager);
    }

    public String getName() {
        return name;
    }

    public BlockEntityRenderDispatcher getBlockEntityRenderDispatcher() {
        return blockEntityRenderer;
    }

    @Override
    public void init() {
        this.chunkRenderer.init();
        /* LOD: abstrahierte Datenquelle (nah: echte Chunkdaten, fern: Generator-Noise) +
           Block-Darstellung aus den gebackenen Modellen — erst nach dem Registry-Bake. */
        this.lodManager = new LodManager(new WorldLodDataSource(this.chunkManager, this.generator),
                new LodBlockAppearance(), this.chunkManager);
        this.chunkRenderer.setLodManager(this.lodManager);
        this.blockEntityRenderer.register(BlockEntities.CHEST, new ChestRenderer());
        this.blockEntityRenderer.register(BlockEntities.ENCHANTING_TABLE, new EnchantingTableRenderer());
        this.blockEntityRenderer.init();
        this.entityRenderer.init(this.chunkRenderer.getTextureArray());
    }

    public void update(Input input, EntityPlayer player) {
        this.gameTime++;
        this.player = player;
        this.playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        this.chunkManager.update(player);
        this.lodManager.update(player);
        this.tickScheduled();
        this.tickRandomBlocks();
        this.tickBlockEntities();
        this.tickEntities();
    }

    /** Simulations-Distanz in Chunks setzen (min. 2). Chunks außerhalb werden nicht getickt. */
    public void setSimulationDistance(int distance) {
        this.simulationDistance = Math.max(2, distance);
    }

    /** true, wenn der Chunk im Simulations-Radius um den Spieler liegt (zirkulär, wie Render-Distanz). */
    private boolean isSimulated(int cx, int cz) {
        int dx = cx - this.playerChunkX, dz = cz - this.playerChunkZ;
        return dx * dx + dz * dz <= this.simulationDistance * this.simulationDistance;
    }

    /**
     * Tickt die Welt-Entities pro Chunk (wie BlockEntities). Ablauf: 1) gepufferte Spawns in ihre
     * Chunks übernehmen, 2) Entities aller READY-Chunks ticken (ein Tick darf neue Entities spawnen
     * -> landen im Puffer, kommen nächsten Tick dran), 3) Reconcile: entfernte raus, in einen anderen
     * Chunk gelaufene umhängen.
     */
    private void tickEntities() {
        if (!this.pendingEntities.isEmpty()) {
            for (Entity entity : this.pendingEntities) this.addToChunk(entity);
            this.pendingEntities.clear();
        }

        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
            if (!this.isSimulated(chunk.chunkX, chunk.chunkZ)) continue;
            List<Entity> list = chunk.entities();
            for (int i = 0; i < list.size(); i++) list.get(i).tick(this);
        }

        this.reconcileEntityChunks();
    }

    /**
     * Räumt nach dem Tick auf: entfernte Entities aussortieren und Entities, die ihren Chunk
     * verlassen haben, in den Zielchunk umhängen. Das Umhängen wird gesammelt und erst nach dem
     * Durchlauf angewandt, damit eine Entity nicht im selben Tick doppelt verarbeitet wird.
     */
    private void reconcileEntityChunks() {
        this.transferBuffer.clear();
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            List<Entity> list = chunk.entities();
            if (list.isEmpty()) continue;
            for (Iterator<Entity> it = list.iterator(); it.hasNext(); ) {
                Entity entity = it.next();
                if (entity.isRemoved()) {
                    it.remove();
                    continue;
                }
                int cx = (int) Math.floor(entity.x) >> ChunkSection.SHIFT;
                int cz = (int) Math.floor(entity.z) >> ChunkSection.SHIFT;
                if (cx != chunk.chunkX || cz != chunk.chunkZ) {
                    it.remove();
                    this.transferBuffer.add(entity);
                }
            }
        }
        for (Entity entity : this.transferBuffer) this.addToChunk(entity);
        this.transferBuffer.clear();
    }

    /** Hängt eine Entity in den Chunk an ihrer aktuellen Position; verwirft sie, wenn der Chunk nicht (READY) geladen ist. */
    private void addToChunk(Entity entity) {
        int cx = (int) Math.floor(entity.x) >> ChunkSection.SHIFT;
        int cz = (int) Math.floor(entity.z) >> ChunkSection.SHIFT;
        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        if (chunk != null && chunk.status == ChunkStatus.READY) chunk.addEntity(entity);
    }

    /** Reiht eine Entity zum Spawnen ein (Übernahme im nächsten {@link #tickEntities}). */
    public void spawnEntity(Entity entity) {
        this.pendingEntities.add(entity);
    }

    /** Spawnt einen flüssig fallenden Block an der Blockposition (Fußpunkt = y, zentriert in x/z). */
    public void spawnFallingBlock(int x, int y, int z, int blockId) {
        FallingBlockEntity entity = new FallingBlockEntity(blockId);
        entity.setPosition(x + 0.5, y, z + 0.5);
        this.spawnEntity(entity);
    }

    /** Spawnt ein gedropptes Item mit leichtem Anfangsimpuls (kleiner „Pop"). */
    public void spawnItem(double x, double y, double z, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemEntity entity = new ItemEntity(stack);
        entity.setPosition(x, y, z);
        entity.motionX = (this.random.nextDouble() - 0.5) * 0.1;
        entity.motionY = 0.2;
        entity.motionZ = (this.random.nextDouble() - 0.5) * 0.1;
        this.spawnEntity(entity);
    }

    /**
     * Wendet {@code action} auf alle Entities im {@code chunkRadius}-Umfeld um (x,z) an
     * (z.B. fürs Aufsammeln). {@code action} darf das removed-Flag setzen, aber die Listen nicht
     * strukturell verändern.
     */
    public void forEachEntityNearby(double x, double z, int chunkRadius, Consumer<Entity> action) {
        int ccx = (int) Math.floor(x) >> ChunkSection.SHIFT;
        int ccz = (int) Math.floor(z) >> ChunkSection.SHIFT;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = this.chunkManager.getChunk(ccx + dx, ccz + dz);
                if (chunk == null) continue;
                List<Entity> list = chunk.entities();
                for (int i = 0; i < list.size(); i++) action.accept(list.get(i));
            }
        }
    }

    /**
     * true, wenn eine kollidierbare Entity (fallender Block, später Mob) {@code box} schneidet.
     * Verhindert das Setzen eines Blocks an die Stelle einer solchen Entity (siehe GameContainer).
     * Prüft alle Chunks, die die Box berührt (Box kann eine Chunk-Grenze schneiden).
     */
    public boolean intersectsCollidableEntity(AABB box) {
        int cx0 = (int) Math.floor(box.minX) >> ChunkSection.SHIFT;
        int cx1 = (int) Math.floor(box.maxX) >> ChunkSection.SHIFT;
        int cz0 = (int) Math.floor(box.minZ) >> ChunkSection.SHIFT;
        int cz1 = (int) Math.floor(box.maxZ) >> ChunkSection.SHIFT;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Chunk chunk = this.chunkManager.getChunk(cx, cz);
                if (chunk == null) continue;
                for (Entity entity : chunk.entities()) {
                    if (entity.isCollidable() && !entity.isRemoved()
                            && entity.getBoundingBox().intersects(box)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Tickt alle tickenden BlockEntities geladener Chunks (Maschinen, Pipes, ...). */
    private void tickBlockEntities() {
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
            if (!this.isSimulated(chunk.chunkX, chunk.chunkZ)) continue;
            var entities = chunk.blockEntities();
            if (entities.isEmpty()) continue;
            /* Snapshot in den wiederverwendeten Puffer: ein tick() darf Blöcke setzen / die Map verändern. */
            this.tickScratch.clear();
            this.tickScratch.addAll(entities);
            for (int i = 0; i < this.tickScratch.size(); i++) {
                BlockEntity be = this.tickScratch.get(i);
                if (be.getType().isTicking()) be.tick();
            }
        }
    }

    /* --- Tick-Scheduler (Phase 1.1): geplante + Zufalls-Ticks --- */

    /**
     * Merkt einen geplanten Tick für die Position vor. Nach {@code delayTicks} Ticks (min. 1)
     * ruft der Block dort {@link de.skyengine.game.world.block.Block#scheduledTick} auf. Pro
     * Position ist nur ein Tick gleichzeitig vorgemerkt (Dedup). Basis für Fluss/Fall.
     */
    public void scheduleTick(int x, int y, int z, int delayTicks) {
        this.scheduledTicks.schedule(x, y, z, this.gameTime + Math.max(1, delayTicks));
    }

    /**
     * Wie {@link #scheduleTick}, zieht aber einen bereits anstehenden <em>späteren</em> Tick auf diese
     * frühere Zeit vor (statt ihn zu ignorieren). Für prompte Reaktionen (z.B. Lava+Wasser→Cobble),
     * die einen regulären Fluss-Tick überholen müssen.
     */
    public void scheduleTickEarlier(int x, int y, int z, int delayTicks) {
        this.scheduledTicks.scheduleEarlier(x, y, z, this.gameTime + Math.max(1, delayTicks));
    }

    /** true, wenn an der Position bereits ein geplanter Tick aussteht. */
    public boolean isTickScheduled(int x, int y, int z) {
        return this.scheduledTicks.isScheduled(x, y, z);
    }

    /** Aktuelle Spielzeit in Ticks (20 TPS). */
    public long getGameTime() {
        return this.gameTime;
    }

    /**
     * Führt alle fälligen geplanten Ticks aus (Fluss-Ausbreitung, Fallprüfung, ...). Außerhalb der
     * Simulations-Distanz wird der Tick nicht ausgeführt, sondern erneut vorgemerkt - der Fluss
     * friert dort ein und läuft weiter, sobald der Spieler zurückkommt. (Während des Drains neu
     * geplante Einträge verarbeitet drainDue erst im nächsten Tick -> keine Endlosschleife.)
     */
    private void tickScheduled() {
        this.scheduledTicks.drainDue(this.gameTime, (x, y, z) -> {
            if (!this.isSimulated(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT)) {
                this.scheduledTicks.schedule(x, y, z, this.gameTime + OUT_OF_SIM_RESCHEDULE);
                return;
            }
            BlockState state = Blocks.getState(this.getBlock(x, y, z));
            if (!state.isAir()) state.getBlock().scheduledTick(this, x, y, z, state);
        });
    }

    /**
     * Zufalls-Ticks: pro nicht-leerer Section werden {@link #RANDOM_TICK_SPEED} zufällige
     * Positionen gezogen; nur Blöcke mit {@link BlockState#ticksRandomly()} reagieren
     * (Pflanzenwachstum, Verfall). Begrenzt auf die Simulations-Distanz um den Spieler.
     */
    private void tickRandomBlocks() {
        if (RANDOM_TICK_SPEED <= 0 || !BlockRegistry.hasRandomTickBlocks()) return;
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
            if (!this.isSimulated(chunk.chunkX, chunk.chunkZ)) continue;
            int baseX = chunk.chunkX << ChunkSection.SHIFT;
            int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
            for (int si = 0; si < Chunk.SECTIONS; si++) {
                ChunkSection section = chunk.getSection(si);
                if (section == null || section.isEmpty()) continue;
                int baseY = si << ChunkSection.SHIFT;
                for (int n = 0; n < RANDOM_TICK_SPEED; n++) {
                    int lx = this.random.nextInt(ChunkSection.SIZE);
                    int ly = this.random.nextInt(ChunkSection.SIZE);
                    int lz = this.random.nextInt(ChunkSection.SIZE);
                    int id = section.getBlock(lx, ly, lz);
                    if (id == Blocks.AIR) continue;
                    BlockState state = Blocks.getState(id);
                    if (!state.ticksRandomly()) continue;
                    state.getBlock().randomTick(this, baseX + lx, baseY + ly, baseZ + lz, state);
                }
            }
        }
    }

    /**
     * Der Spieler, falls er sich innerhalb von {@code maxDist} (3D) um (x,y,z) befindet, sonst null.
     * Aktuell ein einziger Spieler; bei mehreren später den nächsten wählen.
     */
    public EntityPlayer getNearestPlayer(double x, double y, double z, double maxDist) {
        if (this.player == null) return null;
        double dx = this.player.x - x;
        double dy = this.player.y - y;
        double dz = this.player.z - z;
        return dx * dx + dy * dy + dz * dz <= maxDist * maxDist ? this.player : null;
    }

    /** BlockEntity an Weltkoordinaten oder null. */
    public BlockEntity getBlockEntity(int x, int y, int z) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return null;
        return chunk.getBlockEntity(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    public void render(Camera camera, float partialTick) {
        this.chunkManager.processRemeshes();
        /* Entities VOR dem Translucent-Pass (Vanilla-Reihenfolge): Wasser blendet über
           Items/BlockEntities, statt sie hinter sich unsichtbar zu machen. */
        this.chunkRenderer.renderSolid(camera);
        this.blockEntityRenderer.render(this.chunkManager, camera, partialTick);
        this.entityRenderer.render(this.chunkManager, camera, partialTick);
        this.chunkRenderer.renderTranslucent(camera);
    }

    @Override
    public void dispose() {
        this.entityRenderer.dispose();
        this.blockEntityRenderer.dispose();
        this.chunkRenderer.dispose();
        this.chunkManager.dispose();
    }

    /** Block an Weltkoordinaten. Ungeladene Chunks zählen als Luft. */
    public int getBlock(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        /* Erst ab DECORATED lesen: während GENERATING/DECORATING schreiben Worker lock-frei
           (Generator/FeaturePlacer) — ein Read würde mit dem PalettedContainer-Wachstum racen
           (torn reads: falsche State-IDs, im Grenzfall AIOOBE). */
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.DECORATED)) {
            return Blocks.AIR;
        }
        return chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    /** Setzt einen Block (mit Nachbar-Updates für Verbindungen/Treppen-Ecken). */
    public void setBlock(int x, int y, int z, int block) {
        this.setBlock(x, y, z, block, true);
    }

    /**
     * @param updateNeighbors true: betroffene Nachbarn (Zäune, Panes, Treppen)
     *                        rechnen ihren State neu. false vermeidet Rekursion
     *                        bei den dadurch ausgelösten Folge-Updates.
     */
    public void setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
        int old = this.getBlock(x, y, z);
        if (!this.setBlockRaw(x, y, z, block)) return;
        this.manageBlockEntity(x, y, z, old, block);
        if (updateNeighbors) this.updateNeighbors(x, y, z);
    }

    /**
     * Platziert einen fertig berechneten Placement-State: setzt den Block (ohne Kaskade),
     * lässt den Block etwaige Mehrteil-Logik anwenden (z.B. obere Türhälfte über
     * {@link de.skyengine.game.world.block.Block#onPlaced}) und löst ERST DANACH die
     * Nachbar-Updates aus. Das Ordering ist entscheidend - sonst entfernt sich z.B. die
     * untere Türhälfte selbst, bevor die obere existiert.
     */
    public void placeBlock(int x, int y, int z, BlockState state) {
        this.setBlock(x, y, z, state.getId(), false);
        state.getBlock().onPlaced(this, x, y, z, state);
        this.updateNeighbors(x, y, z);
    }

    /**
     * Legt die BlockEntity an oder entfernt sie, wenn sich der BlockEntity-Typ ändert.
     * Reine State-Änderungen am selben Block (Verbindungen, Treppen-Ecken) lassen die
     * vorhandene BlockEntity unberührt.
     */
    private void manageBlockEntity(int x, int y, int z, int oldId, int newId) {
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
    private boolean setBlockRaw(int x, int y, int z, int block) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;

        int cx = x >> ChunkSection.SHIFT;
        int cz = z >> ChunkSection.SHIFT;

        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        /* Nur fertige Chunks editieren - vermeidet Races mit laufenden Mesh-Jobs */
        if (chunk == null || chunk.status != ChunkStatus.READY) return false;

        int lx = x & ChunkSection.MASK;
        int lz = z & ChunkSection.MASK;

        int sy = y >> ChunkSection.SHIFT;

        /* Write-Lock: serialisiert gegen laufende Worker-Mesh-Reads desselben Chunks. */
        chunk.writeLock().lock();
        try {
            chunk.setBlock(lx, y, lz, block);
        } finally {
            chunk.writeLock().unlock();
        }
        chunk.markSectionDirty(sy);

        /* Vertikale Section-Grenzen */
        if ((y & ChunkSection.MASK) == 0 && sy > 0) chunk.markSectionDirty(sy - 1);
        if ((y & ChunkSection.MASK) == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) chunk.markSectionDirty(sy + 1);


        /* An Chunk-Grenzen muss der Nachbar mit-remeshen, sonst bleiben dort falsche Faces */
        if (lx == 0) this.markDirty(cx - 1, cz, sy);
        if (lx == ChunkSection.MASK) this.markDirty(cx + 1, cz, sy);
        if (lz == 0) this.markDirty(cx, cz - 1, sy);
        if (lz == ChunkSection.MASK) this.markDirty(cx, cz + 1, sy);
        /* Chunk-ECKEN zusätzlich diagonal: dessen Fluid-Eckhöhen sampeln diese Zelle. */
        if (lx == 0 && lz == 0) this.markDirty(cx - 1, cz - 1, sy);
        if (lx == 0 && lz == ChunkSection.MASK) this.markDirty(cx - 1, cz + 1, sy);
        if (lx == ChunkSection.MASK && lz == 0) this.markDirty(cx + 1, cz - 1, sy);
        if (lx == ChunkSection.MASK && lz == ChunkSection.MASK) this.markDirty(cx + 1, cz + 1, sy);
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
        int id = this.getBlock(x, y, z);
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
        /* Unfertig für Kollision = ungeladen ODER Status < DECORATED (dort schreiben Worker noch
           lock-frei, s. getBlock -> nicht lesen). Solche Zellen zählen als solide (Boden-Schutz
           beim Laden). Fliegend aber Luft — sonst klebt man an einer unsichtbaren Wand am Rand
           (z.B. mit pausiertem Chunk-Loading, wo Frontier-Chunks unter DECORATED einfrieren).
           Beide Zweige geben eine Konstante zurück, ohne den Chunk zu lesen. Betrifft praktisch
           nur den Spieler: andere Entities leben nur in geladenen Chunks. */
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.DECORATED)) {
            return this.player != null && this.player.isFlying() ? BlockShape.EMPTY : BlockShape.FULL_CUBE;
        }

        int id = chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
        return Blocks.getState(id).getCollisionShape();
    }

    /**
     * Kollisionsabfrage. Ungeladene/ungenerierte Chunks (inkl. Status < DECORATED) zählen als
     * SOLIDE, damit der Spieler beim Laden der Welt nicht durch den Boden fällt — außer er
     * fliegt, dann Luft (sonst unsichtbare Wand am Rand, s. {@link #getCollisionShape}).
     */
    public boolean isBlockSolidForCollision(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        /* Unfertig für Kollision = ungeladen ODER < DECORATED (Worker schreiben noch lock-frei
           -> nicht lesen). Solide, außer der Spieler fliegt. Beide Zweige ohne Chunk-Read. */
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.DECORATED)) {
            return !(this.player != null && this.player.isFlying());
        }

        return Blocks.isSolid(chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK));
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public ChunkRenderer getChunkRenderer() {
        return chunkRenderer;
    }
}
