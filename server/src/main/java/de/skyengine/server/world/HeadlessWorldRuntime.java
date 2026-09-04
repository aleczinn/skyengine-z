package de.skyengine.server.world;

import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerMovementSimulation;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Headless authoritative bootstrap world. It already supplies complete renderable L0 columns;
 * the richer legacy generator, simulation and region persistence are migrated behind the same
 * {@link ServerWorldRuntime} boundary without changing the network or client renderer again.
 */
public final class HeadlessWorldRuntime implements ServerWorldRuntime {
    public static final String OVERWORLD = "skyengine:overworld";
    private static final int CHUNK_SIZE = 32;
    private static final int SECTION_SIZE = 32;
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int MAX_CACHED_COLUMNS = 2048;

    // Network IDs. The client resolves their stable names to its own baked runtime IDs.
    private static final int AIR = 0;
    private static final int BEDROCK = 1;
    private static final int STONE = 2;
    private static final int DIRT = 3;
    private static final int GRASS = 4;
    private static final List<RegistryMapping> REGISTRIES = List.of(
            new RegistryMapping("block_state", List.of(
                    "voxelstories:air", "voxelstories:bedrock", "voxelstories:stone",
                    "voxelstories:dirt", "voxelstories:grass_block")),
            new RegistryMapping("biome", List.of("voxelstories:plains")));

