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
import de.skyengine.game.world.block.behavior.WorldScopedPositionMap;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.game.world.debug.SimulationTelemetry;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.generator.generators.VoidWorldGenerator;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.game.world.light.LightEngine;
import de.skyengine.game.world.lod.LodBlockAppearance;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.lod.StorageLodDataSource;
import de.skyengine.game.world.lod.WorldLodDataSource;
import de.skyengine.game.world.redstone.RedstonePower;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickQueue;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.game.world.tick.TickPriority;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.entity.EntityRenderer;
import de.skyengine.graphics.world.ChunkRenderer;
import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.collect.LongObjMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.function.Consumer;

public class World implements IInitializable, IDisposable {

    private final Logger logger = LogManager.getLogger(World.class.getName());

    private final String name;

    private final WorldGenerator generator;
    private final ChunkManager chunkManager;
    /* Chunk-Persistenz (Region-Dateien + eigener IO-Thread); Flush in dispose(). */
    private final WorldStorage storage;
    /* worldType "imported" — steuert u.a. die LOD-Datenquelle (Storage statt Generator). */
    private final boolean imported;
    private final ChunkRenderer chunkRenderer;
    /* Heightmap-LOD jenseits der Render-Distanz; erst in init() erzeugt (braucht gebackene Modelle) */
    private LodManager lodManager;
    /* Engine-Lebensdauer (GameContainer): Atlas + BlockEntity-Renderer überleben Welt-Austritte —
       die Welt hält nur Referenzen und disposed sie NICHT. */
    private final BlockTextureAtlas atlas;
    private final BlockEntityRenderDispatcher blockEntityRenderer;
    private final EntityRenderer entityRenderer = new EntityRenderer();
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
    private final LightEngine lightEngine = new LightEngine();
    private final Chunk[] lightDiagonals = new Chunk[4];

    /** Zufalls-Ticks pro nicht-leerer Section pro Tick (Wachstum, Verfall). 0 = aus.
     *  24 = MC-Parität: Vanilla zieht 3 je 16³-Subchunk, unsere Sections sind 32³
     *  (= 8 Subchunks) — der alte Wert 3 ließ Pflanzen ~8x langsamer wachsen. */
    private static final int RANDOM_TICK_SPEED = 24;

    /* Eigener Generator für die Zufalls-Ticks: ~75k Ziehungen/s — java.util.Random wäre
       ein CAS-Loop pro nextInt. this.random bleibt für die seltenen Nutzer (Drops/Spawns). */
    private final java.util.SplittableRandom randomTick = new java.util.SplittableRandom();

    /** Verzögerung, mit der geplante Ticks außerhalb der Simulations-Distanz erneut vorgemerkt werden. */
    private static final int OUT_OF_SIM_RESCHEDULE = 20;
    /** Notfallbudget gegen einen einzelnen Tick mit massenhaft gleichzeitig fälligen Block-Ticks. */
    private static final int MAX_SCHEDULED_TICKS_PER_TICK = 4096;

    /** Nur Chunks in diesem Radius (in Chunks) um den Spieler ticken (Random/Scheduled/Entities). */
    private int simulationDistance = 10;
    /* Spieler-Chunk des laufenden Ticks - Basis für isSimulated(). */
    private int playerChunkX, playerChunkZ;

