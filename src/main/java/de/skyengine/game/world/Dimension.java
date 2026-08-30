package de.skyengine.game.world;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.entity.PrimedTntEntity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.entity.PortableBlockEntity;
import de.skyengine.game.world.block.network.EnergyNetworkManager;
import de.skyengine.game.world.block.behavior.WorldScopedPositionMap;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.game.world.debug.SimulationTelemetry;
import de.skyengine.game.world.dimension.*;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.Feature;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.game.world.light.LightEngine;
import de.skyengine.game.world.lod.LodBlockAppearance;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.lod.PersistentLodDataSource;
import de.skyengine.game.world.particle.ParticleEngine;
import de.skyengine.game.world.particle.ParticlePriority;
import de.skyengine.game.world.redstone.RedstonePower;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickQueue;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.game.world.tick.TickPriority;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.collect.LongObjMap;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public class Dimension implements IInitializable, IDisposable {

    private static final int ANIMATE_TICK_SAMPLES = 667;
    private static final int ANIMATE_TICK_NEAR_RADIUS = 16;
    private static final int ANIMATE_TICK_FAR_RADIUS = 32;

    private final Logger logger = LogManager.getLogger(Dimension.class.getName());
    private final EnergyNetworkManager energyNetworks = new EnergyNetworkManager(this);
    /* Synchronous player action scope. Nested block behavior mutations inherit the origin. */
    private int playerBlockChangeDepth;
    /** True while a piston replaces a complete structure by moving-piston placeholders. */
    private int pistonBlockMoveDepth;

    public boolean runPlayerBlockChange(BooleanSupplier action) {
        this.playerBlockChangeDepth++;
        try {
            return action.getAsBoolean();
        } finally {
            this.playerBlockChangeDepth--;
        }
    }

    private final String name;
    private final Identifier dimensionId;
    private final boolean lodAllowed;
    private final DimensionEnvironment environment;

    private final WorldGenerator generator;
    private final ChunkDecorator decorator;
    private final ChunkManager chunkManager;
    /* Chunk-Persistenz (Region-Dateien + eigener IO-Thread); Flush in dispose(). */
    private final WorldStorage storage;
    /* worldType "imported" — steuert u.a. die LOD-Datenquelle (Storage statt Generator). */
    private final boolean imported;
    /* Mehrschichtiges Spalten-LOD jenseits der Render-Distanz; init() braucht gebackene Modelle. */
    private LodManager lodManager;
    private PersistentLodDataSource persistentLodSource;
    private final int generatorVersion;
    private final LevelData levelData;
    private final LevelData.DimensionData dimensionData;
    private final File lodDirectory;
    private final PortalIndex portalIndex;
    private final PortalLinks portalLinks;
    /* Engine-Lebensdauer (GameContainer): Atlas + BlockEntity-Renderer überleben Welt-Austritte —
       die Welt hält nur Referenzen und disposed sie NICHT. */
    private final ParticleEngine particles;
    /* Engine-Lebensdauer (GameContainer): für Sounds aus der Welt-Logik (z.B. TNT-Explosion). Nullable. */
    private SoundManager soundManager;

    /** Reentranzsicherer Puffer: Spawns aus einem laufenden Tick werden erst danach in den Chunk übernommen. */
    private final List<Entity> pendingEntities = new ArrayList<>();
    /** Zwischenpuffer für Entities, die in diesem Tick ihren Chunk wechseln (Umhängen nach dem Reconcile). */
    private final List<Entity> transferBuffer = new ArrayList<>();

    /** Nur Chunks mit mindestens einer Entity — spart dem Renderer die Iteration über ALLE geladenen
        Chunks pro Frame (Entities sind selten). Gepflegt in {@link #addToChunk} (add) und
        {@link #reconcileEntityChunks} (Pruning). Nur auf dem Render/Tick-Thread berührt (identisch). */
    private final Set<Chunk> chunksWithEntities = new LinkedHashSet<>();

    /** Wiederverwendeter Snapshot-Puffer fürs BlockEntity-Ticking (keine Allokation pro Chunk/Tick). */
    private final List<BlockEntity> tickScratch = new ArrayList<>();
    /** READY-Meldungen werden vor zustandsändernden Load-Reconciliations nach Koordinate sortiert. */
    private final List<Chunk> readyChunkScratch = new ArrayList<>();
    /** Reguläre Unloads werden vor dem Redstone-Kantenabgleich nach Koordinate sortiert. */
    private final List<Long> unloadedChunkScratch = new ArrayList<>();
    /** Koordinaten dieses Unload-Batches, die nicht schon wieder einem Ersatz-Chunk gehören. */
    private final LongIntMap unloadedChunkKeys = new LongIntMap(64);

    /* Nachhol-Protokoll für Block-State-Updates. Die FIFO enthält ausschließlich Updates für
       den nächsten Tick; nicht-READY Ziele liegen separat am konkreten Chunk-Objekt. Dadurch
       werden unfertige Chunks nicht jeden Tick erneut gescannt und ein entladener/ersetzter
       Chunk kann keine Altlast auf seinen Nachfolger übertragen. Nur Tick-/Render-Thread. */
    private static final int MAX_DEFERRED_STATE_UPDATES = 4096;
    private static final int MAX_DEFERRED_STATE_UPDATES_PER_TICK = 512;
    private final LinkedHashMap<Long, Chunk> deferredStateUpdates = new LinkedHashMap<>();
    private final LongObjMap<DeferredChunkUpdates> parkedStateUpdates = new LongObjMap<>(32);
    private int parkedStateUpdateCount;
    private int deferredPruneRemovalVersion;
    private long[] deferredScratch = new long[0];
    private Chunk[] deferredChunkScratch = new Chunk[0];

    /* Block-Event-Queue (MCs Block-Events, heute nur Kolben): ServerLevel.runBlockEvents läuft
       genau einmal nach den Block-/Fluidticks und vor Entities/BlockEntities. Innerhalb dieses
       Drains läuft die Queue BIS sie leer ist; Events aus späteren BE-Ticks warten bis zum
       nächsten Welttick.
       LinkedHashSet = Vanillas vollständige Event-Deduplizierung + FIFO + stabile Block-Bindung.
       Nicht tickende Chunks wandern mit vollständigen Eventdaten in Vanillas zweite Menge. */
    private static final int BLOCK_EVENT_TICK_PRIORITY = Integer.MAX_VALUE;
    private final LinkedHashSet<BlockEvent> blockEvents = new LinkedHashSet<>();
    /** Vanillas blockEventsToReschedule: außerhalb der Simulation unverändert bis zum Folgetick. */
    private final LinkedHashSet<BlockEvent> blockEventsToReschedule = new LinkedHashSet<>();
    private long blockEventRescheduleGameTime = Long.MIN_VALUE;
    private long blockEventRevision;

    /** Der Spieler dieses Ticks (für BlockEntities, die ihn brauchen, z.B. das Zaubertisch-Buch). */
    private EntityPlayer player;

    /** Spielzeit in Ticks (20 TPS), bei jedem update() erhöht - Basis für geplante Ticks. */
    private long gameTime;
    /** Vanillas ServerLevel#isHandlingTick; u. a. Teil der Sticky-Piston-Drop-Regel. */
    private boolean handlingTick;
    private final Random random = new Random();
    /* Kosmetische Zufallsfolge getrennt von der Simulation: Audio darf Fluid-Timing nicht ändern. */
    private final Random animateCoordinateRandom = new Random();
    private final Random animateEffectRandom = new Random();
    /** Weltgebundener RNG für Drops und seltene Gameplay-Ereignisse; nur Tick-Thread. */
    public Random random() { return this.random; }
    private final java.util.Map<String, LootRandom> lootRandoms = new java.util.HashMap<>();
    private final int lootSeed;
    private boolean tntExplosionDropDecay;

    /** Benannte, weltgebundene Zufallsfolge einer Loot-Tabelle. */
    public Random lootRandom(String sequence) {
        return this.lootRandoms.computeIfAbsent(sequence,
                key -> new LootRandom((((long) this.lootSeed) << 32) ^ key.hashCode(), false));
    }

    public void saveRuntimeState() {
        if (this.dimensionData.lootRandomStates == null) {
            this.dimensionData.lootRandomStates = new java.util.LinkedHashMap<>();
        }
        this.dimensionData.lootRandomStates.clear();
        for (Map.Entry<String, LootRandom> entry : this.lootRandoms.entrySet()) {
            this.dimensionData.lootRandomStates.put(entry.getKey(), entry.getValue().state());
        }
        this.levelData.tntExplosionDropDecay = this.tntExplosionDropDecay;
        if (this.dimensionId.equals(
                de.skyengine.game.world.dimension.WorldgenRegistries.OVERWORLD)
                && this.levelData.lootRandomStates != null) {
            this.levelData.lootRandomStates.clear();
        }
    }

    /** Kompatibilitaet fuer bestehende Aufrufer; der Zustand landet dimensionslokal. */
    public void saveLootRandomStates(LevelData ignored) {
        this.saveRuntimeState();
    }

    public boolean tntExplosionDropDecay() { return this.tntExplosionDropDecay; }
    public void setTntExplosionDropDecay(boolean enabled) { this.tntExplosionDropDecay = enabled; }

    /** Persistierbarer LCG ohne Synchronisation; Loot läuft ausschließlich im Tick-Thread. */
    private static final class LootRandom extends Random {
        private static final long MASK = (1L << 48) - 1;
        private long state;

        LootRandom(long seed, boolean rawState) {
            super(0L);
            this.state = rawState ? seed & MASK : (seed ^ 0x5DEECE66DL) & MASK;
        }

        @Override
        protected int next(int bits) {
            this.state = (this.state * 0x5DEECE66DL + 0xBL) & MASK;
            return (int) (this.state >>> (48 - bits));
        }

        long state() { return this.state; }
    }
    private final ScheduledTickQueue scheduledTicks = new ScheduledTickQueue();
    private final SimulationTelemetry simulationTelemetry = new SimulationTelemetry();
    /** Debug-Reload: vor dem Clear müssen ältere Saves fertig sein; danach warten neue Loads. */
    private boolean chunkReloadRequested;
    private boolean chunkReloadWaitingForSaves;
    /** Weltgebundene Behavior-Speicher, die nach Chunk-Entfernungen ihre Objektidentitäten prüfen. */
    private final Set<WorldScopedPositionMap<?>> transientPositionStates =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private int transientStateRemovalVersion;

    /* Himmelslicht-Aktualisierung bei Block-Edits. Eigene Engine-Instanz für den
       Render-/Tick-Thread (die Worker haben ihre ThreadLocals im ChunkManager) — eine Instanz
       ist nicht threadsicher. lightDiagonals ist ein wiederverwendeter Übergabepuffer. */
    private final LightEngine lightEngine;
    private final Chunk[] lightDiagonals = new Chunk[4];

    /** Zufalls-Ticks pro nicht-leerer Section pro Tick (Wachstum, Verfall). 0 = aus.
     *  24 = MC-Parität: Vanilla zieht 3 je 16³-Subchunk, unsere Sections sind 32³
     *  (= 8 Subchunks) — der alte Wert 3 ließ Pflanzen ~8x langsamer wachsen. */
    private static final int RANDOM_TICK_SPEED = 24;

    /* Eigener Generator für die Zufalls-Ticks: ~75k Ziehungen/s — java.util.Random wäre
       ein CAS-Loop pro nextInt. this.random bleibt für die seltenen Nutzer (Drops/Spawns). */
    private final SplittableRandom randomTick = new SplittableRandom();

    /** Notfallbudget gegen einen einzelnen Tick mit massenhaft gleichzeitig fälligen Block-Ticks. */
    /** ServerLevel.MAX_SCHEDULED_TICKS_PER_TICK in Vanilla 26.2. */
    private static final int MAX_SCHEDULED_TICKS_PER_TICK = 65_536;

    /** Nur Chunks in diesem Radius (in Chunks) um den Spieler ticken (Random/Scheduled/Entities). */
    private int simulationDistance = 10;
    /* Spieler-Chunk des laufenden Ticks - Basis für isSimulated(). */
    private int playerChunkX, playerChunkZ;

    public Dimension(String dirName, LevelData level, BlockTextureAtlas atlas,
                 BlockEntityRenderDispatcher blockEntityRenderer) {
        this(dirName, level, de.skyengine.game.world.dimension.WorldgenRegistries.OVERWORLD,
                atlas, blockEntityRenderer);
    }

    public Dimension(String dirName, LevelData level, Identifier dimensionId, BlockTextureAtlas atlas,
                 BlockEntityRenderDispatcher blockEntityRenderer) {
        this(dirName, level, dimensionId, new File(GameDirectory.resolve("saves"), dirName),
                null, null);
    }

    Dimension(String dirName, LevelData level, Identifier dimensionId, File saveRoot,
                      WorldWorkerPool workerPool,
                      de.skyengine.game.world.dimension.PortalLinks portalLinks) {
        this(dirName, level, dimensionId, saveRoot,
                DimensionSaves.resolve(saveRoot, level, dimensionId), workerPool, portalLinks, null);
    }

    Dimension(String dirName, LevelData level, Identifier dimensionId, File saveRoot,
              WorldWorkerPool workerPool, de.skyengine.game.world.dimension.PortalLinks portalLinks,
              de.skyengine.game.world.structure.StructureTemplateManager.Snapshot structures) {
        this(dirName, level, dimensionId, saveRoot,
                DimensionSaves.resolve(saveRoot, level, dimensionId), workerPool, portalLinks, structures);
    }

    private Dimension(String dirName, LevelData level, Identifier dimensionId,
                  File saveRoot, DimensionSaves.Resolved resolved, WorldWorkerPool workerPool,
                  de.skyengine.game.world.dimension.PortalLinks portalLinks,
                  de.skyengine.game.world.structure.StructureTemplateManager.Snapshot structures) {
        this.name = dirName;
        this.levelData = level;
        this.dimensionData = resolved.data();
        this.dimensionId = dimensionId;
        this.lodAllowed = resolved.dimension().lodAllowed();
        this.environment = resolved.dimension().environment();
        this.lightEngine = new LightEngine(this.environment.hasSkylight());
        this.particles = new ParticleEngine(this);
        this.lootSeed = level.seed;
        this.tntExplosionDropDecay = Boolean.TRUE.equals(level.tntExplosionDropDecay);
        java.util.Map<String, Long> savedLootRandomStates = this.dimensionData.lootRandomStates;
        /* Format <= 2 speicherte diese Daten global. Sie gehoeren zur Overworld und werden
           beim naechsten Speichern in deren DimensionData migriert. */
        if ((savedLootRandomStates == null || savedLootRandomStates.isEmpty())
                && dimensionId.equals(de.skyengine.game.world.dimension.WorldgenRegistries.OVERWORLD)) {
            savedLootRandomStates = level.lootRandomStates;
        }
        if (savedLootRandomStates != null) {
            for (Map.Entry<String, Long> entry : savedLootRandomStates.entrySet()) {
                this.lootRandoms.put(entry.getKey(), new LootRandom(entry.getValue(), true));
            }
        }

        GenerationSetup setup = resolved.generator().create(resolved.data().seed);
        this.generator = setup.generator();
        List<Feature> sessionFeatures = structures == null ? setup.features() : setup.features().stream().map(feature -> feature.withStructures(structures)).toList();
        this.decorator = new ChunkDecorator(this.generator, sessionFeatures);
        this.chunkManager = workerPool == null
                ? new ChunkManager(this.generator, this.decorator, this.environment.hasSkylight())
                : new ChunkManager(this.generator, this.decorator, workerPool,
                this.environment.hasSkylight());
        this.imported = setup.storageMode() == GenerationSetup.StorageMode.IMPORTED;
        if (resolved.data().generatorVersion != null
                && resolved.data().generatorVersion != resolved.generator().version()) {
            this.logger.warning("Dimension " + dimensionId + " wurde mit Generator-Version "
                    + resolved.data().generatorVersion + " erstellt, Engine hat Version "
                    + resolved.generator().version()
                    + " — ungespeicherte Gegenden können sich ändern (Nähte möglich)");
        }

        /* Chunk-Persistenz: Snapshots liegen in saves/<dir>/region; generierte Welten
           speichern nur modifizierte Chunks (Tints werden beim Laden neu berechnet),
           importierte alle (Tints im Payload). */
        this.generatorVersion = resolved.generator().version();
        this.lodDirectory = resolved.lodDir();
        this.portalIndex = new de.skyengine.game.world.dimension.PortalIndex(resolved.root());
        this.portalLinks = portalLinks == null
                ? new de.skyengine.game.world.dimension.PortalLinks(saveRoot) : portalLinks;
        this.storage = new WorldStorage(resolved.regionDir(), this, this.generator,
                resolved.generator().id().toString(), this.generatorVersion, this.imported);
        this.chunkManager.setStorage(this.storage);
    }

    public String getName() {
        return name;
    }

    public Identifier getDimensionId() {
        return this.dimensionId;
    }

    public boolean isLodAllowed() {
        return this.lodAllowed;
    }

    public de.skyengine.game.world.dimension.DimensionEnvironment getEnvironment() {
        return this.environment;
    }

    public de.skyengine.game.world.dimension.PortalIndex getPortalIndex() {
        return this.portalIndex;
    }

    public de.skyengine.game.world.dimension.PortalLinks getPortalLinks() {
        return this.portalLinks;
    }

    /** Injiziert der GameContainer nach der Welt-Erzeugung; erlaubt Sounds aus der Welt-Logik. */
    public void setSoundManager(SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    /** SoundManager der Welt oder {@code null} (dann bleiben Welt-Sounds stumm). */
    public SoundManager getSoundManager() {
        return this.soundManager;
    }

    public ParticleEngine particles() {
        return this.particles;
    }

    public EntityPlayer getPlayer() {
        return this.player;
    }

    /** Aktualisiert positionsgebundene Entity-Sounds unabhängig vom Frustum-Culling. */
    public void updateEntitySounds(float partialTick) {
        if (this.soundManager == null) return;
        this.soundManager.beginMinecartSounds();
        for (Chunk chunk : this.chunksWithEntities) {
            if (chunk.status != ChunkStatus.READY) continue;
            for (Entity entity : chunk.entities()) {
                if (!(entity instanceof MinecartEntity minecart) || minecart.isRemoved()) continue;
                double x = minecart.lastX + (minecart.x - minecart.lastX) * partialTick;
                double y = minecart.lastY + (minecart.y - minecart.lastY) * partialTick;
                double z = minecart.lastZ + (minecart.z - minecart.lastZ) * partialTick;
                double speed = Math.sqrt(minecart.motionX * minecart.motionX
                        + minecart.motionZ * minecart.motionZ);
                this.soundManager.updateMinecartSound(minecart, x, y, z, speed,
                        this.player != null && this.player.getVehicle() == minecart);
            }
        }
        this.soundManager.endMinecartSounds();
    }

    public void playDispenserSuccess(int x, int y, int z) {
        if (this.soundManager != null) this.soundManager.playDispenserSuccess(x + 0.5, y + 0.5, z + 0.5);
    }

    public void playDispenserFailure(int x, int y, int z) {
        if (this.soundManager != null) this.soundManager.playDispenserFailure(x + 0.5, y + 0.5, z + 0.5);
    }

    public void playBucketEmpty(int x, int y, int z, boolean lava) {
        if (this.soundManager != null) this.soundManager.playBucketEmpty(lava, x + 0.5, y + 0.5, z + 0.5);
    }

    public void playBucketFill(int x, int y, int z, boolean lava) {
        if (this.soundManager != null) this.soundManager.playBucketFill(lava, x + 0.5, y + 0.5, z + 0.5);
    }

    public void playFluidExtinguish(int x, int y, int z) {
        this.particles.fluidReaction(x + 0.5, y + 0.5, z + 0.5);
        if (this.soundManager != null) this.soundManager.playFluidExtinguish(x + 0.5, y + 0.5, z + 0.5);
    }

    public void playWaterAmbient(int x, int y, int z) {
        if (this.soundManager != null) this.soundManager.playWaterAmbient(x + 0.5, y + 0.5, z + 0.5);
    }

    public void playLavaAmbient(int x, int y, int z) {
        if (this.soundManager != null) this.soundManager.playLavaAmbient(x + 0.5, y + 0.5, z + 0.5);
    }

    public void playLavaPop(int x, int y, int z) {
        double px = x + this.animateEffectRandom.nextDouble();
        double py = y + 1.0;
        double pz = z + this.animateEffectRandom.nextDouble();
        this.particles.lavaPop(px, py, pz);
        if (this.soundManager != null) this.soundManager.playLavaPop(px, py, pz);
    }

    @Override
    public void init() {
        /* Reine Savegame-/Simulationslaufzeit; die Clientansicht wird separat erzeugt. */
    }

    public LodManager initClientLod() {
        if (this.lodManager != null) return this.lodManager;
        /* LOD: abstrahierte Datenquelle + Block-Darstellung aus den gebackenen Modellen —
           erst nach dem Registry-Bake. Importierte Welten sampeln die Region-Snapshots
           (der Void-Generator kennt kein Terrain), generierte wie bisher Chunkdaten +
           Generator-Noise. */
        this.persistentLodSource = new PersistentLodDataSource(this.chunkManager, this.storage,
                this.generator, this.decorator, this.imported,
                this.lodDirectory, this.generatorVersion);
        this.lodManager = new LodManager(this.persistentLodSource, new LodBlockAppearance(), this.chunkManager,
                this.lodAllowed);
        this.chunkManager.setLodManager(this.lodManager); // Unload-Gate: erst entladen, wenn LOD deckt
        return this.lodManager;
    }

    public void disposeClientLod() {
        this.chunkManager.setLodManager(null);
        this.lodManager = null;
        if (this.persistentLodSource != null) this.persistentLodSource.close();
        this.persistentLodSource = null;
    }

    public LodManager getLodManager() {
        return this.lodManager;
    }

    public Iterable<Chunk> entityChunks() {
        return this.chunksWithEntities;
    }

    public void update(Input input, EntityPlayer player) {
        this.handlingTick = true;
        try {
            this.updateWhileHandlingTick(input, player);
        } finally {
            this.handlingTick = false;
        }
    }

    private void updateWhileHandlingTick(Input input, EntityPlayer player) {
        PerformanceProfiler profiler = PerformanceProfiler.get();
        long tickStarted = profiler.begin();
        long attributed = 0;
        this.simulationTelemetry.setEnabled(FrameProfiler.isEnabled());
        this.simulationTelemetry.beginTick();
        this.gameTime++;
        this.player = player;
        this.playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        if (this.processChunkReload()) {
            FrameProfiler.reset();
            this.simulationTelemetry.endTick();
            return;
        }
        long phase = profiler.begin();
        this.chunkManager.update(player);
        this.pruneTransientPositionStates();
        if (this.lodManager != null) this.lodManager.update(player);
        this.processUnloadedChunkBoundaries();
        this.restorePendingScheduledTicks();
        this.processReadyChunks();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.CHUNK_LOD_MANAGEMENT, phase);
        phase = profiler.begin();
        this.processDeferredStateUpdates();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.DEFERRED_STATE_UPDATES, phase);
        phase = profiler.begin();
        this.tickScheduled();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.SCHEDULED_TICKS, phase);
        phase = profiler.begin();
        this.tickRandomBlocks();
        this.tickAnimateBlocks();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.RANDOM_TICKS, phase);
        phase = profiler.begin();
        /* ServerLevel.tick: Random-/Chunk-Ticks, dann der einzige Blockevent-Drain. */
        this.processBlockEvents();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.BLOCK_EVENTS, phase);
        /* Vanilla beendet isHandlingTick unmittelbar NACH runBlockEvents und noch VOR
           Entity-/BlockEntity-Ticks. Bleibt das Flag waehrend eines Moving-Piston-Finishs
           wahr, waehlt PistonBaseBlock bei jeder Gegenflanke faelschlich TRIGGER_DROP. */
        this.handlingTick = false;
        phase = profiler.begin();
        this.pushMinecartsByPlayer(player);
        this.tickEntities();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.ENTITY_TICKS, phase);
        phase = profiler.begin();
        this.tickBlockEntities();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.BLOCK_ENTITY_TICKS, phase);
        phase = profiler.begin();
        this.energyNetworks.tick(this.playerChunkX, this.playerChunkZ);
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.ENERGY_NETWORKS, phase);
        phase = profiler.begin();
        this.particles.tick();
        attributed += recordTickPhase(profiler, PerformanceProfiler.TickSection.PARTICLE_TICKS, phase);
        profiler.set(PerformanceProfiler.Counter.ACTIVE_PARTICLES, this.particles.count());
        profiler.set(PerformanceProfiler.Counter.REJECTED_PARTICLES, this.particles.rejected());
        this.simulationTelemetry.endTick();
        if (tickStarted != 0 && profiler.isEnabled()) {
            long total = System.nanoTime() - tickStarted;
            profiler.record(PerformanceProfiler.TickSection.TOTAL, total);
            profiler.record(PerformanceProfiler.TickSection.REST, Math.max(0, total - attributed));
        }
    }

    private static long recordTickPhase(PerformanceProfiler profiler,
                                        PerformanceProfiler.TickSection section, long started) {
        if (started == 0 || !profiler.isEnabled()) return 0;
        long elapsed = System.nanoTime() - started;
        profiler.record(section, elapsed);
        return elapsed;
    }

    /** Minecarts suchen in Vanilla vor ihrer Bewegung Entities in einer um 0,2 verbreiterten Box. */
    private void pushMinecartsByPlayer(EntityPlayer player) {
        this.forEachEntityNearby(player.x, player.z, 1, entity -> {
            if (entity instanceof MinecartEntity minecart
                    && minecart.getBoundingBox().copy().inflate(0.2, 0, 0.2)
                    .intersects(player.getBoundingBox())) {
                minecart.pushFrom(player);
            }
        });
    }

    /**
     * Plant die beim Chunk-Load übergebenen Scheduled-Ticks ein (sonst stünde z.B. frisch
     * geladenes Wasser für immer). Erst ab READY — vorher würde der Tick feuern, bevor
     * {@code setBlockRaw} schreiben kann (nur READY-Chunks sind editierbar), und die
     * Ausbreitung verpuffte still. Arbeitet die Announce-Queue des Managers ab (der
     * Load-Worker meldet Chunks mit mitgebrachten Ticks an) — der frühere Voll-Walk
     * scannte jeden Tick ALLE Chunks für im Steady-State null Treffer.
     */
    private void restorePendingScheduledTicks() {
        int pending = this.chunkManager.tickRestorePending();
        for (int i = 0; i < pending; i++) {
            Chunk chunk = this.chunkManager.pollTickRestore();
            if (chunk == null) break;
            /* Entladene/ersetzte Chunks austragen. */
            if (this.chunkManager.getChunk(chunk.chunkX, chunk.chunkZ) != chunk) continue;
            if (chunk.status != ChunkStatus.READY) {
                this.chunkManager.requeueTickRestore(chunk); // noch nicht dran — später erneut
                continue;
            }
            List<SavedTick> ticks = chunk.pendingScheduledTicks;
            if (ticks == null) continue;
            for (SavedTick tick : ticks) {
                /* Unbekannte Typen filtert schon der Serializer — defensiver Zweitcheck. */
                ScheduledTickTypes.ScheduledTickRestorer restorer = ScheduledTickTypes.get(tick.type());
                if (restorer != null) restorer.restore(this, tick);
            }
            chunk.pendingScheduledTicks = null;
        }
    }

    /**
     * Nimmt READY-Meldungen entgegen und gleicht inzwischen sichtbare Redstone-Kanten ab.
     *
     * <p>Läuft VOR {@link #processDeferredStateUpdates} und {@link #tickScheduled}.
     *
     * <p>Aufbau wie {@link #restorePendingScheduledTicks}: größen-begrenztes Poll (ein Requeue
     * darf im selben Tick nicht erneut drankommen), Identitätscheck gegen die Chunk-Map,
     * Requeue solange der Chunk noch nicht READY ist.
     */
    private void processReadyChunks() {
        int pending = this.chunkManager.readyAnnouncePending();
        this.readyChunkScratch.clear();
        for (int i = 0; i < pending; i++) {
            Chunk chunk = this.chunkManager.pollReadyAnnounce();
            if (chunk == null) break;
            if (this.chunkManager.getChunk(chunk.chunkX, chunk.chunkZ) != chunk) continue;
            if (chunk.status != ChunkStatus.READY) {
                this.chunkManager.requeueReadyAnnounce(chunk);
                continue;
            }
            this.readyChunkScratch.add(chunk);
        }

        /* Worker-Abschlussreihenfolge ist nicht deterministisch. Der zustandsändernde
           Kantenabgleich läuft deshalb stabil nach Chunkkoordinate. */
        if (this.readyChunkScratch.isEmpty()) return;
        this.energyNetworks.invalidate();
        this.readyChunkScratch.sort(Comparator
                .comparingInt((Chunk chunk) -> chunk.chunkX)
                .thenComparingInt(chunk -> chunk.chunkZ));
        LongIntMap reconciledWires = new LongIntMap(1024);
        Chunk previous = null;
        for (Chunk chunk : this.readyChunkScratch) {
            if (chunk == previous) continue;
            previous = chunk;
            /* Generierte Structure-BEs entstehen im Decorator noch ohne Dimension-Referenz. */
            for (BlockEntity blockEntity : chunk.blockEntities()) blockEntity.setWorld(this);
            if (!chunk.loadSeeded) {
                de.skyengine.game.world.block.behavior.ComparatorBehavior
                        .reconcileLoadedChunk(this, chunk);
            }
            this.releaseParkedStateUpdates(chunk);
            this.reconcilePendingOpenBoundaries(chunk, reconciledWires);
            this.reconcileReadyChunkBoundaries(chunk, reconciledWires);
            /* Persistente Hanging-Entities wurden bereits vom Load-Worker in den Chunk gelegt.
               Erst ab READY duerfen Tick und Renderer sie sehen. */
            if (!chunk.entities().isEmpty()) this.chunksWithEntities.add(chunk);
            if (this.persistentLodSource != null) this.persistentLodSource.queueLiveVolumes(chunk);
            chunk.loadSeeded = true;
        }
    }

    /**
     * Gleicht Redstone in einem Zwei-Zellen-Band beiderseits jeder neu sichtbaren Chunk-Kante ab.
     * Zwei Zellen sind für stark gespeiste Vollblöcke und Quasi-Konnektivität erforderlich.
     * Beim Erstladen läuft eine Kante erst, sobald eine Seite bereits initialisiert und beide
     * Seiten READY sind; spätere READY-Meldungen dürfen den idempotenten Abgleich wiederholen.
     */
    private void reconcileReadyChunkBoundaries(Chunk chunk,
                                                LongIntMap reconciledWires) {
        for (Direction direction : Direction.horizontalValues()) {
            Chunk neighbor = this.chunkManager.getChunk(
                    chunk.chunkX + direction.offsetX(), chunk.chunkZ + direction.offsetZ());
            if (neighbor == null || neighbor.status != ChunkStatus.READY || !neighbor.loadSeeded) continue;
            this.reconcileRedstoneBoundaryBand(chunk, direction, reconciledWires);
            this.reconcileRedstoneBoundaryBand(neighbor, direction.opposite(), reconciledWires);
        }
    }

    /** Entfernte Chunks sind bereits aus der Map; verbleibende Nachbarn sehen daher korrekt Luft. */
    private void processUnloadedChunkBoundaries() {
        int pending = this.chunkManager.unloadAnnouncePending();
        if (pending == 0) return;
        this.energyNetworks.invalidate();
        this.unloadedChunkScratch.clear();
        for (int i = 0; i < pending; i++) {
            Long key = this.chunkManager.pollUnloadAnnounce();
            if (key == null) break;
            this.unloadedChunkScratch.add(key);
        }
        this.unloadedChunkScratch.sort(Comparator
                .comparingInt((Long key) -> (int) (key >> 32))
                .thenComparingInt(Long::intValue));

        this.unloadedChunkKeys.clear();
        LongIntMap reconciledWires = new LongIntMap(1024);
        for (long key : this.unloadedChunkScratch) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            /* Der Koordinate ist vor dem Abgleich schon wieder ein neuer Chunk zugeordnet:
               dessen READY-Reconciliation übernimmt, die alte Unload-Meldung ist überholt. */
            if (this.chunkManager.getChunk(chunkX, chunkZ) != null) continue;
            this.unloadedChunkKeys.put(key, 1);
            for (Direction direction : Direction.horizontalValues()) {
                Chunk neighbor = this.chunkManager.getChunk(
                        chunkX + direction.offsetX(), chunkZ + direction.offsetZ());
                if (neighbor == null) continue;
                if (neighbor.status != ChunkStatus.READY) {
                    neighbor.pendingRedstoneBoundaryMask |= 1 << direction.opposite().faceIndex();
                    continue;
                }
                this.reconcileRedstoneBoundaryBand(neighbor, direction.opposite(), reconciledWires);
            }
        }

        /* Der Save-Snapshot des entfernten Chunks ist jetzt autoritativ. Lang laufende Ticks
           dürfen weder bis zu ihrer Zielzeit Speicher belegen noch beim Reload mit ihrem
           eingefrorenen Rest-Delay kollidieren. Ein Batch-Scan vermeidet O(Unloads × Queue). */
        int removedTicks = this.scheduledTicks.removeChunks(this.unloadedChunkKeys);
        this.simulationTelemetry.recordScheduledDroppedUnloaded(removedTicks);
        int eventsBefore = this.blockEvents.size();
        this.blockEvents.removeIf(event -> {
            long pos = event.position();
            return this.unloadedChunkKeys.containsKey(Chunk.key(
                    BlockPos.unpackX(pos) >> ChunkSection.SHIFT,
                    BlockPos.unpackZ(pos) >> ChunkSection.SHIFT));
        });
        if (this.blockEvents.size() != eventsBefore) this.blockEventRevision++;
        int deferredEventsBefore = this.blockEventsToReschedule.size();
        this.blockEventsToReschedule.removeIf(event -> {
            long pos = event.position();
            return this.unloadedChunkKeys.containsKey(Chunk.key(
                    BlockPos.unpackX(pos) >> ChunkSection.SHIFT,
                    BlockPos.unpackZ(pos) >> ChunkSection.SHIFT));
        });
        if (this.blockEventsToReschedule.size() != deferredEventsBefore) this.blockEventRevision++;
    }

    private void reconcilePendingOpenBoundaries(Chunk chunk, LongIntMap reconciledWires) {
        int mask = chunk.pendingRedstoneBoundaryMask;
        chunk.pendingRedstoneBoundaryMask = 0;
        if (mask == 0) return;
        for (Direction direction : Direction.horizontalValues()) {
            if ((mask & (1 << direction.faceIndex())) == 0) continue;
            /* Wurde die Lücke inzwischen neu belegt, übernimmt der normale Load-Seam-Abgleich. */
            if (this.chunkManager.getChunk(chunk.chunkX + direction.offsetX(),
                    chunk.chunkZ + direction.offsetZ()) == null) {
                this.reconcileRedstoneBoundaryBand(chunk, direction, reconciledWires);
            }
        }
    }

    /** Scannt nur die zwei innersten Zelllagen einer Chunk-Seite, nie das volle Volumen. */
    private void reconcileRedstoneBoundaryBand(Chunk chunk, Direction side,
                                                LongIntMap reconciledWires) {
        int originX = chunk.chunkX << ChunkSection.SHIFT;
        int originZ = chunk.chunkZ << ChunkSection.SHIFT;
        boolean xAxis = side.axis() == Direction.Axis.X;
        for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
            ChunkSection section = chunk.getSection(sectionY);
            if (section == null || section.isEmpty()) continue;

            int baseY = sectionY << ChunkSection.SHIFT;
            for (int localY = 0; localY < ChunkSection.SIZE; localY++) {
                int y = baseY + localY;
                for (int lateral = 0; lateral < ChunkSection.SIZE; lateral++) {
                    for (int depth = 0; depth < 2; depth++) {
                        int localX = xAxis
                                ? (side == Direction.WEST ? depth : ChunkSection.MASK - depth)
                                : lateral;
                        int localZ = xAxis
                                ? lateral
                                : (side == Direction.NORTH ? depth : ChunkSection.MASK - depth);
                        int stateId = section.getBlock(localX, localY, localZ);
                        if (stateId != Blocks.AIR) {
                            this.reconcileRedstoneCell(originX + localX, y, originZ + localZ,
                                    stateId, reconciledWires);
                        }
                    }
                }
            }
        }
    }

    private void reconcileRedstoneCell(int x, int y, int z, int stateId,
                                       LongIntMap reconciledWires) {
        BlockState state = Blocks.getState(stateId);
        if (RedstonePower.isWire(state)) {
            RedstoneWireNetwork.updateOncePerComponent(this, x, y, z, reconciledWires);
        } else if (state.getBlock().reconcilesRedstoneOnChunkBoundary()) {
            this.updateStateAt(x, y, z);
        }
    }

    /* Ein-Tick-Index für die Save-Snapshots: EIN forEachPending-Durchlauf je Tick gruppiert
       alle anstehenden Ticks nach Chunk-Key. Vorher scannte JEDER enqueueSave die ganze
       Queue — beim Autosave O(zu speichernde Chunks × offene Ticks) in einem einzigen
       Tick-Slot (der gemessene Kandidat für den Save-Ruckler). */
    private long tickSnapshotIndexTime = -1;
    private long tickSnapshotIndexRevision = -1;
    private long tickSnapshotBlockEventRevision = -1;
    private final de.skyengine.utils.collect.LongObjMap<List<SavedTick>> tickSnapshotIndex =
            new de.skyengine.utils.collect.LongObjMap<>(64);

    /**
     * Sammelt die anstehenden Scheduled-Ticks und wartenden Block-Events des Chunks für die
     * Persistenz. Normale Ticks verwenden den Typ {@code block}, Block-Events den Typ
     * {@code block_event} mit Event-ID und Parameter. Block-Events liegen dabei nach normalen
     * Ticks desselben Folgeticks. Nur Tick-Thread; einziger Aufrufer ist
     * {@code WorldStorage.enqueueSave}.
     * {@code null}, wenn nichts ansteht.
     */
    public List<SavedTick> snapshotScheduledTicks(Chunk chunk) {
        long queueRevision = this.scheduledTicks.revision();
        if (this.tickSnapshotIndexTime != this.gameTime
                || this.tickSnapshotIndexRevision != queueRevision
                || this.tickSnapshotBlockEventRevision != this.blockEventRevision) {
            this.tickSnapshotIndexTime = this.gameTime;
            this.tickSnapshotIndexRevision = queueRevision;
            this.tickSnapshotBlockEventRevision = this.blockEventRevision;
            this.tickSnapshotIndex.clear();
            this.scheduledTicks.forEachPending(this.gameTime,
                    (x, y, z, expectedBlock, remaining, priority, subOrder) -> {
                this.appendTickSnapshot(x, y, z, expectedBlock, remaining, priority, subOrder);
            });
            long blockEventOrder = 0;
            for (BlockEvent event : this.blockEvents) {
                long pos = event.position();
                this.appendBlockEventSnapshot(BlockPos.unpackX(pos), BlockPos.unpackY(pos),
                        BlockPos.unpackZ(pos), event.block(), event.eventId(), event.eventParam(),
                        blockEventOrder++);
            }
            for (BlockEvent event : this.blockEventsToReschedule) {
                long pos = event.position();
                this.appendBlockEventSnapshot(BlockPos.unpackX(pos), BlockPos.unpackY(pos),
                        BlockPos.unpackZ(pos), event.block(), event.eventId(), event.eventParam(),
                        blockEventOrder++);
            }
        }
        List<SavedTick> result = this.tickSnapshotIndex.get(Chunk.key(chunk.chunkX, chunk.chunkZ));
        if (result != null) result.sort(SavedTick.ORDER);
        return result;
    }

    private void appendTickSnapshot(int x, int y, int z, Identifier expectedBlock,
                                    int remaining, int priority, long subOrder) {
        long key = Chunk.key(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        List<SavedTick> list = this.tickSnapshotIndex.get(key);
        if (list == null) {
            list = new ArrayList<>();
            this.tickSnapshotIndex.put(key, list);
        }
        list.add(new SavedTick(ScheduledTickTypes.BLOCK, expectedBlock.toString(),
                x, y, z, remaining, priority, subOrder));
    }

    private void appendBlockEventSnapshot(int x, int y, int z, Identifier expectedBlock,
                                          int eventId, int eventParam, long order) {
        long key = Chunk.key(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        List<SavedTick> list = this.tickSnapshotIndex.get(key);
        if (list == null) {
            list = new ArrayList<>();
            this.tickSnapshotIndex.put(key, list);
        }
        list.add(new SavedTick(ScheduledTickTypes.BLOCK_EVENT, expectedBlock.toString(),
                x, y, z, 1, BLOCK_EVENT_TICK_PRIORITY,
                packBlockEventOrder(order, eventId, eventParam)));
    }

    /**
     * Reiht alle modifizierten Chunks zum Speichern ein (asynchron; Flush garantiert erst
     * {@code storage.close()} in {@link #dispose()}). {@code materializeFalling} nur beim
     * Welt-Austritt — s. {@link Chunk#materializeFallingBlocks()}.
     *
     * @return Anzahl der eingereihten Chunks (0 = es gab nichts zu tun)
     */
    public int saveModifiedChunks(boolean materializeFalling) {
        int queued = 0;
        for (Chunk chunk : this.chunkManager.loadedChunks()) {
            if (!chunk.isModified() || chunk.saveQueued) continue;
            if (materializeFalling) chunk.materializeFallingBlocks();
            chunk.saveQueued = true;
            this.storage.enqueueSave(chunk);
            queued++;
        }
        return queued;
    }

    /** true, solange der IO-Thread noch Chunk-Saves offen hat (Basis der Gespeichert-Meldung). */
    public boolean hasPendingSaves() {
        return this.storage.hasPendingSaves();
    }

    /**
     * Lädt alle Chunks autoritativ aus ihren Persistenz-Snapshots neu. Der Manager zieht beim
     * Clear die Snapshots modifizierter Chunks synchron; erst danach dürfen die Runtime-Tick-
     * Queues verschwinden. Neue Load-Jobs starten erst, wenn die asynchronen Writes fertig sind,
     * damit sie nicht den vorherigen Plattenstand lesen.
     */
    public void reloadAllChunks() {
        this.chunkReloadRequested = true;
        /* Ohne älteren Save kann der Button den synchronen Snapshot/Clear sofort ausführen.
           Das hält die bisherige unmittelbare Debug-Aktion bei und vereinfacht Headless-Tests. */
        if (!this.storage.hasPendingSaves()) this.beginChunkReload();
    }

    /** @return true, solange Welt-Simulation und neue Loads für die Reload-Barriere pausieren. */
    private boolean processChunkReload() {
        if (this.chunkReloadRequested) {
            if (this.storage.hasPendingSaves()) return true;
            this.beginChunkReload();
        }
        if (!this.chunkReloadWaitingForSaves) return false;
        if (this.storage.hasPendingSaves()) return true;
        this.chunkReloadWaitingForSaves = false;
        return false;
    }

    private void beginChunkReload() {
        this.chunkReloadRequested = false;
        this.chunkManager.clearAllChunks();
        this.scheduledTicks.clear();
        if (!this.blockEvents.isEmpty()) {
            this.blockEvents.clear();
            this.blockEventRevision++;
        }
        if (!this.blockEventsToReschedule.isEmpty()) {
            this.blockEventsToReschedule.clear();
            this.blockEventRevision++;
        }
        this.chunkReloadWaitingForSaves = this.storage.hasPendingSaves();
    }

    /**
     * Markiert den Chunk als seit dem letzten Save verändert — für Mutationen, die nicht
     * über {@link #setBlock} laufen (z.B. Truhen-Inventar über das GUI).
     */
    public void markChunkModified(int x, int z) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk != null && chunk.status == ChunkStatus.READY) chunk.markModified();
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
        int ticked = 0;
        if (!this.pendingEntities.isEmpty()) {
            for (Entity entity : this.pendingEntities) this.addToChunk(entity);
            this.pendingEntities.clear();
        }

        /* Nur Chunks mit Entities — dieselbe Menge, die auch der Renderer nutzt; der frühere
           Voll-Walk über alle geladenen Chunks fand pro Tick fast nur Leere. Spawns landen in
           pendingEntities (oben geflusht), Chunk-Wechsel hängt erst reconcileEntityChunks um —
           die Menge ändert sich während der Iteration also nicht. */
        for (Chunk chunk : this.chunksWithEntities) {
            if (chunk.status != ChunkStatus.READY) continue;
            if (!this.isSimulated(chunk.chunkX, chunk.chunkZ)) continue;
            List<Entity> list = chunk.entities();
            for (int i = 0; i < list.size(); i++) {
                list.get(i).tick(this);
                ticked++;
            }
        }

        this.reconcileEntityChunks();
        PerformanceProfiler.get().add(PerformanceProfiler.Counter.TICKED_ENTITIES, ticked);
    }

    /**
     * Räumt nach dem Tick auf: entfernte Entities aussortieren und Entities, die ihren Chunk
     * verlassen haben, in den Zielchunk umhängen. Das Umhängen wird gesammelt und erst nach dem
     * Durchlauf angewandt, damit eine Entity nicht im selben Tick doppelt verarbeitet wird.
     */
    private void reconcileEntityChunks() {
        this.transferBuffer.clear();
        for (Iterator<Chunk> ci = this.chunksWithEntities.iterator(); ci.hasNext(); ) {
            Chunk chunk = ci.next();
            /* Entladene/ersetzte Chunks austragen: die Map hält dann nicht mehr diese Instanz.
               Verhindert, dass die Menge entladene Chunks am Leben hält (Leak/Fehlrender). */
            if (this.chunkManager.getChunk(chunk.chunkX, chunk.chunkZ) != chunk) {
                ci.remove();
                continue;
            }
            List<Entity> list = chunk.entities();
            for (Iterator<Entity> it = list.iterator(); it.hasNext(); ) {
                Entity entity = it.next();
                if (entity.isRemoved()) {
                    it.remove();
                    if (entity.isPersistent()) chunk.markModified();
                    continue;
                }
                int cx = (int) Math.floor(entity.x) >> ChunkSection.SHIFT;
                int cz = (int) Math.floor(entity.z) >> ChunkSection.SHIFT;
                if (cx != chunk.chunkX || cz != chunk.chunkZ) {
                    it.remove();
                    if (entity.isPersistent()) chunk.markModified();
                    this.transferBuffer.add(entity);
                }
            }
            if (list.isEmpty()) ci.remove(); // keine Entities mehr → aus der Menge nehmen
        }
        for (Entity entity : this.transferBuffer) this.addToChunk(entity);
        this.transferBuffer.clear();
    }

    /** Hängt eine Entity in den Chunk an ihrer aktuellen Position; verwirft sie, wenn der Chunk nicht (READY) geladen ist. */
    private void addToChunk(Entity entity) {
        int cx = (int) Math.floor(entity.x) >> ChunkSection.SHIFT;
        int cz = (int) Math.floor(entity.z) >> ChunkSection.SHIFT;
        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        if (chunk != null && chunk.status == ChunkStatus.READY) {
            chunk.addEntity(entity);
            if (entity.isPersistent()) chunk.markModified();
            this.chunksWithEntities.add(chunk);
        }
    }

    /** Reiht eine Entity zum Spawnen ein (Übernahme im nächsten {@link #tickEntities}). */
    public void spawnEntity(Entity entity) {
        this.pendingEntities.add(entity);
    }

    /** Setzt ein normales Minecart auf die Schienenposition. */
    public MinecartEntity spawnMinecart(double x, double y, double z) {
        MinecartEntity minecart = new MinecartEntity();
        minecart.setPosition(x, y, z);
        minecart.alignToRail(this);
        this.spawnEntity(minecart);
        return minecart;
    }

    /** Sensor-Schiene: Minecart-Hitbox im schmalen Erfassungsbereich oberhalb der Schiene. */
    public boolean hasMinecartAtRail(int x, int y, int z) {
        AABB sensor = new AABB(x + 0.2, y, z + 0.2, x + 0.8, y + 0.8, z + 0.8);
        final boolean[] found = {false};
        this.forEachEntityNearby(x + 0.5, z + 0.5, 1, entity -> {
            if (!found[0] && entity instanceof MinecartEntity && !entity.isRemoved()
                    && entity.getBoundingBox().intersects(sensor)) found[0] = true;
        });
        return found[0];
    }

    /** Naechstes Minecart auf dem Augenstrahl; Blöcke begrenzen die Reichweite. */
    public MinecartEntity raycastMinecart(double ox, double oy, double oz,
                                           double dx, double dy, double dz, double maxDistance) {
        final MinecartEntity[] closest = {null};
        final double[] distance = {maxDistance};
        this.forEachEntityNearby(ox + dx * maxDistance * 0.5,
                oz + dz * maxDistance * 0.5, 1, entity -> {
            if (!(entity instanceof MinecartEntity minecart) || minecart.isRemoved()) return;
            double hit = minecart.rayIntersection(ox, oy, oz, dx, dy, dz, distance[0]);
            if (hit < distance[0]) {
                distance[0] = hit;
                closest[0] = minecart;
            }
        });
        return closest[0];
    }

    /**
     * Platziert ein Item Frame unmittelbar im READY-Chunk. Anders als bewegte Spawns muss es noch
     * in diesem Interaktionstick fuer Comparator-Abfragen sichtbar sein.
     */
    public boolean placeItemFrame(int anchorX, int anchorY, int anchorZ, Direction direction) {
        if (!this.isPositionEditable(anchorX, anchorY, anchorZ)) return false;
        ItemFrameEntity frame = new ItemFrameEntity(anchorX, anchorY, anchorZ, direction);
        if (!frame.survives(this)) return false;
        Chunk chunk = this.chunkManager.getChunk(anchorX >> ChunkSection.SHIFT, anchorZ >> ChunkSection.SHIFT);
        chunk.addEntity(frame);
        chunk.markModified();
        this.chunksWithEntities.add(chunk);
        this.updateComparatorOutputs(anchorX, anchorY, anchorZ);
        return true;
    }

    /** Spawnt einen flüssig fallenden Block an der Blockposition (Fußpunkt = y, zentriert in x/z). */
    public void spawnFallingBlock(int x, int y, int z, int blockId) {
        FallingBlockEntity entity = new FallingBlockEntity(blockId);
        entity.setPosition(x + 0.5, y, z + 0.5);
        this.spawnEntity(entity);
    }

    /** Spawnt gezündetes TNT als Entity (Fuse-Countdown + weißer Blink) mit MC-typischem Hüpfer. */
    public void spawnPrimedTnt(double x, double y, double z, float power, int fuse) {
        PrimedTntEntity entity = new PrimedTntEntity(power, fuse);
        entity.setPosition(x, y, z);
        /* PrimedTnt-Konstruktor in Vanilla: horizontal immer Radius 0.02 auf einem zufaelligen
           Kreiswinkel (nicht unabhaengige X/Z-Werte in einem Quadrat), Y ist der Floatwert 0.2. */
        double angle = this.random.nextDouble() * Math.PI * 2.0;
        entity.motionX = -Math.sin(angle) * 0.02;
        entity.motionY = 0.20000000298023224;
        entity.motionZ = -Math.cos(angle) * 0.02;
        this.spawnEntity(entity);
        /* Vanilla spielt TNT_PRIMED fuer jede Entity. Gleichzeitige Zuendungen zu deckeln
           veraendert Kanonen und Kettenreaktionen hoerbar. */
        if (this.soundManager != null) {
            this.soundManager.playFuse(x, y, z); // Zisch beim Zünden
        }
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

    /** Wurfstärke und Aufsammel-Sperre eines Spieler-Drops (MC {@code Player.drop}). */
    private static final double THROW_SPEED = 0.3;
    private static final int THROW_PICKUP_DELAY = 40;

    /**
     * Wirft ein Item vom Spieler weg (Vorbild MC {@code Player.drop}): aus Augenhöhe in
     * Blickrichtung, mit leichter Streuung, danach {@value #THROW_PICKUP_DELAY} Ticks lang nicht
     * aufsammelbar — sonst hätte man es sofort wieder in der Hand.
     */
    public void throwItem(EntityPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemEntity entity = new ItemEntity(stack);
        entity.setPosition(player.x, player.y + player.getEyeHeight(1F) - 0.3, player.z);
        entity.setPickupDelay(THROW_PICKUP_DELAY);

        /* Blickrichtung in DIESER Engine-Konvention (siehe Camera.getDirection): x = +sin(yaw),
           z = -cos(yaw) — MCs Formel hat dort die umgekehrten Vorzeichen. */
        double yaw = Math.toRadians(player.yaw);
        double pitch = Math.toRadians(player.pitch);
        double cosPitch = Math.cos(pitch);
        entity.motionX = cosPitch * Math.sin(yaw) * THROW_SPEED;
        entity.motionY = -Math.sin(pitch) * THROW_SPEED + 0.1;
        entity.motionZ = -cosPitch * Math.cos(yaw) * THROW_SPEED;

        /* Streuung wie in MC, damit ein ganzer Stapel nicht als Strich fliegt. */
        double angle = this.random.nextDouble() * Math.PI * 2.0;
        double radius = this.random.nextDouble() * 0.02;
        entity.motionX += Math.cos(angle) * radius;
        entity.motionY += (this.random.nextDouble() - this.random.nextDouble()) * 0.1;
        entity.motionZ += Math.sin(angle) * radius;

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

    /** Iteriert alle Entities in READY-Chunks; der Callback darf die Entity-Listen nicht verändern. */
    public void forEachLoadedEntity(Consumer<Entity> action) {
        for (Chunk chunk : this.chunksWithEntities) {
            if (chunk.status != ChunkStatus.READY) continue;
            List<Entity> entities = chunk.entities();
            for (int i = 0; i < entities.size(); i++) action.accept(entities.get(i));
        }
    }

    /** true, wenn ein anderer lebender Rahmen dieselbe Hanging-Flaeche belegt. */
    public boolean hasOverlappingItemFrame(ItemFrameEntity frame) {
        final boolean[] found = {false};
        this.forEachEntityNearby(frame.x, frame.z, 1, entity -> {
            if (!found[0] && entity != frame && !entity.isRemoved()
                    && entity instanceof ItemFrameEntity other
                    && frame.conflictsWith(other)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** Naechstes sichtbares Item Frame auf dem Augenstrahl; Blöcke begrenzen maxDistance. */
    public ItemFrameEntity raycastItemFrame(double ox, double oy, double oz,
                                             double dx, double dy, double dz, double maxDistance) {
        final ItemFrameEntity[] closest = {null};
        final double[] distance = {maxDistance};
        this.forEachEntityNearby(ox + dx * maxDistance * 0.5,
                oz + dz * maxDistance * 0.5, 1, entity -> {
            if (!(entity instanceof ItemFrameEntity frame) || frame.isRemoved()) return;
            double hit = frame.rayIntersection(ox, oy, oz, dx, dy, dz, distance[0]);
            if (hit < distance[0]) {
                distance[0] = hit;
                closest[0] = frame;
            }
        });
        return closest[0];
    }

    /**
     * Vanillas Comparator-Sonderquelle: exakt ein passend ausgerichteter Rahmen in der Zielzelle.
     * Mehrere passende Rahmen liefern absichtlich kein Signal.
     */
    public int getItemFrameAnalogSignal(int x, int y, int z, Direction direction) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null || chunk.status != ChunkStatus.READY) return -1;
        ItemFrameEntity match = null;
        for (Entity entity : chunk.entities()) {
            if (!(entity instanceof ItemFrameEntity frame) || frame.isRemoved()) continue;
            if (frame.getAnchorX() != x || frame.getAnchorY() != y || frame.getAnchorZ() != z
                    || frame.getDirection() != direction) continue;
            if (match != null) return -1;
            match = frame;
        }
        return match == null ? -1 : match.getAnalogOutput();
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
        int ticked = 0;
        /* Nur Chunks mit BlockEntities (Manager-Buchführung) statt Scan über alle Chunks. */
        for (Chunk chunk : this.chunkManager.chunksWithBlockEntities()) {
            if (chunk.status != ChunkStatus.READY) continue;
            if (!this.isSimulated(chunk.chunkX, chunk.chunkZ)) continue;
            var entities = chunk.blockEntities();
            if (entities.isEmpty()) continue;
            /* Snapshot in den wiederverwendeten Puffer: ein tick() darf Blöcke setzen / die Map
               verändern. Nicht-tickende Typen bleiben gleich draußen. */
            this.tickScratch.clear();
            for (BlockEntity be : entities) {
                if (be.getType().isTicking()) this.tickScratch.add(be);
            }
            for (int i = 0; i < this.tickScratch.size(); i++) {
                this.tickScratch.get(i).tick();
                ticked++;
            }
        }
        PerformanceProfiler.get().add(PerformanceProfiler.Counter.TICKED_BLOCK_ENTITIES, ticked);
    }

    /* --- Tick-Scheduler (Phase 1.1): geplante + Zufalls-Ticks --- */

    /**
     * Merkt einen geplanten Tick für die Position vor. Nach {@code delayTicks} Ticks (min. 1)
     * ruft der Block dort {@link de.skyengine.game.world.block.Block#scheduledTick} auf. Pro
     * Position und erwartetem Blocktyp ist nur ein Tick gleichzeitig vorgemerkt (Dedup).
     * Basis für Fluss/Fall.
     *
     * <p>Markiert den Chunk zusätzlich als modified: ein anstehender Tick ist Zustand, der
     * gespeichert werden muss — sonst verlöre eine Clock (Verstärker-Loop), die seit dem
     * letzten Save keinen Block geschrieben hat, beim Beenden ihren Rest-Delay und stünde
     * nach dem Neuladen still.</p>
     */
    public void scheduleTick(int x, int y, int z, int delayTicks) {
        this.scheduleTick(x, y, z, delayTicks, TickPriority.NORMAL);
    }

    /** Plant einen Block-Tick mit Vanillas Prioritaetsreihenfolge innerhalb der Zielzeit. */
    public void scheduleTick(int x, int y, int z, int delayTicks,
                             TickPriority priority) {
        Identifier expectedBlock = Blocks.getState(this.getBlock(x, y, z)).getBlock().getIdentifier();
        boolean accepted = this.scheduledTicks.schedule(x, y, z, expectedBlock,
                this.gameTime + Math.max(0, delayTicks), priority.value());
        this.simulationTelemetry.recordScheduledRequest(accepted);
        if (accepted) this.markChunkModified(x, z);
    }

    /**
     * Wie {@link #scheduleTick}, zieht aber einen bereits anstehenden <em>späteren</em> Tick auf diese
     * frühere Zeit vor (statt ihn zu ignorieren). Für prompte Reaktionen (z.B. Lava+Wasser→Cobble),
     * die einen regulären Fluss-Tick überholen müssen.
     */
    public void scheduleTickEarlier(int x, int y, int z, int delayTicks) {
        Identifier expectedBlock = Blocks.getState(this.getBlock(x, y, z)).getBlock().getIdentifier();
        boolean accepted = this.scheduledTicks.scheduleEarlier(x, y, z, expectedBlock,
                this.gameTime + Math.max(0, delayTicks));
        this.simulationTelemetry.recordScheduledRequest(accepted);
        if (accepted) this.markChunkModified(x, z);
    }

    /** true, wenn an der Position bereits ein geplanter Tick aussteht. */
    public boolean isTickScheduled(int x, int y, int z) {
        Identifier expectedBlock = Blocks.getState(this.getBlock(x, y, z)).getBlock().getIdentifier();
        return this.isTickScheduled(x, y, z, expectedBlock);
    }

    /** Einheitlicher Drop-Pfad für nicht vom Spieler verursachte Einzelblock-Zerstörung. */
    public void dropBlockLoot(int x, int y, int z, BlockState state, LootContext.Cause cause) {
        var context = new LootContext(this, x, y, z, state, ItemStack.EMPTY, cause, 0.0F, this.random);
        state.getBlock().appendDrops(context, (stack, dropX, dropY, dropZ) -> spawnItem(dropX + 0.5, dropY + 0.5, dropZ + 0.5, stack));
    }

    /** Tick-Abfrage fuer einen expliziten alten Blocktyp, insbesondere nach dessen Entfernung. */
    public boolean isTickScheduled(int x, int y, int z, Identifier expectedBlock) {
        return this.scheduledTicks.isScheduled(x, y, z, expectedBlock);
    }

    /**
     * Vanilla {@code LevelTicks#willTickThisTick}: der Block ist in der bereits eingesammelten
     * aktuellen Tick-Runde enthalten und wurde noch nicht ausgefuehrt.
     */
    public boolean willTickThisTick(int x, int y, int z) {
        Identifier expectedBlock = Blocks.getState(this.getBlock(x, y, z)).getBlock().getIdentifier();
        return this.scheduledTicks.willTickThisTick(x, y, z, expectedBlock);
    }

    /** Stellt einen persistierten Blocktick mit seiner urspruenglichen Reihenfolge wieder her. */
    public void restoreScheduledBlockTick(SavedTick tick) {
        Identifier expectedBlock = tick.expectedBlock() == null
                ? Blocks.getState(this.getBlock(tick.x(), tick.y(), tick.z())).getBlock().getIdentifier()
                : Identifier.of(tick.expectedBlock());
        boolean accepted = this.scheduledTicks.scheduleRestored(tick.x(), tick.y(), tick.z(), expectedBlock,
                this.gameTime + tick.remainingTicks(), tick.priority(), tick.subOrder());
        this.simulationTelemetry.recordScheduledRequest(accepted);
        /* Reiner Load-Pfad: Der Tick ist bereits Bestandteil des autoritativen
           Chunk-Snapshots. Vanilla macht den Chunk beim Unpack ebenfalls nicht erneut
           unsaved; erst eine spaetere Queue-Mutation erzeugt wieder Save-Arbeit. */
    }

    /** Stellt Event-ID und Parameter eines persistierten Vanilla-Blockevents wieder her. */
    public void restoreBlockEvent(SavedTick tick) {
        Identifier expectedBlock = tick.expectedBlock() == null
                ? Blocks.getState(this.getBlock(tick.x(), tick.y(), tick.z())).getBlock().getIdentifier()
                : Identifier.of(tick.expectedBlock());
        BlockEvent event = new BlockEvent(BlockPos.asLong(tick.x(), tick.y(), tick.z()), expectedBlock,
                unpackBlockEventId(tick.subOrder()), unpackBlockEventParam(tick.subOrder()));
        if (!this.blockEvents.add(event)) return;
        this.blockEventRevision++;
        /* Wie bei restoreScheduledBlockTick stammt das Event aus dem bereits
           geschriebenen Chunk-Snapshot und ist deshalb keine neue Weltmutation. */
    }

    private static long packBlockEventOrder(long order, int eventId, int eventParam) {
        return (order << 32) | ((eventId & 0xffffL) << 16) | (eventParam & 0xffffL);
    }

    private static int unpackBlockEventId(long packed) {
        return (short) (packed >>> 16);
    }

    private static int unpackBlockEventParam(long packed) {
        return (short) packed;
    }

    /** Aktuelle Spielzeit in Ticks (20 TPS). */
    public long getGameTime() {
        return this.gameTime;
    }

    /** true ausschließlich während des vollständigen Server-Weltticks. */
    public boolean isHandlingTick() {
        return this.handlingTick;
    }

    /** Diagnosezaehler dieser Welt; standardmaessig nur im Full-Debug-Modus aktiv. */
    public SimulationTelemetry getSimulationTelemetry() {
        return this.simulationTelemetry;
    }

    /** Registriert einen globalen Behavior-Speicher bei seiner ersten Benutzung in dieser Welt. */
    public void registerTransientPositionState(WorldScopedPositionMap<?> state) {
        this.transientPositionStates.add(state);
    }

    private void pruneTransientPositionStates() {
        int removalVersion = this.chunkManager.getChunkRemovalVersion();
        if (removalVersion == this.transientStateRemovalVersion) return;
        for (WorldScopedPositionMap<?> state : this.transientPositionStates) state.prune(this);
        this.transientStateRemovalVersion = removalVersion;
    }

    /**
     * Führt alle fälligen geplanten Ticks aus (Fluss-Ausbreitung, Fallprüfung, ...). Außerhalb der
     * Simulations-Distanz bleibt der Tick mit seiner ursprünglichen Zielzeit in der Queue. Sobald
     * der Chunk wieder tickt, ist er deshalb wie in Vanilla sofort fällig. Während des Drains neu
     * geplante Einträge verarbeitet drainDue erst im nächsten Tick (keine Endlosschleife).
     *
     * <p>Ticks ENTLADENER Chunks werden dagegen verworfen: ihr Rest-Delay liegt im Save
     * (scheduleTick markiert den Chunk als modified, der Unload-Pfad speichert ihn), und beim
     * Wiederladen stellt {@link #restorePendingScheduledTicks} sie wieder her. Ohne das Verwerfen
     * wüchse die Queue über die Sitzung unbegrenzt.</p>
     */
    private void tickScheduled() {
        this.scheduledTicks.drainDue(this.gameTime, MAX_SCHEDULED_TICKS_PER_TICK,
                (x, y, z) -> {
                    int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                    Chunk chunk = this.chunkManager.getChunk(cx, cz);
                    return chunk != null && chunk.status == ChunkStatus.READY
                            && this.isSimulated(cx, cz);
                },
                (x, y, z, expectedBlock, triggerTime, priority, subOrder) -> {
                    this.simulationTelemetry.recordScheduledDue();
                    int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                    Chunk chunk = this.chunkManager.getChunk(cx, cz);
                    if (chunk == null) {
                        this.simulationTelemetry.recordScheduledDroppedUnloaded();
                        return;
                    }
                    /* drainDue hat den Eintrag bereits entfernt. War er in einem früheren
                       Snapshot gespeichert, muss auch Ausführung, Skip oder Re-Schedule eine
                       neue Save-Epoch erzeugen — selbst während eines LIT/Remesh-Zustands. */
                    chunk.markModified();
                    BlockState state = Blocks.getState(this.getBlock(x, y, z));
                    if (!state.getBlock().getIdentifier().equals(expectedBlock)) {
                        this.simulationTelemetry.recordScheduledSkippedWrongBlock();
                        return;
                    }
                    if (state.isAir()) {
                        this.simulationTelemetry.recordScheduledSkippedAir();
                        return;
                    }
                    this.simulationTelemetry.recordScheduledExecuted();
                    PerformanceProfiler.get().add(PerformanceProfiler.Counter.EXECUTED_BLOCK_TICKS, 1);
                    state.getBlock().scheduledTick(this, x, y, z, state);
                });
    }

    /**
     * Zufalls-Ticks: pro nicht-leerer Section werden {@link #RANDOM_TICK_SPEED} zufällige
     * Positionen gezogen; nur Blöcke mit {@link BlockState#ticksRandomly()} reagieren
     * (Pflanzenwachstum, Verfall). Begrenzt auf die Simulations-Distanz um den Spieler.
     */
    private void tickRandomBlocks() {
        if (RANDOM_TICK_SPEED <= 0 || !BlockRegistry.hasRandomTickBlocks()) return;
        /* Radius-Loop über die Simulations-Distanz statt Voll-Walk über ALLE geladenen
           Chunks: der Walk skalierte mit renderDistance, relevant ist nur der Sim-Kreis
           (Kreis-Kriterium identisch zu isSimulated). */
        int r = this.simulationDistance;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue;
                Chunk chunk = this.chunkManager.getChunk(this.playerChunkX + dx, this.playerChunkZ + dz);
                if (chunk == null || chunk.status != ChunkStatus.READY) continue;
                int baseX = chunk.chunkX << ChunkSection.SHIFT;
                int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
                for (int si = 0; si < Chunk.SECTIONS; si++) {
                    ChunkSection section = chunk.getSection(si);
                    if (section == null || section.isEmpty()) continue;
                    int baseY = si << ChunkSection.SHIFT;
                    for (int n = 0; n < RANDOM_TICK_SPEED; n++) {
                        /* Ein Draw für alle drei Achsen (15 Bit reichen für 3x 5 Bit). */
                        int bits = this.randomTick.nextInt(1 << 15);
                        int lx = bits & ChunkSection.MASK;
                        int ly = (bits >> 5) & ChunkSection.MASK;
                        int lz = (bits >> 10) & ChunkSection.MASK;
                        int id = section.getBlock(lx, ly, lz);
                        if (id == Blocks.AIR) continue;
                        BlockState state = Blocks.getState(id);
                        if (!state.ticksRandomly()) continue;
                        state.getBlock().randomTick(this, baseX + lx, baseY + ly, baseZ + lz, state);
                    }
                }
            }
        }
    }

    /**
     * Minecraft-artige clientseitige Animate-Ticks nahe am Spieler. Sie sind bewusst von den
     * serverartigen Random-Ticks getrennt: Sounds dürfen Wachstum und Fluid-Timing nicht
     * beeinflussen. Minecraft 26.2 prüft pro Sample je einen 16er- und 32er-Ring.
     */
    private void tickAnimateBlocks() {
        if (this.player == null) return;
        int px = (int) Math.floor(this.player.x);
        int py = (int) Math.floor(this.player.y);
        int pz = (int) Math.floor(this.player.z);
        for (int i = 0; i < ANIMATE_TICK_SAMPLES; i++) {
            this.animateBlockSample(px, py, pz, ANIMATE_TICK_NEAR_RADIUS);
            this.animateBlockSample(px, py, pz, ANIMATE_TICK_FAR_RADIUS);
        }
    }

    private void animateBlockSample(int centerX, int centerY, int centerZ, int radius) {
        int x = centerX + this.animateCoordinateRandom.nextInt(radius)
                - this.animateCoordinateRandom.nextInt(radius);
        int y = centerY + this.animateCoordinateRandom.nextInt(radius)
                - this.animateCoordinateRandom.nextInt(radius);
        int z = centerZ + this.animateCoordinateRandom.nextInt(radius)
                - this.animateCoordinateRandom.nextInt(radius);
        BlockState state = Blocks.getState(this.getBlock(x, y, z));
        if (state.isAir()) return;
        state.getBlock().animateTick(this, x, y, z, state, this.animateEffectRandom);
        if (state.isFluid() && !state.getBlock().getFluidInfo().lava
                && this.animateEffectRandom.nextInt(10) == 0) {
            this.particles.underwater(x + this.animateEffectRandom.nextDouble(),
                    y + this.animateEffectRandom.nextDouble(), z + this.animateEffectRandom.nextDouble());
        }
        if (!state.isFluid() && this.getBlock(x, y - 1, z) == Blocks.AIR) {
            BlockState above = Blocks.getState(this.getBlock(x, y + 1, z));
            if (above.isFluid() && this.animateEffectRandom.nextInt(10) == 0) {
                this.particles.drip(x + this.animateEffectRandom.nextDouble(), y - 0.02,
                        z + this.animateEffectRandom.nextDouble(), above.getBlock().getFluidInfo().lava);
            }
        }
        String id = state.getBlock().getIdentifier().path();
        if (id.equals("redstone_wire")) {
            this.particles.redstoneWire(x, y, z, state);
        } else if (id.equals("redstone_torch") && state.get(Properties.LIT)) {
            this.animateRedstoneTorch(x, y, z, state);
        } else if (id.equals("torch")) {
            this.animateTorch(x, y, z, state);
        }
        if (state.isLeaves()) this.animateLeaves(x, y, z, state, id);
    }

    private void animateTorch(int x, int y, int z, BlockState state) {
        double px = x + 0.5, py = y + 0.7, pz = z + 0.5;
        if (state.get(Properties.ATTACH) == AttachFace.WALL) {
            Direction facing = state.get(Properties.FACING);
            px += facing.offsetX() * 0.27;
            py += 0.22;
            pz += facing.offsetZ() * 0.27;
        }
        this.particles.torch(px, py, pz);
    }

    private void animateRedstoneTorch(int x, int y, int z, BlockState state) {
        double px = x + 0.5 + (this.animateEffectRandom.nextDouble() - 0.5) * 0.2;
        double py = y + 0.7 + (this.animateEffectRandom.nextDouble() - 0.5) * 0.2;
        double pz = z + 0.5 + (this.animateEffectRandom.nextDouble() - 0.5) * 0.2;
        if (state.get(Properties.ATTACH) == AttachFace.WALL) {
            Direction facing = state.get(Properties.FACING);
            px += facing.offsetX() * 0.27;
            py += 0.22;
            pz += facing.offsetZ() * 0.27;
        }
        this.particles.redstoneDust(px, py, pz, 0xFF0000,
                ParticlePriority.AMBIENT);
    }

    private void animateLeaves(int x, int y, int z, BlockState state, String id) {
        float chance = id.equals("pale_oak_leaves") ? 0.02F : 0.01F;
        if (this.animateEffectRandom.nextFloat() >= chance
                || this.getCollisionShape(x, y - 1, z).isFaceFull(Direction.UP)) return;
        this.particles.fallingLeaf(x + this.animateEffectRandom.nextDouble(), y - 0.05,
                z + this.animateEffectRandom.nextDouble(), state, id.equals("pale_oak_leaves"));
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

    /**
     * Entfernt eine VERWAISTE BlockEntity (der Block der Zelle passt nicht mehr zu ihr —
     * z.B. eine geladene Piston-Moving-BE auf einer inzwischen anderen Zelle).
     * {@code manageBlockEntity} räumt nur bei Typwechsel über setBlock auf; Waisen aus
     * inkonsistenten Saves erreicht das nie.
     */
    public void removeBlockEntity(int x, int y, int z) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return;
        chunk.writeLock().lock();
        try {
            chunk.removeBlockEntity(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
        } finally {
            chunk.writeLock().unlock();
        }
    }

    /**
     * Benachrichtigt Komparatoren, die den Container an (x,y,z) messen (direkt daneben oder
     * durch einen opaken Block hindurch) — Container-Mutationen erzeugen keine
     * Nachbar-Updates, deshalb rufen Hopper nach Transfers und Container-GUIs beim
     * Schließen hier an (MCs updateNeighbourForOutputSignal).
     */
    public void updateComparatorOutputs(int x, int y, int z) {
        for (Direction d : Direction.horizontalValues()) {
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            BlockState neighbor = Blocks.getState(this.getBlock(nx, y, nz));
            if (neighbor.getValues().containsKey(
                    de.skyengine.game.world.block.state.Properties.MODE)) {
                this.updateStateAt(nx, y, nz);
            } else if (neighbor.isRedstoneConductor()) {
                int fx = nx + d.offsetX(), fz = nz + d.offsetZ();
                BlockState far = Blocks.getState(this.getBlock(fx, y, fz));
                if (far.getValues().containsKey(
                        de.skyengine.game.world.block.state.Properties.MODE)) {
                    this.updateStateAt(fx, y, fz);
                }
            }
        }
    }

    /**
     * true, wenn die Zelle beschreibbar ist (y im Weltbereich, Chunk geladen und READY).
     * Für Vorab-Validierungen von Mehr-Zellen-Operationen (Kolben-Schub): erst ALLE Zellen
     * prüfen, dann schreiben — sonst hinterließe ein halb fehlgeschlagener setBlock-Lauf
     * Duplikate an der Ladefront.
     */
    public boolean isPositionEditable(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        return chunk != null && chunk.status == ChunkStatus.READY;
    }

    /**
     * Ob der Spieler diese Zelle bereits wirklich sehen und damit sicher anvisieren darf.
     *
     * <p>{@link ChunkStatus#READY} bedeutet nur, dass alle initialen Section-Meshes erzeugt und
     * zum Upload eingereiht wurden. Bis der Renderer sie komplett übernommen hat, bleibt der
     * atomare LOD-Parent als sichtbarer Fallback aktiv. In diesem Übergang dürfen weder Auswahl
     * noch Interaktionen auf die noch nicht vollständig sichtbaren L0-Daten zugreifen.</p>
     */
    public boolean isPlayerInteractionReady(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        boolean lodShowsCell = this.lodManager != null && this.lodManager.lodShowsCell(cx, cz);
        return isPlayerInteractionReady(chunk, lodShowsCell);
    }

    /** Pure Statusmatrix fuer Tests und den oeffentlichen Weltkoordinaten-Pfad oben. */
    static boolean isPlayerInteractionReady(Chunk chunk, boolean lodShowsCell) {
        return chunk != null && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded()
                && !chunk.pendingUnload && !lodShowsCell;
    }


    @Override
    public void dispose() {
        /* ERST die Worker stoppen (inkl. awaitTermination), DANN die GL-Ressourcen: In-flight-
           Mesh-Jobs dürfen beim Welt-Austritt nicht mehr laufen, wenn Arenen/Meshes sterben —
           sonst arbeiten Alt-Jobs beim direkten Wiedereintritt in die neue Welt hinein. */
        this.chunkManager.dispose();
        this.saveRuntimeState();
        this.disposeClientLod();
        /* NACH den Workern: jetzt schreibt niemand mehr auf Chunks — ausstehende Save-Jobs
           flushen (bis 10 s) und die Region-Handles schließen. */
        this.storage.close();
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

    /**
     * Block der aktuell sichtbaren Welt-Darstellung. Solange ein LOD-Mesh die Zelle ersetzt,
     * wird dessen Spalte gesampelt; andernfalls gelten die echten Chunkdaten aus
     * {@link #getBlock}. Nur fuer Kamera- und Render-Effekte verwenden, nie fuer Simulation.
     */
    public int getRenderedBlock(int x, int y, int z) {
        if (this.lodManager != null) {
            int lodState = this.lodManager.visibleStateAt(x, y, z);
            if (lodState >= 0) return lodState;
        }
        return this.getBlock(x, y, z);
    }

    /** Aufrufer-gehaltener Ein-Eintrag-Chunk-Cache für getBlock-Serien (Explosions-Raycast). */
    static final class ChunkMemo {
        private int cx = Integer.MIN_VALUE, cz;
        private Chunk chunk; // null = fehlt/nicht lesbar (Semantik wie getBlock)
    }

    /**
     * Wie {@link #getBlock}, aber mit Last-Chunk-Memo: Explosions-Strahlen laufen räumlich
     * kohärent — der CHM-Lookup pro Schritt (Millionen pro Explosion) war der teuerste
     * Einzelposten des Raycasts. Semantik identisch zu getBlock (unter DECORATED = Luft).
     */
    int getBlockMemo(int x, int y, int z, ChunkMemo memo) {
        if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;
        int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
        if (cx != memo.cx || cz != memo.cz) {
            memo.cx = cx;
            memo.cz = cz;
            Chunk chunk = this.chunkManager.getChunk(cx, cz);
            memo.chunk = chunk != null && chunk.status.isAtLeast(ChunkStatus.DECORATED) ? chunk : null;
        }
        return memo.chunk == null ? Blocks.AIR
                : memo.chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    /**
     * Himmelslicht 0..15 an Weltkoordinaten — für Objekte, die kein gebackenes Vertex-Licht
     * haben (Spieler, Item-Drops, Item in der Hand, BlockEntities).
     *
     * <p>Zwei bewusste Abweichungen von {@link #getBlock}: das Status-Gate liegt bei
     * {@link ChunkStatus#LIT} statt DECORATED (davor ist der {@code LightStorage} eines Chunks
     * durchgehend 0 — ein früherer Read lieferte also <b>schwarz</b> statt „unbekannt"), und
     * fehlende bzw. noch unbelichtete Chunks liefern <b>15</b> statt 0. Sonst würden Hand und
     * Drops an der Ladekante kurz schwarz aufblitzen, obwohl gleich hell nachgeladen wird —
     * dieselbe Konvention wie in {@code NeighborSampler.samplePackedLight}.
     *
     * <p>Kein Lock nötig: {@code LightStorage} ist lock-frei (s. Skill {@code licht-system}).
     */
    public int getSkyLight(int x, int y, int z) {
        if (y >= Chunk.HEIGHT) return 15; // über der Welt ist immer voller Himmel
        if (y < 0) return 0;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) {
            return 15;
        }
        return chunk.light.get(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    /** Sichtbares Himmelslicht inklusive Wasserdaempfung eines gerade dargestellten LOD-Meshes. */
    public int getRenderedSkyLight(int x, int y, int z) {
        if (this.lodManager != null) {
            int lodLight = this.lodManager.visibleSkyLightAt(x, y, z);
            if (lodLight >= 0) return lodLight;
        }
        return this.getSkyLight(x, y, z);
    }

    /**
     * Blocklicht 0..15 (Fackeln, Lava) an Weltkoordinaten — das Gegenstück zu
     * {@link #getSkyLight} mit demselben {@link ChunkStatus#LIT}-Gate.
     *
     * <p>Ein Unterschied: fehlende oder noch unbelichtete Chunks liefern hier <b>0</b>, nicht 15.
     * „Unbekannt" heißt beim Himmelslicht „vermutlich hell", beim Blocklicht aber „vermutlich
     * keine Fackel in der Nähe" — sonst würden Hand und Drops an der Ladekante kurz aufglühen.
     */
    public int getBlockLight(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return 0;

        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) {
            return 0;
        }
        return chunk.blockLight.get(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    /**
     * Setzt einen Block (mit Nachbar-Updates für Verbindungen/Treppen-Ecken).
     *
     * @return true, wenn der Block geschrieben wurde; false, wenn der Zielchunk nicht READY
     *         ist (Ladefront/Unload) — Aufrufer mit Folgewirkung (placeBlock, Fluids,
     *         FallingBlock) MÜSSEN das auswerten, sonst gehen Schreibzugriffe still verloren.
     */
    public boolean setBlock(int x, int y, int z, int block) {
        return this.setBlock(x, y, z, block, true);
    }

    /**
     * @param updateNeighbors true: betroffene Nachbarn (Zäune, Panes, Treppen)
     *                        rechnen ihren State neu. false vermeidet Rekursion
     *                        bei den dadurch ausgelösten Folge-Updates.
     */
    public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
        int old = this.getBlock(x, y, z);
        /* LevelChunk#setBlockState liefert bei identischer BlockState null; Level#setBlock
           bricht daraufhin ohne Licht-, Save- oder Nachbararbeit ab. Insbesondere darf ein
           Same-State-Write keinen Observer und keine Redstone-Kaskade vortäuschen. */
        if (old == block) return false;
        if (!this.setBlockRaw(x, y, z, block)) return false;
        this.manageBlockEntity(x, y, z, old, block);
        BlockState oldState = Blocks.getState(old);
        BlockState newState = Blocks.getState(block);
        if (EnergyNetworkManager.isConnector(oldState) || EnergyNetworkManager.isConnector(newState)
                || oldState.getBlock().getBlockEntityType() != newState.getBlock().getBlockEntityType()) {
            this.energyNetworks.invalidate();
        }
        if (oldState.getBlock() != newState.getBlock()) {
            oldState.getBlock().onRemoved(this, x, y, z, oldState, newState);
        }
        if (updateNeighbors) this.updateNeighbors(x, y, z);
        return true;
    }

    /**
     * Schreibt einen State mit Vanillas Block-Flag 2: keine allgemeinen Neighbor-Updates,
     * aber die gerichteten Shape-Updates des State-Wechsels bleiben aktiv. Observer hängen
     * genau an diesem Pfad; {@code setBlock(..., false)} wäre deshalb nicht gleichbedeutend.
     */
    public boolean setBlockWithShapeUpdates(int x, int y, int z, int block) {
        if (!this.setBlock(x, y, z, block, false)) return false;
        for (Direction direction : Direction.shapeUpdateValues()) {
            this.updateShapeStateAt(x + direction.offsetX(), y + direction.offsetY(),
                    z + direction.offsetZ(), direction.opposite());
        }
        return true;
    }

    /**
     * Platziert einen fertig berechneten Placement-State: setzt den Block (ohne Kaskade),
     * lässt den Block etwaige Mehrteil-Logik anwenden (z.B. obere Türhälfte über
     * {@link de.skyengine.game.world.block.Block#onPlaced}) und löst ERST DANACH die
     * Nachbar-Updates aus. Das Ordering ist entscheidend - sonst entfernt sich z.B. die
     * untere Türhälfte selbst, bevor die obere existiert.
     */
    public boolean placeBlock(int x, int y, int z, BlockState state) {
        return this.placeBlock(x, y, z, state, ItemStack.EMPTY);
    }

    public boolean placeBlock(int x, int y, int z, BlockState state, ItemStack sourceStack) {
        /* Schlägt der Schreibzugriff fehl (Chunk nicht READY), dürfen onPlaced/updateNeighbors
           NICHT laufen — PartsBehavior setzte sonst Geschwisterteile für einen Ursprung,
           der nie geschrieben wurde. */
        if (!this.setBlock(x, y, z, state.getId(), false)) return false;
        if (sourceStack != null && sourceStack.getCustomData() != null
                && this.getBlockEntity(x, y, z) instanceof PortableBlockEntity portable) {
            portable.loadPortable(sourceStack.getCustomData());
            this.markChunkModified(x, z);
        }
        state.getBlock().onPlaced(this, x, y, z, state);
        this.updateNeighbors(x, y, z);
        return true;
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

        /* Write-Lock: die BlockEntity-Map ist unsynchronisiert; der Autosave-IO-Thread iteriert
           sie unter readLock (ChunkSerializer). Ohne writeLock racet diese Struktur-Mutation
           gegen den Save -> ConcurrentModificationException / torn Save. */
        chunk.writeLock().lock();
        try {
            if (oldType != null) chunk.removeBlockEntity(lx, y, lz);
            if (newType != null) {
                BlockEntity be = newType.create(new BlockPos(x, y, z), Blocks.getState(newId));
                be.setWorld(this);
                chunk.setBlockEntity(lx, y, lz, be);
            }
        } finally {
            chunk.writeLock().unlock();
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

        /* Alter Block VOR dem Write — die Licht-Aktualisierung braucht beide Opazitäten. */
        int oldBlock = chunk.getBlock(lx, y, lz);

        /* Write-Lock: serialisiert gegen laufende Worker-Mesh-Reads desselben Chunks. */
        chunk.writeLock().lock();
        try {
            chunk.setBlock(lx, y, lz, block);
        } finally {
            chunk.writeLock().unlock();
        }
        boolean playerChange = this.playerBlockChangeDepth > 0;
        chunk.markSectionDirty(sy, playerChange);

        /* Vertikale Section-Grenzen */
        if ((y & ChunkSection.MASK) == 0 && sy > 0) chunk.markSectionDirty(sy - 1, playerChange);
        if ((y & ChunkSection.MASK) == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) chunk.markSectionDirty(sy + 1, playerChange);


        /* An Chunk-Grenzen muss der Nachbar mit-remeshen, sonst bleiben dort falsche Faces */
        if (lx == 0) this.markDirtyColumn(cx - 1, cz, sy, y, playerChange);
        if (lx == ChunkSection.MASK) this.markDirtyColumn(cx + 1, cz, sy, y, playerChange);
        if (lz == 0) this.markDirtyColumn(cx, cz - 1, sy, y, playerChange);
        if (lz == ChunkSection.MASK) this.markDirtyColumn(cx, cz + 1, sy, y, playerChange);
        /* Chunk-ECKEN zusätzlich diagonal: dessen Fluid-Eckhöhen sampeln diese Zelle. */
        if (lx == 0 && lz == 0) this.markDirtyColumn(cx - 1, cz - 1, sy, y, playerChange);
        if (lx == 0 && lz == ChunkSection.MASK) this.markDirtyColumn(cx - 1, cz + 1, sy, y, playerChange);
        if (lx == ChunkSection.MASK && lz == 0) this.markDirtyColumn(cx + 1, cz - 1, sy, y, playerChange);
        if (lx == ChunkSection.MASK && lz == ChunkSection.MASK) this.markDirtyColumn(cx + 1, cz + 1, sy, y, playerChange);

        /* Himmelslicht nachziehen. Die Markierungen oben decken nur den 1-Block-Ring von
           Geometrie und AO ab — Licht reicht deutlich weiter (eine gekappte Direkt-Säule
           verdunkelt bis zum Boden, ein Loch in eine Höhle flutet 15 Blöcke in alle
           Richtungen, auch über Chunk-Grenzen). Die LightEngine markiert die betroffenen
           Sections deshalb selbst, inklusive ±1-Ring fürs Corner-Smoothing. */
        this.updateLight(chunk, cx, cz, lx, y, lz, oldBlock, block);

        /* Persistenz: Chunk ist seit dem letzten Save verändert. */
        chunk.markModified();
        return true;
    }

    /**
     * Sammelt das 3×3-Umfeld und lässt die {@link LightEngine} das Himmelslicht nachziehen.
     * Läuft synchron auf dem Render-/Tick-Thread — dem einzigen Block-Schreiber; Licht-Writes
     * sind lock-frei (siehe {@code LightStorage}), deshalb sind hier keine Locks nötig. Die
     * Engine-Instanz gehört exklusiv diesem Thread (die Worker haben ihre ThreadLocals).
     */
    private void updateLight(Chunk chunk, int cx, int cz, int lx, int y, int lz, int oldBlock, int newBlock) {
        PerformanceProfiler profiler = PerformanceProfiler.get();
        long started = profiler.begin();
        Chunk north = this.chunkManager.getChunk(cx, cz - 1);
        Chunk south = this.chunkManager.getChunk(cx, cz + 1);
        Chunk west = this.chunkManager.getChunk(cx - 1, cz);
        Chunk east = this.chunkManager.getChunk(cx + 1, cz);
        /* Reihenfolge NW, NE, SW, SE — wie ChunkManager.getDiagonalsAtLeast und NeighborSampler. */
        this.lightDiagonals[0] = this.chunkManager.getChunk(cx - 1, cz - 1);
        this.lightDiagonals[1] = this.chunkManager.getChunk(cx + 1, cz - 1);
        this.lightDiagonals[2] = this.chunkManager.getChunk(cx - 1, cz + 1);
        this.lightDiagonals[3] = this.chunkManager.getChunk(cx + 1, cz + 1);
        this.lightEngine.onBlockChanged(chunk, north, south, west, east, this.lightDiagonals,
                lx, y, lz, oldBlock, newBlock);
        profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_LIGHT_UPDATE, 0, started);
    }

    /* ------------------- Massen-Zerstörung (Explosion) ------------------- */

    /** Zellen eines Chunks im Batch-Pfad (gepackte Lokalpositionen + Alt-IDs + Dirty-Masken). */
    private static final class BreakBatchGroup {
        final Chunk chunk; // null = Chunk nicht editierbar (Ladefront) — Zellen verwerfen
        int[] packedPos = new int[64];
        int[] oldIds = new int[64];
        int count;
        int ownMask;
        final int[] borderMasks = new int[8]; // N,S,W,E,NW,NE,SW,SE

        BreakBatchGroup(Chunk chunk) {
            this.chunk = chunk;
        }

        void add(int packed, int oldId) {
            if (this.count == this.packedPos.length) {
                this.packedPos = java.util.Arrays.copyOf(this.packedPos, this.count * 2);
                this.oldIds = java.util.Arrays.copyOf(this.oldIds, this.count * 2);
            }
            this.packedPos[this.count] = packed;
            this.oldIds[this.count] = oldId;
            this.count++;
        }
    }

    /* Randnachbar-Offsets für borderMasks (Index-Reihenfolge N,S,W,E,NW,NE,SW,SE). */
    private static final int[] BORDER_DX = {0, 0, -1, 1, -1, 1, -1, 1};
    private static final int[] BORDER_DZ = {-1, 1, 0, 0, -1, -1, 1, 1};

    /** Batch-Gruppe fuer Structure-/Editor-Writes; Layout entspricht dem Licht-Batch. */
    private static final class SetBatchGroup {
        final Chunk chunk;
        int[] packed = new int[64], oldIds = new int[64], newIds = new int[64];
        int count, ownMask;
        final int[] borderMasks = new int[8];
        SetBatchGroup(Chunk chunk) { this.chunk = chunk; }
        void add(int position, int oldId, int newId) {
            if (count == packed.length) {
                packed = java.util.Arrays.copyOf(packed, count * 2);
                oldIds = java.util.Arrays.copyOf(oldIds, count * 2);
                newIds = java.util.Arrays.copyOf(newIds, count * 2);
            }
            packed[count] = position; oldIds[count] = oldId; newIds[count] = newId; count++;
        }
    }

    /**
     * Schreibt viele unabhaengige Blockstates mit einem Lock/Dirty-/Licht-Batch pro Chunk.
     * Gedacht fuer Structure-Placement; Neighbor-Updates erfolgen nach dem kompletten Paste.
     *
     * @return Zahl tatsaechlich geaenderter Zellen; nicht-READY-Chunks werden abgewiesen.
     */
    public int setBlocksBatch(long[] positions, int[] states, int count) {
        if (count < 0 || count > positions.length || count > states.length) {
            throw new IllegalArgumentException("Ungueltige Batch-Laenge " + count);
        }
        LongObjMap<SetBatchGroup> groups = new LongObjMap<>(64);
        for (int i = 0; i < count; i++) {
            int x = BlockPos.unpackX(positions[i]), y = BlockPos.unpackY(positions[i]), z = BlockPos.unpackZ(positions[i]);
            if (y < 0 || y >= Chunk.HEIGHT) continue;
            int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
            long key = Chunk.key(cx, cz);
            SetBatchGroup group = groups.get(key);
            if (group == null) {
                Chunk candidate = this.chunkManager.getChunk(cx, cz);
                group = new SetBatchGroup(candidate != null && candidate.status == ChunkStatus.READY ? candidate : null);
                groups.put(key, group);
            }
            if (group.chunk == null) continue;
            int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
            int oldId = group.chunk.getBlock(lx, y, lz), newId = states[i];
            if (oldId == newId) continue;
            group.add(lx | (lz << 5) | (y << 10), oldId, newId);
            int sy = y >> ChunkSection.SHIFT;
            int mask = 1 << sy;
            if ((y & ChunkSection.MASK) == 0 && sy > 0) mask |= 1 << (sy - 1);
            if ((y & ChunkSection.MASK) == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) mask |= 1 << (sy + 1);
            group.ownMask |= mask;
            boolean west = lx == 0, east = lx == ChunkSection.MASK;
            boolean north = lz == 0, south = lz == ChunkSection.MASK;
            if (north) group.borderMasks[0] |= mask;
            if (south) group.borderMasks[1] |= mask;
            if (west) group.borderMasks[2] |= mask;
            if (east) group.borderMasks[3] |= mask;
            if (west && north) group.borderMasks[4] |= mask;
            if (east && north) group.borderMasks[5] |= mask;
            if (west && south) group.borderMasks[6] |= mask;
            if (east && south) group.borderMasks[7] |= mask;
        }

        List<SetBatchGroup> activeGroups = new ArrayList<>(groups.size());
        for (int gi = 0, gn = groups.tableSize(); gi < gn; gi++) {
            SetBatchGroup group = groups.valueAt(gi);
            if (group != null && group.chunk != null && group.count > 0) activeGroups.add(group);
        }
        /* Die Map-Slotreihenfolge ist ein Implementierungsdetail. Eine feste Weltreihenfolge
           macht Profiling und Regressionstests reproduzierbar; fuer die Lichtkorrektheit darf
           die Reihenfolge nach der Phasentrennung keine Rolle mehr spielen. */
        activeGroups.sort(Comparator.comparingInt((SetBatchGroup g) -> g.chunk.chunkZ)
                .thenComparingInt(g -> g.chunk.chunkX));

        int changed = 0;
        boolean playerChange = this.playerBlockChangeDepth > 0;
        /* Phase 1: ALLE Blockdaten schreiben. Licht darf erst danach laufen, damit jeder
           3x3-Kontext bereits den endgueltigen Zustand seiner Nachbar-Chunks liest. */
        for (SetBatchGroup group : activeGroups) {
            Chunk chunk = group.chunk;
            chunk.writeLock().lock();
            try {
                for (int i = 0; i < group.count; i++) {
                    int packed = group.packed[i];
                    chunk.setBlock(packed & 31, (packed >> 10) & 511, (packed >> 5) & 31, group.newIds[i]);
                }
            } finally {
                chunk.writeLock().unlock();
            }
            changed += group.count;
            chunk.markSectionsDirty(group.ownMask, playerChange);
            chunk.markModified();
            for (int b = 0; b < 8; b++) {
                if (group.borderMasks[b] == 0) continue;
                Chunk neighbor = this.chunkManager.getChunk(chunk.chunkX + BORDER_DX[b], chunk.chunkZ + BORDER_DZ[b]);
                if (neighbor != null && neighbor.status.isAtLeast(ChunkStatus.LIT)) {
                    neighbor.markSectionsDirty(group.borderMasks[b], playerChange);
                }
            }
            int baseX = chunk.chunkX << ChunkSection.SHIFT, baseZ = chunk.chunkZ << ChunkSection.SHIFT;
            for (int i = 0; i < group.count; i++) {
                int packed = group.packed[i];
                int wx = baseX + (packed & 31), wy = (packed >> 10) & 511, wz = baseZ + ((packed >> 5) & 31);
                this.manageBlockEntity(wx, wy, wz, group.oldIds[i], group.newIds[i]);
            }
        }

        /* Phase 2: Heightmaps und beide Lichtebenen gegen den finalen Blockzustand pflegen.
           Phase 3 gleicht danach die betroffenen Raender bis zum monotonen Fixpunkt ab. */
        PerformanceProfiler profiler = PerformanceProfiler.get();
        long lightStarted = profiler.begin();
        for (SetBatchGroup group : activeGroups) this.updateSetBatchLight(group);
        this.stabilizeSetBatchBorders(activeGroups);
        profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_LIGHT_UPDATE, 0, lightStarted);

        for (SetBatchGroup group : activeGroups) {
            int baseX = group.chunk.chunkX << ChunkSection.SHIFT, baseZ = group.chunk.chunkZ << ChunkSection.SHIFT;
            for (int i = 0; i < group.count; i++) {
                int packed = group.packed[i];
                BlockState oldState = Blocks.getState(group.oldIds[i]);
                BlockState newState = Blocks.getState(group.newIds[i]);
                if (oldState.getBlock() != newState.getBlock()) oldState.getBlock().onRemoved(this,
                        baseX + (packed & 31), (packed >> 10) & 511,
                        baseZ + ((packed >> 5) & 31), oldState, newState);
            }
        }
        return changed;
    }

    /** Licht-Update einer bereits vollstaendig geschriebenen Structure-/Editor-Chunkgruppe. */
    private void updateSetBatchLight(SetBatchGroup group) {
        Chunk chunk = group.chunk;
        int cx = chunk.chunkX, cz = chunk.chunkZ;
        Chunk north = this.chunkManager.getChunk(cx, cz - 1);
        Chunk south = this.chunkManager.getChunk(cx, cz + 1);
        Chunk west = this.chunkManager.getChunk(cx - 1, cz);
        Chunk east = this.chunkManager.getChunk(cx + 1, cz);
        this.fillLightDiagonals(cx, cz);
        this.lightEngine.onBlocksChanged(chunk, north, south, west, east, this.lightDiagonals,
                group.packed, group.oldIds, group.count);
    }

    /**
     * Rand-Fixpunkt nach einem mehrchunkigen Edit. exchangeBorders erhoeht Werte ausschliesslich;
     * damit terminiert die Schleife sicher. Der Lichtradius 15 ist kleiner als ein 32er-Chunk,
     * also bleibt die Ausbreitung im Zentrum plus dessen direktem Ein-Chunk-Halo.
     */
    private void stabilizeSetBatchBorders(List<SetBatchGroup> groups) {
        boolean changed;
        do {
            changed = false;
            for (SetBatchGroup group : groups) {
                Chunk chunk = group.chunk;
                int cx = chunk.chunkX, cz = chunk.chunkZ;
                Chunk north = this.chunkManager.getChunk(cx, cz - 1);
                Chunk south = this.chunkManager.getChunk(cx, cz + 1);
                Chunk west = this.chunkManager.getChunk(cx - 1, cz);
                Chunk east = this.chunkManager.getChunk(cx + 1, cz);
                this.fillLightDiagonals(cx, cz);
                changed |= this.lightEngine.exchangeBorders(
                        chunk, north, south, west, east, this.lightDiagonals);
            }
        } while (changed);
    }

    private void fillLightDiagonals(int cx, int cz) {
        this.lightDiagonals[0] = this.chunkManager.getChunk(cx - 1, cz - 1);
        this.lightDiagonals[1] = this.chunkManager.getChunk(cx + 1, cz - 1);
        this.lightDiagonals[2] = this.chunkManager.getChunk(cx - 1, cz + 1);
        this.lightDiagonals[3] = this.chunkManager.getChunk(cx + 1, cz + 1);
    }

    /**
     * Zerstört viele Blöcke als Batch (Explosion): gruppiert nach Chunk, EIN Write-Lock,
     * EIN Dirty-CAS und EIN Licht-Update ({@link LightEngine#onBlocksChanged}) pro Chunk statt
     * pro Block. Semantik je Zelle wie {@code setBlock(x, y, z, AIR)} — zusätzlich läuft für
     * Blöcke mit BlockEntity vorher {@code onBreak} (Truheninhalt fiel bei Explosionen sonst
     * still weg). Die Nachbar-Updates laufen nicht pro Zelle, sondern zum Schluss EINMAL über die
     * Krater-Schale ({@link #updateBlastShell}). Zellen in nicht-READY-Chunks werden wie bei
     * setBlockRaw verworfen. Nur Tick-Thread.
     *
     * @param positions Welt-Positionen als {@link BlockPos#asLong}-Keys
     */
    public void breakBlocksBatch(long[] positions, int count) {
        breakBlocksBatch(positions, count, null);
    }

    /** Empfänger für akzeptierte Batch-Zellen, solange State und BlockEntity existieren. */
    @FunctionalInterface
    public interface BatchBreakConsumer {
        void accept(long position, BlockState state);
    }

    public void breakBlocksBatch(long[] positions, int count, BatchBreakConsumer beforeBreak) {
        de.skyengine.utils.collect.LongObjMap<BreakBatchGroup> groups =
                new de.skyengine.utils.collect.LongObjMap<>(64);

        /* Pass 1: gruppieren, Alt-IDs lesen (dieser Thread ist der einzige Block-Schreiber)
           und onBreak für BlockEntity-Blöcke VOR dem Write laufen lassen (Muster
           updateStateAt-Selbstentfernung). */
        for (int i = 0; i < count; i++) {
            long pos = positions[i];
            int x = BlockPos.unpackX(pos), y = BlockPos.unpackY(pos), z = BlockPos.unpackZ(pos);
            if (y < 0 || y >= Chunk.HEIGHT) continue;
            int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
            long chunkKey = Chunk.key(cx, cz);
            BreakBatchGroup group = groups.get(chunkKey);
            if (group == null) {
                Chunk chunk = this.chunkManager.getChunk(cx, cz);
                group = new BreakBatchGroup(chunk != null && chunk.status == ChunkStatus.READY ? chunk : null);
                groups.put(chunkKey, group);
            }
            if (group.chunk == null) continue;

            int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
            int oldId = group.chunk.getBlock(lx, y, lz);
            if (oldId == Blocks.AIR) continue;
            BlockState oldState = Blocks.getState(oldId);
            if (beforeBreak != null) beforeBreak.accept(pos, oldState);
            if (oldState.getBlock().getBlockEntityType() != null) {
                oldState.getBlock().onBreak(this, x, y, z, oldState);
            }
            group.add((lx & 31) | ((lz & 31) << 5) | ((y & 511) << 10), oldId);

            /* Dirty-Masken akkumulieren: eigene Section + vertikale Grenzen (wie setBlockRaw),
               Randspalten zusätzlich für die 4 Kardinal- und 4 Diagonal-Nachbarn
               (Regeln aus setBlockRaw/markDirtyColumn, hier als Union über alle Zellen). */
            int sy = y >> ChunkSection.SHIFT;
            int columnMask = 1 << sy;
            if ((y & ChunkSection.MASK) == 0 && sy > 0) columnMask |= 1 << (sy - 1);
            if ((y & ChunkSection.MASK) == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) columnMask |= 1 << (sy + 1);
            group.ownMask |= columnMask;
            boolean west = lx == 0, east = lx == ChunkSection.MASK;
            boolean north = lz == 0, south = lz == ChunkSection.MASK;
            if (north) group.borderMasks[0] |= columnMask;
            if (south) group.borderMasks[1] |= columnMask;
            if (west) group.borderMasks[2] |= columnMask;
            if (east) group.borderMasks[3] |= columnMask;
            if (west && north) group.borderMasks[4] |= columnMask;
            if (east && north) group.borderMasks[5] |= columnMask;
            if (west && south) group.borderMasks[6] |= columnMask;
            if (east && south) group.borderMasks[7] |= columnMask;
        }

        /* Pass 2 je Chunk: Writes unter EINEM Lock, dann Dirty/BE/Licht. */
        for (int gi = 0, gn = groups.tableSize(); gi < gn; gi++) {
            BreakBatchGroup group = groups.valueAt(gi);
            if (group == null || group.chunk == null || group.count == 0) continue;
            Chunk chunk = group.chunk;

            chunk.writeLock().lock();
            try {
                for (int i = 0; i < group.count; i++) {
                    int packed = group.packedPos[i];
                    chunk.setBlock(packed & 31, (packed >> 10) & 511, (packed >> 5) & 31, Blocks.AIR);
                }
            } finally {
                chunk.writeLock().unlock();
            }
            chunk.markSectionsDirty(group.ownMask);
            chunk.markModified();

            /* Rand-Remeshes der Nachbarn (Union; Filter wie markDirty: ab LIT). */
            for (int b = 0; b < 8; b++) {
                if (group.borderMasks[b] == 0) continue;
                Chunk neighbor = this.chunkManager.getChunk(chunk.chunkX + BORDER_DX[b], chunk.chunkZ + BORDER_DZ[b]);
                if (neighbor != null && neighbor.status.isAtLeast(ChunkStatus.LIT)) {
                    neighbor.markSectionsDirty(group.borderMasks[b]);
                }
            }

            /* BlockEntities abräumen (macht intern den eigenen Write-Lock). */
            for (int i = 0; i < group.count; i++) {
                if (Blocks.getState(group.oldIds[i]).getBlock().getBlockEntityType() == null) continue;
                int packed = group.packedPos[i];
                int x = (chunk.chunkX << ChunkSection.SHIFT) + (packed & 31);
                int z = (chunk.chunkZ << ChunkSection.SHIFT) + ((packed >> 5) & 31);
                this.manageBlockEntity(x, (packed >> 10) & 511, z, group.oldIds[i], Blocks.AIR);
            }

            /* EIN Licht-Update für alle Zellen dieses Chunks (statt n Einzel-Flutungen). */
            PerformanceProfiler profiler = PerformanceProfiler.get();
            long lightStarted = profiler.begin();
            int cx = chunk.chunkX, cz = chunk.chunkZ;
            Chunk north = this.chunkManager.getChunk(cx, cz - 1);
            Chunk south = this.chunkManager.getChunk(cx, cz + 1);
            Chunk west = this.chunkManager.getChunk(cx - 1, cz);
            Chunk east = this.chunkManager.getChunk(cx + 1, cz);
            this.lightDiagonals[0] = this.chunkManager.getChunk(cx - 1, cz - 1);
            this.lightDiagonals[1] = this.chunkManager.getChunk(cx + 1, cz - 1);
            this.lightDiagonals[2] = this.chunkManager.getChunk(cx - 1, cz + 1);
            this.lightDiagonals[3] = this.chunkManager.getChunk(cx + 1, cz + 1);
            this.lightEngine.onBlocksChanged(chunk, north, south, west, east, this.lightDiagonals,
                    group.packedPos, group.oldIds, group.count);
            profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_LIGHT_UPDATE, 0, lightStarted);
        }

        /* Erst nachdem ALLE Batch-Writes sichtbar sind: Vanilla-Post-Removal-Hooks duerfen
           auch ueber Chunkgrenzen keinen noch nicht abgearbeiteten Altblock beobachten. */
        for (int gi = 0, gn = groups.tableSize(); gi < gn; gi++) {
            BreakBatchGroup group = groups.valueAt(gi);
            if (group == null || group.chunk == null) continue;
            int baseX = group.chunk.chunkX << ChunkSection.SHIFT;
            int baseZ = group.chunk.chunkZ << ChunkSection.SHIFT;
            for (int i = 0; i < group.count; i++) {
                int packed = group.packedPos[i];
                BlockState oldState = Blocks.getState(group.oldIds[i]);
                oldState.getBlock().onRemoved(this,
                        baseX + (packed & 31), (packed >> 10) & 511,
                        baseZ + ((packed >> 5) & 31), oldState, Blocks.getState(Blocks.AIR));
            }
        }

        this.updateBlastShell(groups);
    }

    /* Markierungen für die Schalen-Map in updateBlastShell. */
    private static final int BLAST_DESTROYED = 1;
    private static final int BLAST_SHELL = 2;

    /**
     * Pass 3 der Massen-Zerstörung: Nachbar-State-Updates auf der <b>Krater-Schale</b>. Die Writes
     * oben laufen wie {@code setBlock(..., updateNeighbors=false)} — ohne diesen Pass erführen
     * hängende Blöcke (Fackel, hohe Pflanze, Türhälfte) nie vom Stützverlust, Sand/Kies bliebe
     * schweben, und vor allem plante nie jemand einen Fluid-Tick: {@code
     * FluidBehavior.onNeighborUpdate} ist der EINZIGE Weg dorthin (Fluids ticken nicht zufällig),
     * also flösse Wasser nie in den Krater nach.
     *
     * <p>Besucht werden nur die Zellen, die an eine zerstörte grenzen und selbst nicht zerstört
     * wurden: die zerstörten sind jetzt Luft, und {@link #updateStateAt} steigt bei Luft ohnehin
     * sofort aus. Läuft NACH allen Writes aller Chunks — sonst entschiede ein Behavior anhand von
     * Blöcken, die im selben Batch gleich verschwinden.
     */
    private void updateBlastShell(de.skyengine.utils.collect.LongObjMap<BreakBatchGroup> groups) {
        de.skyengine.utils.collect.LongIntMap cells = new de.skyengine.utils.collect.LongIntMap(256);

        /* Runde 1: alle tatsächlich zerstörten Zellen markieren (nicht die Eingabe-Positionen —
           verworfene Zellen aus nicht-READY Chunks und Luft stehen nicht in den Gruppen). */
        for (int gi = 0, gn = groups.tableSize(); gi < gn; gi++) {
            BreakBatchGroup group = groups.valueAt(gi);
            if (group == null || group.chunk == null) continue;
            int baseX = group.chunk.chunkX << ChunkSection.SHIFT;
            int baseZ = group.chunk.chunkZ << ChunkSection.SHIFT;
            for (int i = 0; i < group.count; i++) {
                int packed = group.packedPos[i];
                cells.put(BlockPos.asLong(baseX + (packed & 31), (packed >> 10) & 511,
                        baseZ + ((packed >> 5) & 31)), BLAST_DESTROYED);
            }
        }

        /* Runde 2: die 6 Achsen-Nachbarn einsammeln, die nicht selbst zerstört wurden. Iteriert
           über die Gruppen, nicht über die Map — die wächst hier ja gerade. */
        for (int gi = 0, gn = groups.tableSize(); gi < gn; gi++) {
            BreakBatchGroup group = groups.valueAt(gi);
            if (group == null || group.chunk == null) continue;
            int baseX = group.chunk.chunkX << ChunkSection.SHIFT;
            int baseZ = group.chunk.chunkZ << ChunkSection.SHIFT;
            for (int i = 0; i < group.count; i++) {
                int packed = group.packedPos[i];
                int x = baseX + (packed & 31);
                int y = (packed >> 10) & 511;
                int z = baseZ + ((packed >> 5) & 31);
                for (Direction d : Direction.sharedValues()) {
                    int ny = y + d.offsetY();
                    if (ny < 0 || ny >= Chunk.HEIGHT) continue;
                    long key = BlockPos.asLong(x + d.offsetX(), ny, z + d.offsetZ());
                    if (!cells.containsKey(key)) cells.put(key, BLAST_SHELL);
                }
            }
        }

        /* Runde 3: nur die Schale aktualisieren. Selbst-Entfernungen kaskadieren dabei über den
           Einzelpfad (setBlock mit Ring) weiter — das terminiert, weil jeder Schritt einen Block
           endgültig entfernt. */
        for (int i = 0, n = cells.tableSize(); i < n; i++) {
            if (!cells.usedAt(i) || cells.valueAt(i) != BLAST_SHELL) continue;
            long pos = cells.keyAt(i);
            this.updateStateAt(BlockPos.unpackX(pos), BlockPos.unpackY(pos), BlockPos.unpackZ(pos));
        }
    }

    /**
     * Lässt den geänderten Block und seine 4 horizontalen Nachbarn ihren State
     * neu berechnen (Verbindungen, Treppen-Ecken). Reine State-Änderungen bleiben
     * ein Ring ohne Kaskade; entfernt sich ein Block dabei selbst (z.B. Tall Grass
     * nach Stützverlust), löst diese Entfernung einen Folge-Ring aus, damit
     * abhängige Blöcke (Tür-/Pflanzen-Oberhälfte) mitbenachrichtigt werden.
     *
     * <p>Public für Redstone-Quellen, die einen FREMDEN Block stark powern (Knopf →
     * Trägerblock, Fackel → Block darüber, Verstärker → Block davor): dessen Nachbarn
     * erfahren die Signaländerung nur über einen zweiten Ring um das starke Ziel —
     * genau zwei Ringe, weiterhin keine Kaskade.</p>
     */
    public void updateNeighbors(int x, int y, int z) {
        /* Engine-spezifischer Eigenabgleich fuer Placement-States (z.B. Doppeltruhen). */
        this.updateStateAt(x, y, z);

        /* Vanilla trennt NeighborUpdater.UPDATE_ORDER von BlockBehaviour.UPDATE_SHAPE_ORDER.
           Die allgemeine Kette wird vollstaendig abgearbeitet, bevor setBlock die Shape-
           Updates startet. Das ist bei richtungsabhaengigem Redstone beobachtbar. */
        for (Direction d : Direction.neighborUpdateValues()) {
            this.updateGeneralStateAt(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
        }
        for (Direction d : Direction.shapeUpdateValues()) {
            this.updateShapeStateAt(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ(), d.opposite());
        }
    }

    /**
     * Exakter gemeinsamer Pfad von {@code RedstoneTorchBlock.notifyNeighbors} und der ersten
     * Phase von {@code RedStoneWireBlock.affectNeighborsAfterRemoval}: Fuer jede
     * der sechs angrenzenden Zellen in {@code Direction.values()}-Reihenfolge startet Vanilla
     * einen eigenen allgemeinen Sechser-Ring in {@code NeighborUpdater.UPDATE_ORDER}. Dadurch
     * wird die Ursprungszelle sechsmal erreicht und weitere Zellen mehrfach. Diese Duplikate und
     * ihre verschachtelte Reihenfolge sind bei Redstone beobachtbar und duerfen nicht zu einem
     * Radius-2-Diamanten zusammengefasst werden. Shape-Updates gehoeren nicht zu diesem Aufruf.
     */
    public void updateGeneralNeighborsAroundAdjacentCells(int x, int y, int z) {
        for (Direction outer : Direction.vanillaValues()) {
            int centerX = x + outer.offsetX();
            int centerY = y + outer.offsetY();
            int centerZ = z + outer.offsetZ();
            this.updateGeneralNeighborsAt(centerX, centerY, centerZ);
        }
    }

    /** Vanillas {@code Level.updateNeighborsAt}: nur allgemeine Updates, keine Shape-Updates. */
    public void updateGeneralNeighborsAt(int x, int y, int z) {
        for (Direction direction : Direction.neighborUpdateValues()) {
            this.updateGeneralStateAt(x + direction.offsetX(),
                    y + direction.offsetY(), z + direction.offsetZ());
        }
    }

    /**
     * Vanillas {@code updateNeighborsAtExceptFromFacing}: allgemeiner Sechser-Ring ohne die
     * angegebene Seite. Redstone-Staub nutzt ihn beim manuellen Punkt/Kreuz-Formwechsel an
     * leitenden Nachbarblöcken; die Rückrichtung zum Staub wird dabei ausgelassen.
     */
    public void updateGeneralNeighborsAtExceptFromFacing(int x, int y, int z,
                                                          Direction excluded) {
        for (Direction direction : Direction.neighborUpdateValues()) {
            if (direction == excluded) continue;
            this.updateGeneralStateAt(x + direction.offsetX(),
                    y + direction.offsetY(), z + direction.offsetZ());
        }
    }

    /**
     * Gemeinsamer Vanilla-Pfad von {@code DiodeBlock.updateNeighborsInFront} und
     * {@code ObserverBlock.updateNeighborsInFront}: zuerst der Ausgangsblock selbst, danach
     * dessen allgemeine Nachbarn ohne die zur Quelle zurueckweisende Seite.
     */
    public void updateDirectionalOutputNeighbors(int x, int y, int z, Direction output) {
        int targetX = x + output.offsetX();
        int targetY = y + output.offsetY();
        int targetZ = z + output.offsetZ();
        this.updateGeneralStateAt(targetX, targetY, targetZ);
        Direction towardDiode = output.opposite();
        for (Direction direction : Direction.neighborUpdateValues()) {
            if (direction == towardDiode) continue;
            this.updateGeneralStateAt(targetX + direction.offsetX(),
                    targetY + direction.offsetY(), targetZ + direction.offsetZ());
        }
    }

    /**
     * Lässt genau EINE Zelle ihren State neu berechnen (schmaler Wrapper um
     * {@link #updateStateAt}) — für die gezielte Empfänger-Benachrichtigung des
     * Staub-Evaluators und anderer gezielter Update-Pfade.
     */
    public void updateBlockStateAt(int x, int y, int z) {
        this.updateStateAt(x, y, z);
    }

    /**
     * Groups the source-to-moving-piston writes of one piston action. Behaviors use this
     * scope to distinguish an actual removal from a block that is merely being relocated.
     */
    public void runPistonBlockMove(Runnable action) {
        this.pistonBlockMoveDepth++;
        try {
            action.run();
        } finally {
            this.pistonBlockMoveDepth--;
        }
    }

    public boolean isPistonBlockMove() {
        return this.pistonBlockMoveDepth > 0;
    }

    /**
     * Nachbehandlung eines von einem Kolben materialisierten Blocks. Vanilla berechnet zuerst
     * dessen eigene gerichtete Nachbarformen und benachrichtigt danach die Umgebung. Der eigene
     * Shape-Pass ist insbesondere fuer Observer wichtig: ihr Ankunftspuls entsteht aus dem
     * Update ihrer Vorderseite, nicht aus einem kuenstlichen pauschalen Puls-Hook.
     */
    public void updatePistonMovedBlock(int x, int y, int z) {
        for (Direction direction : Direction.shapeUpdateValues()) {
            this.updateShapeStateAt(x, y, z, direction);
            if (this.getBlock(x, y, z) == Blocks.AIR) return;
        }
        for (Direction direction : Direction.neighborUpdateValues()) {
            this.updateGeneralStateAt(x + direction.offsetX(),
                    y + direction.offsetY(), z + direction.offsetZ());
        }
        for (Direction direction : Direction.shapeUpdateValues()) {
            this.updateShapeStateAt(x + direction.offsetX(), y + direction.offsetY(),
                    z + direction.offsetZ(), direction.opposite());
        }
    }

    private void updateStateAt(int x, int y, int z) {
        this.updateStateAt(x, y, z, null);
    }

    private void updateStateAt(int x, int y, int z, Direction changedDirection) {
        this.updateStateAt(x, y, z, changedDirection, UpdateKind.COMBINED);
    }

    protected void updateGeneralStateAt(int x, int y, int z) {
        this.updateStateAt(x, y, z, null, UpdateKind.GENERAL);
    }

    private void updateShapeStateAt(int x, int y, int z, Direction changedDirection) {
        this.updateStateAt(x, y, z, changedDirection, UpdateKind.SHAPE);
    }

    private enum UpdateKind { COMBINED, GENERAL, SHAPE }

    private void updateStateAt(int x, int y, int z, Direction changedDirection, UpdateKind kind) {
        /* Nicht-READY-Zielchunk: setBlockRaw könnte ohnehin nicht schreiben — das Update
           würde still verlorengehen (Zaun im Nachbarchunk berechnet seine Verbindung dann
           NIE). Position parken; processDeferredStateUpdates zieht sie nach, sobald der
           konkrete Chunk-Objekt READY ist. chunk == null ist nur das Unload-Race → verwerfen. */
        Chunk targetChunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (targetChunk == null) return;
        if (targetChunk.status != ChunkStatus.READY) {
            this.parkStateUpdate(targetChunk, BlockPos.asLong(x, y, z));
            return;
        }

        int id = this.getBlock(x, y, z);
        if (id == Blocks.AIR) return;
        BlockState current = Blocks.getState(id);
        BlockState updated;
        if (kind == UpdateKind.GENERAL || changedDirection == null) {
            updated = current.getBlock().getStateForGeneralNeighborUpdate(this, x, y, z, current);
        } else {
            BlockState neighbor = Blocks.getState(this.getBlock(
                    x + changedDirection.offsetX(),
                    y + changedDirection.offsetY(),
                    z + changedDirection.offsetZ()));
            updated = current.getBlock().getStateForShapeUpdate(
                    this, x, y, z, current, changedDirection, neighbor);
            if (kind == UpdateKind.COMBINED) {
                updated = current.getBlock().getStateForGeneralNeighborUpdate(
                        this, x, y, z, updated);
            }
        }
        if (updated != current) {
            /* Selbst-Entfernen kaskadiert (Tür-/Tall-Grass-Oberhälfte nach Stützverlust),
               reine State-Änderungen (Verbindungen, Treppen) bewusst nicht. Keine Endlos-
               Kaskade möglich: jeder Kaskadenschritt entfernt einen Block endgültig. */
            boolean removed = updated.getId() == Blocks.AIR;
            /* Ein Block, der sich selbst entfernt, wird abgebaut — also VOR dem Setzen denselben
               onBreak-Hook laufen lassen wie beim Abbau durch den Spieler. Mehrteilige Blöcke
               unterdrücken nur den Loot der automatisch aufgeräumten Geschwisterhälfte; der Hook
               bleibt aktiv, damit BlockEntities und sonstige Seiteneffekte nicht verloren gehen. */
            if (removed) {
                if (current.getBlock().dropsWhenUnsupported()) {
                    this.dropBlockLoot(x, y, z, current,
                            LootContext.Cause.SUPPORT);
                }
                current.getBlock().onBreak(this, x, y, z, current);
            }
            if (this.setBlock(x, y, z, updated.getId(), removed) && !removed) {
                updated.getBlock().onStateChangedByNeighborUpdate(
                        this, x, y, z, current, updated);
            }
        }
    }

    /**
     * Merkt ein Block-Update für den NÄCHSTEN Tick vor (öffentliche Sicht auf das
     * Nachhol-Protokoll). Reguläre Vanilla-Nachbarupdates laufen unmittelbar; dieser Pfad ist
     * ausschließlich für explizite Nachhol- und Diagnosefälle bestimmt.
     */
    public void deferBlockUpdate(int x, int y, int z) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) {
            this.simulationTelemetry.recordDeferredDropped();
            return;
        }
        this.enqueueDeferredStateUpdate(BlockPos.asLong(x, y, z), chunk);
    }

    /**
     * Reiht ein Block-Event für dieselbe Tick-Runde ein (MCs Block-Event-Äquivalent, heute nur
     * Kolben): der Block dort bekommt beim nächsten Drain-Punkt {@code onBlockEvent} — im
     * SELBEN Game-Tick wie die auslösende Flanke, aber außerhalb der Nachbar-Update-Kaskade
     * (der Ort für schwere Multi-Block-Aktionen). Dedupliziert wie Vanilla über Position,
     * Blocktyp, Event-ID und Parameter; „tolerantes Feuern" wie beim Tick-Scheduler: der
     * Empfänger validiert seinen State selbst. Wartende Events werden im Chunk-Snapshot an den
     * Blocktyp beim Einreihen gebunden.
     */
    public void enqueueBlockEvent(int x, int y, int z) {
        this.enqueueBlockEvent(x, y, z, -1, 0);
    }

    /** Reiht ein Blockevent mit Vanillas Event-ID und Block-Parameter ein. */
    public void enqueueBlockEvent(int x, int y, int z, int eventId, int eventParam) {
        Identifier expectedBlock = Blocks.getState(this.getBlock(x, y, z)).getBlock().getIdentifier();
        BlockEvent event = new BlockEvent(BlockPos.asLong(x, y, z), expectedBlock, eventId, eventParam);
        if (!this.blockEvents.add(event)) return;
        this.blockEventRevision++;
        this.markChunkModified(x, z);
    }

    /**
     * Drained Block-Events bis die aktive Menge leer ist (MCs {@code runBlockEvents}). Events
     * außerhalb tickender Chunks wandern unverändert in {@code blockEventsToReschedule} und
     * werden erst im folgenden Game-Tick wieder aktiv.
     *
     * <p>Events, die der Drain selbst erzeugt, laufen noch in demselben Durchgang. Events aus
     * den nachfolgenden BlockEntity-Ticks bleiben dagegen bis zum nächsten Welttick stehen —
     * genau wie in {@code ServerLevel.tick}.
     *
     * <p>Terminierung entspricht Vanilla: identische Events werden dedupliziert und Empfänger
     * verwerfen veraltete Flanken anhand von Event-ID und aktuellem Signal.
     */
    private void processBlockEvents() {
        if (this.blockEventRescheduleGameTime != this.gameTime) {
            this.blockEventRescheduleGameTime = this.gameTime;
            if (!this.blockEventsToReschedule.isEmpty()) {
                this.blockEvents.addAll(this.blockEventsToReschedule);
                this.blockEventsToReschedule.clear();
                this.blockEventRevision++;
            }
        }
        while (!this.blockEvents.isEmpty()) {
            Iterator<BlockEvent> iterator = this.blockEvents.iterator();
            BlockEvent event = iterator.next();
            iterator.remove();
            this.blockEventRevision++;
            this.simulationTelemetry.recordBlockEventWave(1);
            long pos = event.position();
            int x = BlockPos.unpackX(pos), y = BlockPos.unpackY(pos), z = BlockPos.unpackZ(pos);
            this.markChunkModified(x, z);
            int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
            Chunk chunk = this.chunkManager.getChunk(cx, cz);
            if (chunk == null || chunk.status != ChunkStatus.READY || !this.isSimulated(cx, cz)) {
                if (this.blockEventsToReschedule.add(event)) this.blockEventRevision++;
                continue;
            }
            BlockState state = Blocks.getState(this.getBlock(x, y, z));
            if (state.getBlock().getIdentifier().equals(event.block())) {
                state.getBlock().onBlockEvent(
                        this, x, y, z, state, event.eventId(), event.eventParam());
            }
        }
    }

    private record BlockEvent(long position, Identifier block, int eventId, int eventParam) {}

    /** Merkt ein State-Update für den nächsten Tick vor (dedupliziert, FIFO). */
    private void enqueueDeferredStateUpdate(long pos, Chunk chunk) {
        Chunk previous = this.deferredStateUpdates.get(pos);
        if (previous == chunk) return;
        if (previous != null) {
            /* Dieselbe Koordinate gehört inzwischen einem Ersatz-Chunk: der aktuelle Anlass
               ersetzt den alten, ohne die stabile FIFO-Position unnötig zu verschieben. */
            this.deferredStateUpdates.put(pos, chunk);
            return;
        }
        if (this.deferredStateUpdates.size() >= MAX_DEFERRED_STATE_UPDATES) {
            /* Notventil gegen einen Erzeuger, der schneller einreiht als das Tick-Budget
               abarbeiten kann: den ältesten Eintrag deterministisch opfern. */
            Iterator<Map.Entry<Long, Chunk>> it = this.deferredStateUpdates.entrySet().iterator();
            it.next();
            it.remove();
            this.simulationTelemetry.recordDeferredDropped();
            this.logger.debug("Nachhol-Puffer für Block-Updates voll — ältester Eintrag verworfen");
        }
        this.deferredStateUpdates.put(pos, chunk);
    }

    /**
     * Parkt ein Update am aktuell geladenen, aber noch nicht editierbaren Chunk-Objekt.
     * Der globale Deckel verhindert Speicherwachstum, ohne READY-ferne Chunks pro Tick zu scannen.
     */
    private void parkStateUpdate(Chunk chunk, long pos) {
        long key = Chunk.key(chunk.chunkX, chunk.chunkZ);
        DeferredChunkUpdates pending = this.parkedStateUpdates.get(key);
        if (pending != null && pending.chunk != chunk) {
            this.parkedStateUpdateCount -= pending.positions.size();
            this.parkedStateUpdates.remove(key);
            pending = null;
        }
        if (pending != null && pending.positions.contains(pos)) return;
        if (this.parkedStateUpdateCount >= MAX_DEFERRED_STATE_UPDATES) {
            this.simulationTelemetry.recordDeferredDropped();
            this.logger.debug("Geparkter Nachhol-Puffer für Block-Updates voll — neuer Eintrag verworfen");
            return;
        }
        if (pending == null) {
            pending = new DeferredChunkUpdates(chunk);
            this.parkedStateUpdates.put(key, pending);
        }
        pending.positions.add(pos);
        this.parkedStateUpdateCount++;
        this.simulationTelemetry.recordDeferredRequeued();
    }

    /** Gibt nur Updates frei, die zum identischen Chunk-Objekt gehören; Ersatz-Chunks erben nichts. */
    private void releaseParkedStateUpdates(Chunk chunk) {
        DeferredChunkUpdates pending = this.parkedStateUpdates.remove(Chunk.key(chunk.chunkX, chunk.chunkZ));
        if (pending == null) return;
        this.parkedStateUpdateCount -= pending.positions.size();
        if (pending.chunk != chunk) return;
        for (long pos : pending.positions) this.enqueueDeferredStateUpdate(pos, chunk);
    }

    /** Entfernt Gruppen entladener Chunks nur dann, wenn sich die Chunk-Map tatsächlich geändert hat. */
    private void pruneParkedStateUpdates() {
        int removalVersion = this.chunkManager.getChunkRemovalVersion();
        if (removalVersion == this.deferredPruneRemovalVersion) return;
        this.parkedStateUpdates.removeIf((key, pending) -> {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (this.chunkManager.getChunk(chunkX, chunkZ) == pending.chunk) return false;
            this.parkedStateUpdateCount -= pending.positions.size();
            return true;
        });
        this.deferredPruneRemovalVersion = removalVersion;
    }

    /**
     * Arbeitet die FIFO für den nächsten Tick mit festem Budget ab. Nicht-READY Ziele werden
     * einmalig am Chunk geparkt und erst durch dessen sortierte READY-Meldung freigegeben.
     * Vor der Runde werden die Positionen entfernt, damit während der Verarbeitung erzeugte
     * Updates garantiert erst im nächsten Tick laufen.
     */
    private void processDeferredStateUpdates() {
        this.pruneParkedStateUpdates();
        if (this.deferredStateUpdates.isEmpty()) return;

        int n = Math.min(this.deferredStateUpdates.size(), MAX_DEFERRED_STATE_UPDATES_PER_TICK);
        this.simulationTelemetry.recordDeferredProcessed(n);
        if (this.deferredScratch.length < n) {
            this.deferredScratch = new long[n * 2];
            this.deferredChunkScratch = new Chunk[n * 2];
        }
        int i = 0;
        Iterator<Map.Entry<Long, Chunk>> iterator = this.deferredStateUpdates.entrySet().iterator();
        while (i < n && iterator.hasNext()) {
            Map.Entry<Long, Chunk> entry = iterator.next();
            this.deferredScratch[i] = entry.getKey();
            this.deferredChunkScratch[i++] = entry.getValue();
            iterator.remove();
        }

        for (int j = 0; j < n; j++) {
            long pos = this.deferredScratch[j];
            int x = BlockPos.unpackX(pos), y = BlockPos.unpackY(pos), z = BlockPos.unpackZ(pos);
            Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            if (chunk != this.deferredChunkScratch[j]) {
                this.simulationTelemetry.recordDeferredDropped();
                continue;
            }
            if (chunk.status != ChunkStatus.READY) {
                this.parkStateUpdate(chunk, pos);
                continue;
            }
            this.updateStateAt(x, y, z);
        }
    }

    private static final class DeferredChunkUpdates {
        private final Chunk chunk;
        private final LinkedHashSet<Long> positions = new LinkedHashSet<>();

        private DeferredChunkUpdates(Chunk chunk) {
            this.chunk = chunk;
        }
    }

    /**
     * Wie {@link #markDirty}, zusätzlich die vertikal angrenzende Section des Nachbar-Chunks:
     * das AO-Eck-Sample des Meshers greift ±1 auf ALLEN DREI Achsen. Liegt der geänderte Block
     * an einer Chunk-Randsäule UND an einer Section-Grenze in y, hängt also auch das AO in der
     * Section darüber/darunter des Nachbarn an ihm — ohne diese Markierung bliebe dort dauerhaft
     * ein falscher AO-Wert stehen.
     */
    private void markDirtyColumn(int cx, int cz, int sectionY, int y, boolean player) {
        this.markDirty(cx, cz, sectionY, player);
        if ((y & ChunkSection.MASK) == 0 && sectionY > 0) {
            this.markDirty(cx, cz, sectionY - 1, player);
        }
        if ((y & ChunkSection.MASK) == ChunkSection.MASK && sectionY < Chunk.SECTIONS - 1) {
            this.markDirty(cx, cz, sectionY + 1, player);
        }
    }

    private void markDirty(int cx, int cz, int sectionY, boolean player) {
        Chunk chunk = this.chunkManager.getChunk(cx, cz);

        /* isAtLeast(LIT) statt == READY: ein Nachbar im MESHING-Fenster (unlockRead vor dem
           READY-Set weckt genau hier den blockierten Writer) hat sein Mesh bereits aus den
           Vor-Edit-Daten gebaut — ein verworfener Marker hieße dauerhaft falsche Naht-Faces.
           Die Dirty-Bits überleben bis READY (der Erst-Mesh-Job konsumiert sie nicht), das
           Remesh-Gate in processRemeshes verlangt weiterhin READY — schlimmstenfalls also ein
           redundanter Remesh, exakt wie bei den Markierungen der LightEngine. */
        if (chunk != null && chunk.status.isAtLeast(ChunkStatus.LIT)) {
            chunk.markSectionDirty(sectionY, player);
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
                    if (Blocks.getState(this.getBlock(x, y, z)).getBlock()
                            == Blocks.getState(Blocks.MOVING_PISTON).getBlock()) continue;
                    BlockShape shape = this.getCollisionShape(x, y, z);
                    if (shape.isEmpty()) continue;
                    for (AABB local : shape.boxes()) {
                        boxes.add(local.copy().move(x, y, z));
                    }
                }
            }
        }
        /* Moving-Piston-Formen können bis zu eine Zelle aus ihrer technischen BE-Zelle
           hinausragen. Deshalb separat mit Ein-Zellen-Rand suchen; die Blockform selbst ist
           leer und kann im normalen Lauf nicht doppelt auftauchen. */
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = y0 - 1; y <= y1 + 1; y++) {
                    if (Blocks.getState(this.getBlock(x, y, z)).getBlock()
                            != Blocks.getState(Blocks.MOVING_PISTON).getBlock()) continue;
                    if (this.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity moving) {
                        moving.appendCollisionBoxes(boxes);
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
        if (Blocks.getState(id).getBlock() == Blocks.getState(Blocks.MOVING_PISTON).getBlock()
                && this.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity moving) {
            return moving.getCollisionShape();
        }
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

    /** Biom an Weltposition (pures Generator-Sampling) — z.B. fürs F3-Debug-Overlay. */
    public Biome biomeAt(int x, int z) {
        return this.generator.biomeAt(x, z);
    }

    public WorldGenerator getGenerator() {
        return generator;
    }

    /**
     * Erzeugt einen kurzlebigen, voll dekorierten Chunk aus dem aktuellen Generatorzustand.
     * Der Snapshot wird weder in den ChunkManager eingesetzt noch gespeichert.
     */
    public Chunk generateWorldgenSnapshot(int chunkX, int chunkZ) {
        if (this.imported) throw new IllegalStateException(
                "Importierte Welten besitzen keinen rekonstruierbaren Weltgenerator");
        Chunk snapshot = new Chunk(chunkX, chunkZ);
        this.generator.generate(snapshot);
        this.decorator.decorate(snapshot);
        return snapshot;
    }

    public boolean supportsRegeneration() {
        return !this.imported;
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public EnergyNetworkManager getEnergyNetworks() {
        return this.energyNetworks;
    }

    /** True only for READY chunks inside the current simulation circle. */
    public boolean isPositionSimulated(int blockX, int blockZ) {
        int chunkX = blockX >> ChunkSection.SHIFT;
        int chunkZ = blockZ >> ChunkSection.SHIFT;
        Chunk chunk = this.chunkManager.getChunk(chunkX, chunkZ);
        return chunk != null && chunk.status == ChunkStatus.READY && this.isSimulated(chunkX, chunkZ);
    }

}