    private record ColumnKey(String dimension, int chunkX, int chunkZ) {}

    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final long seed;
    private final ExecutorService generationWorkers;
    private final ConcurrentHashMap<ColumnKey, CompletableFuture<ChunkColumnSnapshot>> inFlight =
            new ConcurrentHashMap<>();
    private final LinkedHashMap<ColumnKey, ChunkColumnSnapshot> cache =
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<ColumnKey, ChunkColumnSnapshot> eldest) {
                    return size() > MAX_CACHED_COLUMNS;
                }
            };
    private volatile boolean closed;

    public HeadlessWorldRuntime(Path directory) throws IOException {
        this(directory, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    }

    public HeadlessWorldRuntime(Path directory, int workerThreads) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        this.lockChannel = FileChannel.open(this.directory.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        this.lock = this.lockChannel.tryLock();
        if (this.lock == null) {
            this.lockChannel.close();
            throw new IOException("World is already open: " + this.directory);
        }
        try {
            this.seed = loadOrCreateSeed(this.directory);
            int threads = Math.max(1, Math.min(32, workerThreads));
            this.generationWorkers = Executors.newFixedThreadPool(threads,
                    Thread.ofPlatform().daemon().name("Server Worldgen-", 0).factory());
        } catch (IOException | RuntimeException failure) {
            try { this.lock.release(); } catch (IOException ignored) { }
            try { this.lockChannel.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    @Override public Path directory() { return this.directory; }
    public long seed() { return this.seed; }
    @Override public List<RegistryMapping> registryMappings() { return REGISTRIES; }
    @Override public void tick(long serverTick) { }
    @Override public void autosave(long serverTick) { }

    @Override
    public ChunkSnapshotTicket requestChunkSnapshot(
            String dimension, int chunkX, int chunkZ) {
        if (!OVERWORLD.equals(dimension) || this.closed) {
            return ChunkSnapshotTicket.completed(Optional.empty());
        }
        ColumnKey key = new ColumnKey(dimension, chunkX, chunkZ);
        synchronized (this.cache) {
            ChunkColumnSnapshot cached = this.cache.get(key);
            if (cached != null) return ChunkSnapshotTicket.completed(Optional.of(cached));
        }
        CompletableFuture<ChunkColumnSnapshot> future = this.inFlight.computeIfAbsent(key, requested -> {
            CompletableFuture<ChunkColumnSnapshot> created = CompletableFuture.supplyAsync(
                    () -> generateColumn(requested.chunkX(), requested.chunkZ()), this.generationWorkers);
            created.whenComplete((snapshot, failure) -> {
                this.inFlight.remove(requested, created);
                if (snapshot != null && failure == null) {
                    synchronized (this.cache) { this.cache.put(requested, snapshot); }
                }
            });
            return created;
        });
        ChunkSnapshotTicket ticket = new ChunkSnapshotTicket();
        future.whenComplete((snapshot, failure) -> {
            if (failure == null) ticket.complete(Optional.of(snapshot));
            else ticket.completeExceptionally(failure);
        });
        return ticket;
    }

    @Override
    public PlayerStateSnapshot playerJoined(PlayerIdentity identity, int entityId, long serverTick) {
        double x = 0.5;
        double z = 0.5;
        double y = heightAt(0, 0) + 1.0;
        return new PlayerStateSnapshot(serverTick, 0, OVERWORLD, x, y, z,
                0, 0, 0, 0, 0, true, PlayerGameMode.CREATIVE, 0);
    }

    @Override
    public PlayerStateSnapshot applyPlayerInput(PlayerIdentity identity, int entityId,
                                                 PlayerStateSnapshot previous, PlayerInputFrame input,
                                                 long serverTick) {
        int continuousButtons = input.buttons() & ~(PlayerInputFrame.CYCLE_GAME_MODE
                | PlayerInputFrame.TOGGLE_FLY | PlayerInputFrame.SPECTATOR_SPEED_UP
                | PlayerInputFrame.SPECTATOR_SPEED_DOWN);
        PlayerInputFrame continuous = new PlayerInputFrame(input.sequence(), input.clientTick(),
                input.forward(), input.strafe(), input.yaw(), input.pitch(), continuousButtons,
                previous.selectedHotbarSlot());
        return PlayerMovementSimulation.simulate(previous, continuous, serverTick,
                (x, z, fallback) -> heightAt((int) Math.floor(x), (int) Math.floor(z)) + 1.0);
    }

    private ChunkColumnSnapshot generateColumn(int chunkX, int chunkZ) {
        int[][] cells = new int[16][];
        int[] heightmap = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        int[] biomes = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int surface = heightAt(baseX + x, baseZ + z);
                heightmap[(z << 5) | x] = surface + 1;
                for (int y = 0; y <= surface; y++) {
                    int sectionY = y >> 5;
                    int[] section = cells[sectionY];
                    if (section == null) section = cells[sectionY] = new int[SECTION_VOLUME];
                    int state = y == 0 ? BEDROCK : y == surface ? GRASS
                            : y >= surface - 3 ? DIRT : STONE;
                    section[((y & 31) << 10) | (z << 5) | x] = state;
                }
            }
        }

        List<ChunkSectionSnapshot> sections = new ArrayList<>();
        for (int sectionY = 0; sectionY < cells.length; sectionY++) {
            int[] section = cells[sectionY];
            if (section != null) sections.add(packSection(sectionY, section));
        }
        int[] grass = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int[] foliage = new int[ChunkColumnSnapshot.TINT_CORNERS];
        Arrays.fill(grass, 0x91BD59);
        Arrays.fill(foliage, 0x77AB2F);
        return new ChunkColumnSnapshot(OVERWORLD, chunkX, chunkZ, 0, sections,
                biomes, grass, foliage, heightmap);
    }

    private static ChunkSectionSnapshot packSection(int sectionY, int[] cells) {
        LinkedHashMap<Integer, Integer> paletteIndex = new LinkedHashMap<>();
        int nonAir = 0;
        for (int state : cells) {
            paletteIndex.computeIfAbsent(state, ignored -> paletteIndex.size());
            if (state != AIR) nonAir++;
        }
        int[] palette = new int[paletteIndex.size()];
        for (Map.Entry<Integer, Integer> entry : paletteIndex.entrySet()) {
            palette[entry.getValue()] = entry.getKey();
        }
        int bits = palette.length == 1 ? 0
                : Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        long[] packed = bits == 0 ? new long[0]
                : new long[(int) (((long) SECTION_VOLUME * bits + 63) / 64)];
        if (bits != 0) {
            long mask = (1L << bits) - 1;
            for (int i = 0; i < cells.length; i++) {
                long value = paletteIndex.get(cells[i]) & mask;
                long bitIndex = (long) i * bits;
                int word = (int) (bitIndex >>> 6);
                int offset = (int) (bitIndex & 63);
                packed[word] |= value << offset;
                if (offset + bits > 64) packed[word + 1] |= value >>> (64 - offset);
            }
        }
        LightPlane sky = new LightPlane(nonAir < SECTION_VOLUME
                ? LightPlane.Mode.UNIFORM_FULL : LightPlane.Mode.UNIFORM_ZERO, null);
        return new ChunkSectionSnapshot(sectionY, nonAir, palette, bits, packed, sky,
                new LightPlane(LightPlane.Mode.UNIFORM_ZERO, null));
    }

    /** Deterministic smooth heightfield with correct floor behaviour at negative coordinates. */
    private int heightAt(int x, int z) {
        double continental = valueNoise(x, z, 96);
        double detail = valueNoise(x, z, 28);
        return Math.clamp((int) Math.round(68 + continental * 17 + detail * 5), 40, 94);
    }

    private double valueNoise(int x, int z, int scale) {
        int gx = Math.floorDiv(x, scale);
        int gz = Math.floorDiv(z, scale);
        double tx = smooth(Math.floorMod(x, scale) / (double) scale);
        double tz = smooth(Math.floorMod(z, scale) / (double) scale);
        double a = randomUnit(gx, gz);
        double b = randomUnit(gx + 1, gz);
        double c = randomUnit(gx, gz + 1);
        double d = randomUnit(gx + 1, gz + 1);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private double randomUnit(int x, int z) {
        long value = this.seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static long loadOrCreateSeed(Path directory) throws IOException {
        Path metadata = directory.resolve("level.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(metadata)) {
            try (InputStream input = Files.newInputStream(metadata)) { properties.load(input); }
            String text = properties.getProperty("seed");
            if (text == null) throw new IOException("Missing seed in " + metadata);
            try { return Long.parseLong(text.trim()); }
            catch (NumberFormatException invalid) {
                throw new IOException("Invalid world seed in " + metadata, invalid);
            }
        }
        long seed = new SecureRandom().nextLong();
        properties.setProperty("seed", Long.toString(seed));
        try (OutputStream output = Files.newOutputStream(metadata, StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "SkyEngine headless world metadata");
        }
        return seed;
    }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        this.generationWorkers.shutdownNow();
        synchronized (this.cache) { this.cache.clear(); }
        this.inFlight.clear();
        try { this.lock.release(); } catch (IOException ignored) { }
        try { this.lockChannel.close(); } catch (IOException ignored) { }
    }
}