    public World(String dirName, LevelData level, BlockTextureAtlas atlas, BlockEntityRenderDispatcher blockEntityRenderer) {
        this.name = dirName;
        this.atlas = atlas;
        this.blockEntityRenderer = blockEntityRenderer;

        /* Generator nach worldType: importierte Welten (MC-Import) kommen komplett aus den
           Region-Dateien und bekommen den Void-Generator ohne Features. */
        boolean imported = "imported".equals(level.worldType);
        this.imported = imported;
        if (imported) {
            this.generator = new VoidWorldGenerator(level.seed);
            this.chunkManager = new ChunkManager(this.generator,
                    new ChunkDecorator(this.generator, List.of()));
        } else {
            this.generator = new AlphaWorldGeneratorV2(level.seed);
            /* Feature-Pass (Dekoration): biome-abhaengige Baeume (featureId 0) */
            this.chunkManager = new ChunkManager(this.generator,
                    new ChunkDecorator(this.generator, List.of(new BiomeTreeFeature())));
            if (level.generatorVersion != null && level.generatorVersion != AlphaWorldGeneratorV2.VERSION) {
                this.logger.warning("Welt wurde mit Generator-Version " + level.generatorVersion
                        + " erstellt, Engine hat Version " + AlphaWorldGeneratorV2.VERSION
                        + " — ungespeicherte Gegenden können sich ändern (Nähte möglich)");
            }
        }
        this.chunkRenderer = new ChunkRenderer(this.chunkManager);

        /* Chunk-Persistenz: Snapshots liegen in saves/<dir>/region; generierte Welten
           speichern nur modifizierte Chunks (Tints werden beim Laden neu berechnet),
           importierte alle (Tints im Payload). */
        String generatorId = level.generator != null ? level.generator : (imported ? "minecraft_import" : "alpha_v2");
        int generatorVersion = level.generatorVersion != null ? level.generatorVersion
                : (imported ? 1 : AlphaWorldGeneratorV2.VERSION);
        this.storage = new WorldStorage(new File(GameDirectory.resolve("saves"), dirName + "/region"),
                this, this.generator, generatorId, generatorVersion, imported);
        this.chunkManager.setStorage(this.storage);
    }

    public String getName() {
        return name;
    }

