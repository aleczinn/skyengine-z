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
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.tick.ScheduledTickQueue;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.ChestRenderer;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.entity.EntityRenderer;
import de.skyengine.graphics.world.ChunkRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class World implements IInitializable, IDisposable {

    private final String name;

    private final WorldGenerator generator;
    private final ChunkManager chunkManager;
    private final ChunkRenderer chunkRenderer;
    private final BlockEntityRenderDispatcher blockEntityRenderer = new BlockEntityRenderDispatcher();
    private final EntityRenderer entityRenderer = new EntityRenderer();

    /** Aktive Welt-Entities (Nicht-Player: fallende Blöcke, gedroppte Items). */
    private final List<Entity> entities = new ArrayList<>();
    /** Reentranzsicherer Puffer: Spawns aus einem laufenden Tick werden erst danach übernommen. */
    private final List<Entity> pendingEntities = new ArrayList<>();

    /** Wiederverwendeter Snapshot-Puffer fürs BlockEntity-Ticking (keine Allokation pro Chunk/Tick). */
    private final List<BlockEntity> tickScratch = new ArrayList<>();

    /** Spielzeit in Ticks (20 TPS), bei jedem update() erhöht - Basis für geplante Ticks. */
    private long gameTime;
    private final Random random = new Random();
    private final ScheduledTickQueue scheduledTicks = new ScheduledTickQueue();

    /** Zufalls-Ticks pro nicht-leerer Section pro Tick (Wachstum, Verfall). 0 = aus. */
    private static final int RANDOM_TICK_SPEED = 3;

    public World(String name) {
        this.name = name;
        this.generator = new WorldGenerator(123);
        this.chunkManager = new ChunkManager(this.generator);
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
        this.blockEntityRenderer.register(BlockEntities.CHEST, new ChestRenderer());
        this.blockEntityRenderer.init();
        this.entityRenderer.init(this.chunkRenderer.getTextureArray());
    }

    public void update(Input input, EntityPlayer player) {
        this.gameTime++;
        this.chunkManager.update(player);
        this.tickScheduled();
        this.tickRandomBlocks();
        this.tickBlockEntities();
        this.tickEntities();
    }

    /**
     * Tickt alle Welt-Entities: zuerst gepufferte Spawns übernehmen, dann ticken (ein Tick darf
     * neue Entities spawnen -> landen im Puffer, kommen nächsten Tick dran), zuletzt entfernte
     * aussortieren.
     */
    private void tickEntities() {
        if (!this.pendingEntities.isEmpty()) {
            this.entities.addAll(this.pendingEntities);
            this.pendingEntities.clear();
        }
        for (int i = 0; i < this.entities.size(); i++) {
            this.entities.get(i).tick(this);
        }
        this.entities.removeIf(Entity::isRemoved);
    }

    /** Reiht eine Entity zum Spawnen ein (Übernahme im nächsten {@link #tickEntities}). */
    public void spawnEntity(Entity entity) {
        this.pendingEntities.add(entity);
    }

    /** Spawnt einen flüssig fallenden Block an der Blockposition (Fußpunkt = y, zentriert in x/z). */
    public void spawnFallingBlock(int x, int y, int z, short blockId) {
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

    public List<Entity> getEntities() {
        return this.entities;
    }

    /** Tickt alle tickenden BlockEntities geladener Chunks (Maschinen, Pipes, ...). */
    private void tickBlockEntities() {
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
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

    /** true, wenn an der Position bereits ein geplanter Tick aussteht. */
    public boolean isTickScheduled(int x, int y, int z) {
        return this.scheduledTicks.isScheduled(x, y, z);
    }

    /** Aktuelle Spielzeit in Ticks (20 TPS). */
    public long getGameTime() {
        return this.gameTime;
    }

    /** Führt alle fälligen geplanten Ticks aus (Fluss-Ausbreitung, Fallprüfung, ...). */
    private void tickScheduled() {
        this.scheduledTicks.drainDue(this.gameTime, (x, y, z) -> {
            BlockState state = Blocks.getState(this.getBlock(x, y, z));
            if (!state.isAir()) state.getBlock().scheduledTick(this, x, y, z, state);
        });
    }

    /**
     * Zufalls-Ticks: pro nicht-leerer Section werden {@link #RANDOM_TICK_SPEED} zufällige
     * Positionen gezogen; nur Blöcke mit {@link BlockState#ticksRandomly()} reagieren
     * (Pflanzenwachstum, Verfall). Läuft über alle geladenen Chunks - später ggf. auf eine
     * Simulationsdistanz um den Spieler begrenzen.
     */
    private void tickRandomBlocks() {
        if (RANDOM_TICK_SPEED <= 0 || !BlockRegistry.hasRandomTickBlocks()) return;
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
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
                    short id = section.getBlock(lx, ly, lz);
                    if (id == Blocks.AIR) continue;
                    BlockState state = Blocks.getState(id);
                    if (!state.ticksRandomly()) continue;
                    state.getBlock().randomTick(this, baseX + lx, baseY + ly, baseZ + lz, state);
                }
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
        this.blockEntityRenderer.render(this.chunkManager, camera, partialTick);
        this.entityRenderer.render(this.entities, camera, partialTick);
    }

    @Override
    public void dispose() {
        this.entityRenderer.dispose();
        this.blockEntityRenderer.dispose();
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
