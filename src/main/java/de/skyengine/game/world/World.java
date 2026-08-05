package de.skyengine.game.world;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.PrimedTntEntity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
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
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickQueue;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.entity.EntityRenderer;
import de.skyengine.graphics.world.ChunkRenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
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

    /* Nachhol-Protokoll für Nachbar-State-Updates an nicht-READY Chunks (F1): updateStateAt
       parkt die Position hier, processDeferredStateUpdates zieht sie bei READY nach.
       LinkedHashSet = Dedup + älteste-zuerst fürs Notventil; nur Tick-/Render-Thread. */
    private static final int MAX_DEFERRED_STATE_UPDATES = 4096;
    private final LinkedHashSet<Long> deferredStateUpdates = new LinkedHashSet<>();
    private long[] deferredScratch = new long[0];

    /* Block-Event-Queue (MCs Block-Events, heute nur Kolben): 0-Tick-Reaktion mit zwei
       Drain-Punkten pro Tick — Drain A nach tickScheduled (Flanken aus Redstone-Ticks),
       Drain B nach tickEntities (Re-Checks aus dem BE-finish). Jeder Drain-Punkt läuft in
       Wellen, BIS die Queue leer ist (MCs ServerLevel.runBlockEvents) — eine Kaskade wird
       also im selben Durchgang fertig. Das ist der Unterschied zwischen „zwei gestapelte
       Kolben fahren gleichzeitig" und „der zweite hinkt einen Tick" (s. processBlockEvents).
       LinkedHashSet = Dedup pro Position + FIFO. */
    private static final int MAX_BLOCK_EVENT_WAVES = 64;
    private final LinkedHashSet<Long> blockEvents = new LinkedHashSet<>();
    private long[] blockEventScratch = new long[0];

    /** Der Spieler dieses Ticks (für BlockEntities, die ihn brauchen, z.B. das Zaubertisch-Buch). */
    private EntityPlayer player;

    /** Spielzeit in Ticks (20 TPS), bei jedem update() erhöht - Basis für geplante Ticks. */
    private long gameTime;
    private final Random random = new Random();
    private final ScheduledTickQueue scheduledTicks = new ScheduledTickQueue();
    private final SimulationTelemetry simulationTelemetry = new SimulationTelemetry();

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
        this.simulationTelemetry.setEnabled(FrameProfiler.isEnabled());
        this.simulationTelemetry.beginTick();
        this.gameTime++;
        this.player = player;
        this.playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        this.chunkManager.update(player);
        this.lodManager.update(player);
        this.restorePendingScheduledTicks();
        this.processReadyChunks();
        this.processDeferredStateUpdates();
        this.tickScheduled();
        this.processBlockEvents(); // Drain A: Flanken aus den Redstone-Ticks, noch VOR der BE-Phase
        this.tickRandomBlocks();
        this.tickBlockEntities();
        this.tickEntities();
        this.processBlockEvents(); // Drain B: Re-Checks aus dem BE-finish, noch im selben Tick
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
                if (restorer != null) restorer.restore(this, tick.x(), tick.y(), tick.z(), tick.remainingTicks());
            }
            chunk.pendingScheduledTicks = null;
        }
    }

    /**
     * Nimmt frisch READY gewordene Chunks entgegen und stellt darin den transienten
     * Vergleichs-Zustand wieder her, den kein Save mitbringt — heute nur der Beobachter.
     *
     * <p>Läuft VOR {@link #processDeferredStateUpdates} und {@link #tickScheduled}: der
     * Anfangszustand muss stehen, bevor das erste Nachbar-Update oder ein aus dem Save
     * restaurierter Puls-Tick den Block erreicht. Sonst verschluckt der Beobachter genau die
     * Flanke, mit der eine Clock weiterlaufen wollte.
     *
     * <p>Aufbau wie {@link #restorePendingScheduledTicks}: größen-begrenztes Poll (ein Requeue
     * darf im selben Tick nicht erneut drankommen), Identitätscheck gegen die Chunk-Map,
     * Requeue solange der Chunk noch nicht READY ist.
     */
    private void processReadyChunks() {
        int pending = this.chunkManager.readyAnnouncePending();
        for (int i = 0; i < pending; i++) {
            Chunk chunk = this.chunkManager.pollReadyAnnounce();
            if (chunk == null) break;
            if (this.chunkManager.getChunk(chunk.chunkX, chunk.chunkZ) != chunk) continue;
            if (chunk.status != ChunkStatus.READY) {
                this.chunkManager.requeueReadyAnnounce(chunk);
                continue;
            }
            if (chunk.loadSeeded) continue;   // remeshAll macht Chunks ein zweites Mal READY
            chunk.loadSeeded = true;
            de.skyengine.game.world.block.behavior.ObserverBehavior.seedLoadedChunk(this, chunk);
        }
    }

    /* Ein-Tick-Index für die Save-Snapshots: EIN forEachPending-Durchlauf je Tick gruppiert
       alle anstehenden Ticks nach Chunk-Key. Vorher scannte JEDER enqueueSave die ganze
       Queue — beim Autosave O(zu speichernde Chunks × offene Ticks) in einem einzigen
       Tick-Slot (der gemessene Kandidat für den Save-Ruckler). */
    private long tickSnapshotIndexTime = -1;
    private final de.skyengine.utils.collect.LongObjMap<List<SavedTick>> tickSnapshotIndex =
            new de.skyengine.utils.collect.LongObjMap<>(64);

    /**
     * Sammelt die anstehenden Scheduled-Ticks des Chunks für die Persistenz (Typ "block" —
     * alles in der Queue dispatcht über Block.scheduledTick). Künftige Systeme mit eigenen
     * Datenstrukturen hängen sich hier als weitere Quellen an. Nur Tick-Thread; einziger
     * Aufrufer ist {@code WorldStorage.enqueueSave}. null, wenn nichts ansteht.
     */
    public List<SavedTick> snapshotScheduledTicks(Chunk chunk) {
        if (this.tickSnapshotIndexTime != this.gameTime) {
            this.tickSnapshotIndexTime = this.gameTime;
            this.tickSnapshotIndex.clear();
            this.scheduledTicks.forEachPending(this.gameTime, (x, y, z, remaining) -> {
                long key = Chunk.key(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
                List<SavedTick> list = this.tickSnapshotIndex.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    this.tickSnapshotIndex.put(key, list);
                }
                list.add(new SavedTick(ScheduledTickTypes.BLOCK, x, y, z, remaining));
            });
        }
        return this.tickSnapshotIndex.get(Chunk.key(chunk.chunkX, chunk.chunkZ));
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
     * Position ist nur ein Tick gleichzeitig vorgemerkt (Dedup). Basis für Fluss/Fall.
     *
     * <p>Markiert den Chunk zusätzlich als modified: ein anstehender Tick ist Zustand, der
     * gespeichert werden muss — sonst verlöre eine Clock (Verstärker-Loop), die seit dem
     * letzten Save keinen Block geschrieben hat, beim Beenden ihren Rest-Delay und stünde
     * nach dem Neuladen still.</p>
     */
    public void scheduleTick(int x, int y, int z, int delayTicks) {
        boolean accepted = this.scheduledTicks.schedule(x, y, z,
                this.gameTime + Math.max(1, delayTicks));
        this.simulationTelemetry.recordScheduledRequest(accepted);
        this.markChunkModified(x, z);
    }

    /**
     * Wie {@link #scheduleTick}, zieht aber einen bereits anstehenden <em>späteren</em> Tick auf diese
     * frühere Zeit vor (statt ihn zu ignorieren). Für prompte Reaktionen (z.B. Lava+Wasser→Cobble),
     * die einen regulären Fluss-Tick überholen müssen.
     */
    public void scheduleTickEarlier(int x, int y, int z, int delayTicks) {
        boolean accepted = this.scheduledTicks.scheduleEarlier(x, y, z,
                this.gameTime + Math.max(1, delayTicks));
        this.simulationTelemetry.recordScheduledRequest(accepted);
        this.markChunkModified(x, z);
    }

    /** true, wenn an der Position bereits ein geplanter Tick aussteht. */
    public boolean isTickScheduled(int x, int y, int z) {
        return this.scheduledTicks.isScheduled(x, y, z);
    }

    /** Aktuelle Spielzeit in Ticks (20 TPS). */
    public long getGameTime() {
        return this.gameTime;
    }

    /** Diagnosezaehler dieser Welt; standardmaessig nur im Full-Debug-Modus aktiv. */
    public SimulationTelemetry getSimulationTelemetry() {
        return this.simulationTelemetry;
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
        this.scheduledTicks.drainDue(this.gameTime, (x, y, z) -> {
            this.simulationTelemetry.recordScheduledDue();
            int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
            Chunk chunk = this.chunkManager.getChunk(cx, cz);
            if (chunk == null) {
                this.simulationTelemetry.recordScheduledDroppedUnloaded();
                return;
            }
            if (chunk.status != ChunkStatus.READY || !this.isSimulated(cx, cz)) {
                this.scheduledTicks.schedule(x, y, z, this.gameTime + OUT_OF_SIM_RESCHEDULE);
                this.simulationTelemetry.recordScheduledRescheduled();
                return;
            }
            BlockState state = Blocks.getState(this.getBlock(x, y, z));
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
            } else if (neighbor.isOpaqueCube()) {
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
        for (Direction d : Direction.horizontalValues()) {
            this.updateStateAt(x + d.offsetX(), y, z + d.offsetZ());
        }
        /* Vertikale Nachbarn fürs 6-dir-Connection-System (Pipes/Cables). */
        this.updateStateAt(x, y + 1, z);
        this.updateStateAt(x, y - 1, z);
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
     * <p>Das Staub-Netz baut sich dieselbe Menge selbst zusammen, weil es über eine ganze
     * Komponente hinweg deduplizieren muss (s. {@code RedstoneWireNetwork}).
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
     * Staub-Netzes, das seine Betroffenen selbst dedupliziert statt ganze Ringe
     * zu feuern.
     */
    public void updateBlockStateAt(int x, int y, int z) {
        this.updateStateAt(x, y, z);
    }

    private void updateStateAt(int x, int y, int z) {
        /* Nicht-READY-Zielchunk: setBlockRaw könnte ohnehin nicht schreiben — das Update
           würde still verlorengehen (Zaun im Nachbarchunk berechnet seine Verbindung dann
           NIE). Position parken; processDeferredStateUpdates zieht sie nach, sobald der
           Chunk READY ist. chunk == null ist nur das Unload-Race → verwerfen. */
        Chunk targetChunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (targetChunk == null) return;
        if (targetChunk.status != ChunkStatus.READY) {
            this.deferStateUpdate(x, y, z);
            return;
        }

        int id = this.getBlock(x, y, z);
        if (id == Blocks.AIR) return;
        BlockState current = Blocks.getState(id);
        BlockState updated = current.getBlock().getStateForNeighborUpdate(this, x, y, z, current);
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
            this.setBlock(x, y, z, updated.getId(), removed);
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
        this.deferStateUpdate(x, y, z);
    }

    /**
     * Reiht ein Block-Event für dieselbe Tick-Runde ein (MCs Block-Event-Äquivalent, heute nur
     * Kolben): der Block dort bekommt beim nächsten Drain-Punkt {@code onBlockEvent} — im
     * SELBEN Game-Tick wie die auslösende Flanke, aber außerhalb der Nachbar-Update-Kaskade
     * (der Ort für schwere Multi-Block-Aktionen). Dedupliziert pro Position; „tolerantes
     * Feuern" wie beim Tick-Scheduler: der Empfänger validiert seinen State selbst.
     */
    public void enqueueBlockEvent(int x, int y, int z) {
        this.blockEvents.add(BlockPos.asLong(x, y, z));
    }

    /**
     * Drained Block-Events in Wellen, bis die Queue leer ist (MCs {@code runBlockEvents}).
     * Der Snapshot je Welle schützt nur die Iteration; was während einer Welle dazukommt,
     * läuft in der NÄCHSTEN Welle desselben Drain-Punkts.
     *
     * <p>Warum das wichtig ist: löst ein Kolben beim Ausfahren über seinen Nachbar-Ring einen
     * zweiten Kolben aus (2-hohe Kolbentür — der untere hängt an der Quasi-Konnektivität),
     * dann muss dessen Bewegung noch VOR {@code tickBlockEntities} beginnen. Sonst verpasst er
     * den Animationsschritt dieses Ticks und wird dauerhaft einen Tick später fertig — der
     * Versatz pflanzt sich über den Re-Check aus dem BE-finish in jeden Zyklus fort.
     *
     * <p>Terminierung: die Empfänger validieren ihren Zustand selbst und tun nichts mehr, wenn
     * er schon passt ({@code PistonBehavior.evaluate}), eine Kaskade läuft also aus. Der Deckel
     * {@link #MAX_BLOCK_EVENT_WAVES} ist nur die Notbremse gegen einen unbekannten Zyklus; der
     * Rest bleibt liegen und läuft am nächsten Drain-Punkt.
     *
     * <p>Nicht simulierbare Positionen werden als geplanter Tick geparkt: der persistiert im
     * Save und kommt nach dem Laden wieder.
     */
    private void processBlockEvents() {
        int welle = 0;
        while (!this.blockEvents.isEmpty()) {
            if (++welle > MAX_BLOCK_EVENT_WAVES) {
                this.simulationTelemetry.recordBlockEventWaveLimitHit();
                this.logger.warning("Block-Events terminieren nicht (" + MAX_BLOCK_EVENT_WAVES
                        + " Wellen, " + this.blockEvents.size() + " offen) — Rest auf den naechsten Drain");
                return;
            }

            int n = this.blockEvents.size();
            this.simulationTelemetry.recordBlockEventWave(n);
            if (this.blockEventScratch.length < n) this.blockEventScratch = new long[n * 2];
            int i = 0;
            for (long pos : this.blockEvents) this.blockEventScratch[i++] = pos;
            this.blockEvents.clear();

            for (int j = 0; j < n; j++) {
                long pos = this.blockEventScratch[j];
                int x = BlockPos.unpackX(pos), y = BlockPos.unpackY(pos), z = BlockPos.unpackZ(pos);
                int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                Chunk chunk = this.chunkManager.getChunk(cx, cz);
                if (chunk == null || chunk.status != ChunkStatus.READY || !this.isSimulated(cx, cz)) {
                    this.scheduleTickEarlier(x, y, z, 1);
                    continue;
                }
                BlockState state = Blocks.getState(this.getBlock(x, y, z));
                state.getBlock().onBlockEvent(this, x, y, z, state);
            }
        }
    }

    /** Merkt ein Nachbar-State-Update für einen (noch) nicht-READY Chunk vor (dedupliziert). */
    private void deferStateUpdate(int x, int y, int z) {
        if (this.deferredStateUpdates.size() >= MAX_DEFERRED_STATE_UPDATES) {
            /* Notventil gegen unbegrenztes Wachstum (z.B. Einträge für nie zurückkehrende
               Chunks): ältesten Eintrag opfern — der Fall ist konstruiert selten. */
            Iterator<Long> it = this.deferredStateUpdates.iterator();
            it.next();
            it.remove();
            this.simulationTelemetry.recordDeferredDropped();
            this.logger.debug("Nachhol-Puffer für Block-Updates voll — ältester Eintrag verworfen");
        }
        this.deferredStateUpdates.add(BlockPos.asLong(x, y, z));
    }

    /**
     * Zieht vorgemerkte Nachbar-State-Updates nach, deren Chunk inzwischen READY ist
     * (Gegenstück zu {@link #restorePendingScheduledTicks} für Block-States). Läuft im Tick
     * direkt nach {@code chunkManager.update()}, damit READY-Übergänge dieses Ticks schon
     * sichtbar sind. Die Einträge werden in einen Scratch kopiert, weil {@code updateStateAt}
     * beim Nachziehen neue Deferrals erzeugen kann (Kaskade an die nächste Chunkgrenze).
     */
    private void processDeferredStateUpdates() {
        if (this.deferredStateUpdates.isEmpty()) return;

        int n = this.deferredStateUpdates.size();
        this.simulationTelemetry.recordDeferredProcessed(n);
        if (this.deferredScratch.length < n) this.deferredScratch = new long[n * 2];
        int i = 0;
        for (long pos : this.deferredStateUpdates) this.deferredScratch[i++] = pos;
        this.deferredStateUpdates.clear();

        for (int j = 0; j < n; j++) {
            long pos = this.deferredScratch[j];
            int x = BlockPos.unpackX(pos), y = BlockPos.unpackY(pos), z = BlockPos.unpackZ(pos);
            Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            if (chunk == null || chunk.status != ChunkStatus.READY) {
                /* Noch nicht dran (auch chunk == null: kann nach einem Unload-Zyklus
                   zurückkommen) — wieder einreihen, das Set dedupliziert. */
                this.deferredStateUpdates.add(pos);
                this.simulationTelemetry.recordDeferredRequeued();
                continue;
            }
            this.updateStateAt(x, y, z);
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