    /** Injiziert der GameContainer nach der Welt-Erzeugung; erlaubt Sounds aus der Welt-Logik. */
    public void setSoundManager(SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    /** SoundManager der Welt oder {@code null} (dann bleiben Welt-Sounds stumm). */
    public SoundManager getSoundManager() {
        return this.soundManager;
    }

    public BlockEntityRenderDispatcher getBlockEntityRenderDispatcher() {
        return blockEntityRenderer;
    }

    @Override
    public void init() {
        this.chunkRenderer.init(this.atlas);
        /* LOD: abstrahierte Datenquelle + Block-Darstellung aus den gebackenen Modellen —
           erst nach dem Registry-Bake. Importierte Welten sampeln die Region-Snapshots
           (der Void-Generator kennt kein Terrain), generierte wie bisher Chunkdaten +
           Generator-Noise. */
        LodDataSource lodSource = this.imported
                ? new StorageLodDataSource(this.storage)
                : new WorldLodDataSource(this.chunkManager, this.generator);
        this.lodManager = new LodManager(lodSource, new LodBlockAppearance(), this.chunkManager);
        this.chunkRenderer.setLodManager(this.lodManager);
        this.chunkManager.setLodManager(this.lodManager); // Unload-Gate: erst entladen, wenn LOD deckt
        /* BlockEntity-Renderer werden beim Boot registriert/initialisiert (GameContainer). */
        this.entityRenderer.init(this.atlas.textures());
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
        this.simulationTelemetry.setEnabled(FrameProfiler.isEnabled());
        this.simulationTelemetry.beginTick();
        this.gameTime++;
        this.player = player;
        this.playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        if (this.processChunkReload()) {
            this.simulationTelemetry.endTick();
            return;
        }
        this.chunkManager.update(player);
        this.pruneTransientPositionStates();
        this.lodManager.update(player);
        this.processUnloadedChunkBoundaries();
        this.restorePendingScheduledTicks();
        this.processReadyChunks();
        this.processDeferredStateUpdates();
        this.tickScheduled();
        this.processBlockEvents(); // Vanillas einziger runBlockEvents-Punkt, VOR der BE-Phase
        this.tickRandomBlocks();
        this.tickBlockEntities();
        this.tickEntities();
        this.simulationTelemetry.endTick();
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
        this.readyChunkScratch.sort(Comparator
                .comparingInt((Chunk chunk) -> chunk.chunkX)
                .thenComparingInt(chunk -> chunk.chunkZ));
        LongIntMap reconciledWires = new LongIntMap(1024);
        Chunk previous = null;
        for (Chunk chunk : this.readyChunkScratch) {
            if (chunk == previous) continue;
            previous = chunk;
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
                    continue;
                }
                int cx = (int) Math.floor(entity.x) >> ChunkSection.SHIFT;
                int cz = (int) Math.floor(entity.z) >> ChunkSection.SHIFT;
                if (cx != chunk.chunkX || cz != chunk.chunkZ) {
                    it.remove();
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
            this.chunksWithEntities.add(chunk);
        }
    }

    /** Reiht eine Entity zum Spawnen ein (Übernahme im nächsten {@link #tickEntities}). */
    public void spawnEntity(Entity entity) {
        this.pendingEntities.add(entity);
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
    /* Fuse-Sound-Deckel: eine TNT-Kette spawnt hunderte Entities im selben Tick — ein
       Zisch pro Tick reicht, sonst gibt es einen OpenAL-Play-Sturm. */
    private long lastFuseSoundTick = Long.MIN_VALUE;

    public void spawnPrimedTnt(double x, double y, double z, float power, int fuse) {
        PrimedTntEntity entity = new PrimedTntEntity(power, fuse);
        entity.setPosition(x, y, z);
        entity.motionY = 0.2;
        entity.motionX = (this.random.nextDouble() - 0.5) * 0.02;
        entity.motionZ = (this.random.nextDouble() - 0.5) * 0.02;
        this.spawnEntity(entity);
        if (this.soundManager != null && this.gameTime != this.lastFuseSoundTick) {
            this.lastFuseSoundTick = this.gameTime;
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

    /** true, wenn ein anderer lebender Rahmen dieselbe Hanging-Flaeche belegt. */
    public boolean hasOverlappingItemFrame(ItemFrameEntity frame) {
        final boolean[] found = {false};
        this.forEachEntityNearby(frame.x, frame.z, 1, entity -> {
            if (!found[0] && entity != frame && !entity.isRemoved()
                    && entity instanceof ItemFrameEntity other
                    && other.getBoundingBox().intersects(frame.getBoundingBox())) {
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
            }
        }
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
                this.gameTime + Math.max(1, delayTicks), priority.value());
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
                this.gameTime + Math.max(1, delayTicks));
        this.simulationTelemetry.recordScheduledRequest(accepted);
        if (accepted) this.markChunkModified(x, z);
    }

    /** true, wenn an der Position bereits ein geplanter Tick aussteht. */
    public boolean isTickScheduled(int x, int y, int z) {
        Identifier expectedBlock = Blocks.getState(this.getBlock(x, y, z)).getBlock().getIdentifier();
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
                this.gameTime + Math.max(1, tick.remainingTicks()), tick.priority(), tick.subOrder());
        this.simulationTelemetry.recordScheduledRequest(accepted);
        this.markChunkModified(tick.x(), tick.z());
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
        this.markChunkModified(tick.x(), tick.z());
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
     * Simulations-Distanz wird der Tick nicht ausgeführt, sondern erneut vorgemerkt - der Fluss
     * friert dort ein und läuft weiter, sobald der Spieler zurückkommt. (Während des Drains neu
     * geplante Einträge verarbeitet drainDue erst im nächsten Tick -> keine Endlosschleife.)
     *
     * <p>Ticks ENTLADENER Chunks werden dagegen verworfen: ihr Rest-Delay liegt im Save
     * (scheduleTick markiert den Chunk als modified, der Unload-Pfad speichert ihn), und beim
     * Wiederladen stellt {@link #restorePendingScheduledTicks} sie wieder her. Ohne das Verwerfen
     * wüchse die Queue über die Sitzung unbegrenzt und re-schedulte alle 20 Ticks jede je
     * entladene Position.</p>
     */
    private void tickScheduled() {
        this.scheduledTicks.drainDue(this.gameTime, MAX_SCHEDULED_TICKS_PER_TICK,
                (x, y, z, expectedBlock, priority, subOrder) -> {
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
                    if (chunk.status != ChunkStatus.READY || !this.isSimulated(cx, cz)) {
                        this.scheduledTicks.scheduleRestored(x, y, z, expectedBlock,
                                this.gameTime + OUT_OF_SIM_RESCHEDULE, priority, subOrder);
                        this.simulationTelemetry.recordScheduledRescheduled();
                        return;
                    }
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

    public void render(Camera camera, float partialTick) {
        this.render(camera, partialTick, null);
    }

    /** @param beforeTranslucent optionaler Draw-Hook (Third-Person-Spieler) VOR dem Translucent-Pass. */
    public void render(Camera camera, float partialTick, Runnable beforeTranslucent) {
        FrameProfiler.cpuStart(FrameProfiler.Cpu.REMESH);
        this.chunkManager.processRemeshes();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.REMESH);
        /* Entities VOR dem Translucent-Pass (Vanilla-Reihenfolge): Wasser blendet über
           Items/BlockEntities, statt sie hinter sich unsichtbar zu machen. */
        this.chunkRenderer.renderSolid(camera);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.BE);
        this.blockEntityRenderer.render(this.chunkManager, this.lodManager, camera, partialTick);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.BE);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.ENT);
        this.entityRenderer.render(this.chunksWithEntities, camera, partialTick);
        if (beforeTranslucent != null) beforeTranslucent.run();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.ENT);
        this.chunkRenderer.renderTranslucent(camera);
    }

    @Override
    public void dispose() {
        /* ERST die Worker stoppen (inkl. awaitTermination), DANN die GL-Ressourcen: In-flight-
           Mesh-Jobs dürfen beim Welt-Austritt nicht mehr laufen, wenn Arenen/Meshes sterben —
           sonst arbeiten Alt-Jobs beim direkten Wiedereintritt in die neue Welt hinein. */
        this.chunkManager.dispose();
        /* NACH den Workern: jetzt schreibt niemand mehr auf Chunks — ausstehende Save-Jobs
           flushen (bis 10 s) und die Region-Handles schließen. */
        this.storage.close();
        this.entityRenderer.dispose();
        this.chunkRenderer.dispose();
        /* blockEntityRenderer + atlas NICHT disposen: Engine-Lebensdauer (GameContainer). */
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
        if (!this.setBlockRaw(x, y, z, block)) return false;
        this.manageBlockEntity(x, y, z, old, block);
        if (updateNeighbors) this.updateNeighbors(x, y, z);
        return true;
    }

    /**
     * Platziert einen fertig berechneten Placement-State: setzt den Block (ohne Kaskade),
     * lässt den Block etwaige Mehrteil-Logik anwenden (z.B. obere Türhälfte über
     * {@link de.skyengine.game.world.block.Block#onPlaced}) und löst ERST DANACH die
     * Nachbar-Updates aus. Das Ordering ist entscheidend - sonst entfernt sich z.B. die
     * untere Türhälfte selbst, bevor die obere existiert.
     */
    public void placeBlock(int x, int y, int z, BlockState state) {
        /* Schlägt der Schreibzugriff fehl (Chunk nicht READY), dürfen onPlaced/updateNeighbors
           NICHT laufen — PartsBehavior setzte sonst Geschwisterteile für einen Ursprung,
           der nie geschrieben wurde. */
        if (!this.setBlock(x, y, z, state.getId(), false)) return;
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
        chunk.markSectionDirty(sy);

        /* Vertikale Section-Grenzen */
        if ((y & ChunkSection.MASK) == 0 && sy > 0) chunk.markSectionDirty(sy - 1);
        if ((y & ChunkSection.MASK) == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) chunk.markSectionDirty(sy + 1);


        /* An Chunk-Grenzen muss der Nachbar mit-remeshen, sonst bleiben dort falsche Faces */
        if (lx == 0) this.markDirtyColumn(cx - 1, cz, sy, y);
        if (lx == ChunkSection.MASK) this.markDirtyColumn(cx + 1, cz, sy, y);
        if (lz == 0) this.markDirtyColumn(cx, cz - 1, sy, y);
        if (lz == ChunkSection.MASK) this.markDirtyColumn(cx, cz + 1, sy, y);
        /* Chunk-ECKEN zusätzlich diagonal: dessen Fluid-Eckhöhen sampeln diese Zelle. */
        if (lx == 0 && lz == 0) this.markDirtyColumn(cx - 1, cz - 1, sy, y);
        if (lx == 0 && lz == ChunkSection.MASK) this.markDirtyColumn(cx - 1, cz + 1, sy, y);
        if (lx == ChunkSection.MASK && lz == 0) this.markDirtyColumn(cx + 1, cz - 1, sy, y);
        if (lx == ChunkSection.MASK && lz == ChunkSection.MASK) this.markDirtyColumn(cx + 1, cz + 1, sy, y);

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
        this.updateStateAt(x, y, z);
        for (Direction d : Direction.shapeUpdateValues()) {
            this.updateStateAt(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ(), d.opposite());
        }
    }

    /**
     * Der Ring um diese Zelle UND um jeden ihrer 6 Nachbarn (= Diamant mit Radius 2).
     *
     * <p>Genau die Reichweite, die Minecraft den beiden „lauten" Redstone-Quellen gibt
     * ({@code RedstoneTorchBlock.notifyNeighbors}, {@code RedStoneWireBlock.updatePowerStrength}).
     * Sie ist die Voraussetzung dafür, dass Quasi-Konnektivität am Kolben praktisch nutzbar ist:
     * eine Quelle, die die Zelle ÜBER dem Kolben speist, ist selbst kein Nachbar des Kolbens —
     * ohne den zweiten Ring bliebe er stehen. Hebel/Knopf/Dioden bleiben bewusst schmaler,
     * auch das ist MC-getreu.
     *
     * <p>Redstone-Staub verwendet diesen Sammelpfad nicht; dessen Vanilla-Evaluator erzeugt
     * dieselbe Reichweite schrittweise über verschachtelte Nachbar-Updates.
     */
    public void updateNeighborsWide(int x, int y, int z) {
        this.forEachWide(x, y, z, this::updateStateAt);
    }

    /**
     * Derselbe Diamant, aber aufgeschoben auf den nächsten Tick — für {@code onBreak}, das VOR
     * dem Entfernen läuft (s. {@link #deferBlockUpdate}). Ohne das bliebe ein Kolben, der über
     * Quasi-Konnektivität an einer abgebauten Fackel hing, ausgefahren stehen.
     */
    public void deferBlockUpdatesWide(int x, int y, int z) {
        this.forEachWide(x, y, z, this::deferBlockUpdate);
    }

    /** Alle Zellen mit Manhattan-Abstand <= 2 — jede genau einmal. */
    private void forEachWide(int x, int y, int z, CellAction action) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2 + Math.abs(dx); dy <= 2 - Math.abs(dx); dy++) {
                int rest = 2 - Math.abs(dx) - Math.abs(dy);
                for (int dz = -rest; dz <= rest; dz++) {
                    action.run(x + dx, y + dy, z + dz);
                }
            }
        }
    }

    private interface CellAction {
        void run(int x, int y, int z);
    }

    /**
     * Lässt genau EINE Zelle ihren State neu berechnen (schmaler Wrapper um
     * {@link #updateStateAt}) — für die gezielte Empfänger-Benachrichtigung des
     * Staub-Evaluators und anderer gezielter Update-Pfade.
     */
    public void updateBlockStateAt(int x, int y, int z) {
        this.updateStateAt(x, y, z);
    }

    private void updateStateAt(int x, int y, int z) {
        this.updateStateAt(x, y, z, null);
    }

    private void updateStateAt(int x, int y, int z, Direction changedDirection) {
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
        if (changedDirection == null) {
            updated = current.getBlock().getStateForNeighborUpdate(this, x, y, z, current);
        } else {
            BlockState neighbor = Blocks.getState(this.getBlock(
                    x + changedDirection.offsetX(),
                    y + changedDirection.offsetY(),
                    z + changedDirection.offsetZ()));
            updated = current.getBlock().getStateForNeighborUpdate(
                    this, x, y, z, current, changedDirection, neighbor);
        }
        if (updated != current) {
            /* Selbst-Entfernen kaskadiert (Tür-/Tall-Grass-Oberhälfte nach Stützverlust),
               reine State-Änderungen (Verbindungen, Treppen) bewusst nicht. Keine Endlos-
               Kaskade möglich: jeder Kaskadenschritt entfernt einen Block endgültig. */
            boolean removed = updated.getId() == Blocks.AIR;
            /* Ein Block, der sich selbst entfernt, wird abgebaut — also VOR dem Setzen denselben
               onBreak-Hook laufen lassen wie beim Abbau durch den Spieler. Ohne das verlöre ein
               so entfernter Block mit BlockEntity still seinen Inhalt. Heute implementiert kein
               Behavior onBreak; die Reihenfolge schließt die Lücke, bevor das erste es tut. */
            if (removed) current.getBlock().onBreak(this, x, y, z, current);
            if (this.setBlock(x, y, z, updated.getId(), removed) && !removed) {
                updated.getBlock().onStateChangedByNeighborUpdate(
                        this, x, y, z, current, updated);
            }
        }
    }

    /**
     * Merkt ein Block-Update für den NÄCHSTEN Tick vor (öffentliche Sicht auf das
     * Nachhol-Protokoll). Für Fälle, in denen ein Empfänger erst NACH einer laufenden
     * Entfernung neu rechnen darf — der Staub-Abbau nutzt das als Post-Removal-2-Ring
     * (Vanillas onRemove-Äquivalent): eine Tür hinter einem stark gespeisten Block sähe
     * den Staub sonst beim Re-Check noch stehen.
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
    private void markDirtyColumn(int cx, int cz, int sectionY, int y) {
        this.markDirty(cx, cz, sectionY);
        if ((y & ChunkSection.MASK) == 0 && sectionY > 0) {
            this.markDirty(cx, cz, sectionY - 1);
        }
        if ((y & ChunkSection.MASK) == ChunkSection.MASK && sectionY < Chunk.SECTIONS - 1) {
            this.markDirty(cx, cz, sectionY + 1);
        }
    }

    private void markDirty(int cx, int cz, int sectionY) {
        Chunk chunk = this.chunkManager.getChunk(cx, cz);

        /* isAtLeast(LIT) statt == READY: ein Nachbar im MESHING-Fenster (unlockRead vor dem
           READY-Set weckt genau hier den blockierten Writer) hat sein Mesh bereits aus den
           Vor-Edit-Daten gebaut — ein verworfener Marker hieße dauerhaft falsche Naht-Faces.
           Die Dirty-Bits überleben bis READY (der Erst-Mesh-Job konsumiert sie nicht), das
           Remesh-Gate in processRemeshes verlangt weiterhin READY — schlimmstenfalls also ein
           redundanter Remesh, exakt wie bei den Markierungen der LightEngine. */
        if (chunk != null && chunk.status.isAtLeast(ChunkStatus.LIT)) {
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
        /* Moving-Piston-Formen können bis zu eine Zelle aus ihrer technischen BE-Zelle
           hinausragen. Deshalb separat mit Ein-Zellen-Rand suchen; die Blockform selbst ist
           leer und kann im normalen Lauf nicht doppelt auftauchen. */
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = y0 - 1; y <= y1 + 1; y++) {
                    if (this.getBlock(x, y, z) != Blocks.MOVING_PISTON) continue;
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

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public ChunkRenderer getChunkRenderer() {
        return chunkRenderer;
    }
}
