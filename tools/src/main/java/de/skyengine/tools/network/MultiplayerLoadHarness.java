package de.skyengine.tools.network;

import de.skyengine.client.network.ClientNetworkSession;
import de.skyengine.client.network.LegacyBlockStateNetworkMapper;
import de.skyengine.client.network.ReplicatedChunkCache;
import de.skyengine.client.network.ReplicatedChunkWorldAdapter;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.server.ServerApplication;
import de.skyengine.server.ServerConfig;
import de.skyengine.server.network.NettyTransportConnection;
import de.skyengine.server.network.VarIntFrameDecoder;
import de.skyengine.server.world.AuthoritativeWorldRuntime;
import de.skyengine.server.world.HeadlessWorldRuntime;
import de.skyengine.server.world.ServerWorldRuntime;
import de.skyengine.shared.gameplay.PlayerAbilityAction;
import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.PacketRegistry;
import de.skyengine.shared.network.ProtocolLimits;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Reproducible local/TCP load harness using the production client replication state machine. */
public final class MultiplayerLoadHarness {
    public static void main(String[] args) throws Exception {
        int players = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        Path report = args.length > 2 && !args[2].isBlank() ? Path.of(args[2]) : null;
        String runtime = args.length > 3 ? args[3].strip().toLowerCase(Locale.ROOT) : "headless";
        int viewDistance = args.length > 4 ? Integer.parseInt(args[4]) : 16;
        String route = args.length > 5 ? args[5].strip().toLowerCase(Locale.ROOT) : "stationary";
        int bandwidthMiB = args.length > 6 ? Integer.parseInt(args[6]) : 128;
        int seed = args.length > 7 ? Integer.parseInt(args[7]) : 123456789;
        int catchUpSeconds = args.length > 8 ? Integer.parseInt(args[8]) : 10;
        int mutationRate = args.length > 9 ? Integer.parseInt(args[9]) : 100;
        int workerThreads = args.length > 10 ? Integer.parseInt(args[10])
                : Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        if (players < 1 || players > 1000 || seconds < 1 || seconds > 3600
                || viewDistance < 2 || viewDistance > 32 || bandwidthMiB < 1 || bandwidthMiB > 128
                || catchUpSeconds < 0 || catchUpSeconds > 300
                || mutationRate < 1 || mutationRate > 10_000
                || workerThreads < 1 || workerThreads > 256) {
            throw new IllegalArgumentException("Usage: MultiplayerLoadHarness [players 1..1000] "
                    + "[seconds 1..3600] [report] [headless|authoritative|dedicated] "
                    + "[viewDistance 2..32] [stationary|fast] [bandwidthMiB 1..128] [seed] "
                    + "[catchUpSeconds 0..300] [mutationsPerSecond 1..10000] "
                    + "[workerThreads 1..256]");
        }
        if (!runtime.equals("headless") && !runtime.equals("authoritative")
                && !runtime.equals("dedicated")) {
            throw new IllegalArgumentException("Unknown runtime " + runtime);
        }
        if (!route.equals("stationary") && !route.equals("fast") && !route.equals("warm")) {
            throw new IllegalArgumentException("Unknown route " + route);
        }
        boolean dedicated = runtime.equals("dedicated");
        PerformanceProfiler.get().setEnabled(true);
        Path root = Files.createTempDirectory("skyengine-load-");
        List<Bot> bots = new ArrayList<>(players);
        try {
            ServerConfig defaults = ServerConfig.load(root);
            ServerConfig config = benchmarkConfig(defaults, Math.max(players, defaults.maxPlayers()),
                    viewDistance, dedicated ? reservePort() : defaults.serverPort(), bandwidthMiB,
                    workerThreads);
            try (ServerWorldRuntime world = !runtime.equals("headless")
                    ? new AuthoritativeWorldRuntime(config, seed)
                    : new HeadlessWorldRuntime(config.worldDirectory());
                 ServerApplication server = new ServerApplication(config, world)) {
                RemoteConnector connector = dedicated ? new RemoteConnector(players) : null;
                WorldWorkerPool clientWorkers = !dedicated && world instanceof AuthoritativeWorldRuntime authoritative
                        ? authoritative.workerPool() : new WorldWorkerPool(config.workerThreads());
                boolean ownsClientWorkers = !(world instanceof AuthoritativeWorldRuntime authoritative
                        && clientWorkers == authoritative.workerPool());
                try {
                    if (dedicated) {
                        server.startDedicated();
                        for (int i = 0; i < players; i++) {
                            bots.add(new Bot(i, connector.connect(config.listenAddress()), viewDistance,
                                    clientWorkers));
                        }
                    } else {
                        bots.add(new Bot(0, server.startIntegrated().client(), viewDistance, clientWorkers));
                        for (int i = 1; i < players; i++) {
                            bots.add(new Bot(i, server.connectLocalClient(), viewDistance, clientWorkers));
                        }
                    }
                    bots.forEach(Bot::start);

                    de.skyengine.server.network.ServerSessionManager.NetworkSnapshot measurementBaseline = null;
                    if (route.equals("warm")) {
                        waitForWarmWorld(bots, server);
                        java.util.Set<Long> mutationColumns = new java.util.HashSet<>();
                        for (Bot bot : bots) {
                            if (!bot.prepareMutationTarget(mutationColumns)) {
                                throw new IllegalStateException("No placeable warm-world target for bot " + bot.id);
                            }
                        }
                        PerformanceProfiler.get().reset();
                        server.resetProfilerOnTick().get(5, TimeUnit.SECONDS);
                        measurementBaseline = server.sessions().networkSnapshot();
                    }

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                    long nextInput = System.nanoTime();
                    long nextMutation = System.nanoTime();
                    long mutationInterval = Math.max(1, TimeUnit.SECONDS.toNanos(1) / mutationRate);
                    int mutationBot = 0;
                    while (System.nanoTime() < deadline) {
                        for (Bot bot : bots) bot.update();
                        long now = System.nanoTime();
                        if (now >= nextInput) {
                            for (Bot bot : bots) bot.move(route);
                            nextInput += TimeUnit.MILLISECONDS.toNanos(50);
                        }
                        if (route.equals("warm") && now >= nextMutation) {
                            int catchUp = 0;
                            while (now >= nextMutation && catchUp++ < Math.max(1, players * 2)) {
                                bots.get(mutationBot++ % bots.size()).mutateWarmWorld();
                                nextMutation += mutationInterval;
                            }
                            if (now - nextMutation > TimeUnit.SECONDS.toNanos(1)) {
                                nextMutation = now + mutationInterval;
                            }
                        }
                        Thread.sleep(1);
                    }
                    if (route.equals("fast") && catchUpSeconds > 0) {
                        bots.forEach(Bot::beginCatchUp);
                        long catchUpDeadline = System.nanoTime()
                                + TimeUnit.SECONDS.toNanos(catchUpSeconds);
                        long nextNeutralInput = 0;
                        while (System.nanoTime() < catchUpDeadline
                                && bots.stream().anyMatch(bot -> !bot.currentViewPresented())) {
                            long now = System.nanoTime();
                            if (now >= nextNeutralInput) {
                                for (Bot bot : bots) bot.stopMovement();
                                nextNeutralInput = now + TimeUnit.MILLISECONDS.toNanos(50);
                            }
                            for (Bot bot : bots) bot.update();
                            Thread.sleep(1);
                        }
                    }
                    if (route.equals("warm")) {
                        long settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (System.nanoTime() < settleDeadline
                                && bots.stream().anyMatch(bot -> !bot.mutationSettled())) {
                            for (Bot bot : bots) bot.update();
                            Thread.sleep(1);
                        }
                    }
                    for (int i = 0; i < 10; i++) {
                        for (Bot bot : bots) bot.update();
                        Thread.sleep(1);
                    }
                    long joined = bots.stream().filter(Bot::joined).count();
                    var tick = server.profiler().stats(
                            de.skyengine.server.profile.ServerProfiler.Phase.SERVER_TICK_TOTAL);
                    var net = server.sessions().networkSnapshot();
                    long chunks = bots.stream().mapToLong(Bot::chunks).sum();
                    long batches = bots.stream().mapToLong(Bot::batches).sum();
                    long resident = bots.stream().mapToLong(Bot::residentChunks).sum();
                    long unloaded = bots.stream().mapToLong(Bot::unloadedChunks).sum();
                    long meshBatches = bots.stream().mapToLong(Bot::meshBatches).sum();
                    long meshSections = bots.stream().mapToLong(Bot::meshSections).sum();
                    long presented = bots.stream().mapToLong(Bot::presentedChunks).sum();
                    long visibleResident = bots.stream().mapToLong(Bot::visibleResidentChunks).sum();
                    int minimumPresentationRadius = bots.stream().mapToInt(Bot::presentationRadius)
                            .min().orElse(-1);
                    long expectedVisible = (long) players * visibleChunkCount(viewDistance);
                    double minimumTravelBlocks = bots.stream().mapToDouble(Bot::travelDistanceBlocks)
                            .min().orElse(0);
                    long mutationRequests = bots.stream().mapToLong(Bot::mutationRequests).sum();
                    long mutationAccepted = bots.stream().mapToLong(Bot::mutationAccepted).sum();
                    long mutationRejected = bots.stream().mapToLong(Bot::mutationRejected).sum();
                    long mutationObserved = bots.stream().mapToLong(Bot::mutationStateChanges).sum();
                    System.out.printf("%s bots %d/%d joined, RD%d %s, tick median/p95/max "
                                    + "%.3f/%.3f/%.3f ms, RX/TX %.1f/%.1f MiB, queues %d/%d, "
                                    + "installed/resident/unloaded/batches %d/%d/%d/%d, "
                                    + "meshed sections/visible/presented %d/%d/%d/%d, radius %d, "
                                    + "server resident %d, min travel %.1f blocks, mutations %d/%d/%d/%d%n",
                            dedicated ? "Dedicated TCP" : "Integrated local", joined, players,
                            viewDistance, route, tick.medianMillis(), tick.p95Millis(), tick.maximumMillis(),
                            mib(net.receivedBytes()), mib(net.sentBytes()), net.inboundQueue(), net.outboundQueue(),
                            chunks, resident, unloaded, batches, meshSections, visibleResident,
                            presented, expectedVisible, minimumPresentationRadius,
                            net.residentServerChunks(), minimumTravelBlocks, mutationRequests,
                            mutationAccepted, mutationRejected, mutationObserved);
                    if (mutationRejected > 0) {
                        System.out.println("Warm mutation rejection samples: " + bots.stream()
                                .map(Bot::lastMutationRejection).filter(message -> !message.isBlank())
                                .distinct().toList());
                    }
                    if (report != null) writeReport(report, runtime, route, viewDistance, bandwidthMiB,
                            seed, catchUpSeconds, players, seconds, joined, chunks, resident,
                            visibleResident, expectedVisible, unloaded, batches, meshBatches,
                            meshSections, presented, minimumPresentationRadius, bots, tick, net,
                            measurementBaseline, clientWorkers, !ownsClientWorkers, mutationRate);
                    for (Bot bot : bots) bot.throwIfFailed();
                    if (joined != players) {
                        throw new IllegalStateException("Only " + joined + " bots reached PLAY");
                    }
                } finally {
                    for (Bot bot : bots) bot.close();
                    if (connector != null) connector.close();
                    if (ownsClientWorkers) clientWorkers.dispose();
                }
            }
        } finally {
            deleteTree(root);
            PerformanceProfiler.get().setEnabled(false);
        }
    }

    private static void writeReport(Path report, String runtime, String route, int viewDistance,
                                    int bandwidthMiB, int seed, int catchUpSeconds, int players,
                                    int seconds, long joined, long chunks, long resident,
                                    long visibleResident, long expectedVisible, long unloaded,
                                    long batches, long meshBatches, long meshSections, long presented,
                                    int minimumPresentationRadius, List<Bot> bots,
                                    de.skyengine.server.profile.ServerProfiler.Stats tick,
                                    de.skyengine.server.network.ServerSessionManager.NetworkSnapshot net,
                                    de.skyengine.server.network.ServerSessionManager.NetworkSnapshot baseline,
                                    WorldWorkerPool clientWorkers, boolean sharedClientServerWorkers,
                                    int mutationRate)
            throws Exception {
        Path parent = report.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        List<String> fields = new ArrayList<>();
        add(fields, "mode", runtime.equals("dedicated") ? "dedicated-tcp" : "integrated-local-transport");
        add(fields, "worldRuntime", runtime.equals("headless") ? "headless" : "authoritative");
        add(fields, "route", route);
        add(fields, "viewDistance", viewDistance);
        add(fields, "bandwidthMiBPerSecond", bandwidthMiB);
        add(fields, "seed", seed);
        add(fields, "catchUpSeconds", catchUpSeconds);
        add(fields, "configuredMutationsPerSecond", mutationRate);
        add(fields, "players", players);
        add(fields, "seconds", seconds);
        add(fields, "joined", joined);
        add(fields, "installedChunks", chunks);
        add(fields, "residentClientChunks", resident);
        add(fields, "visibleResidentClientChunks", visibleResident);
        add(fields, "expectedVisibleClientChunks", expectedVisible);
        add(fields, "unloadedClientChunks", unloaded);
        add(fields, "acknowledgedBatches", batches);
        add(fields, "clientMeshBatches", meshBatches);
        add(fields, "clientMeshSections", meshSections);
        add(fields, "presentedClientChunks", presented);
        add(fields, "missingPresentedClientChunks", Math.max(0, expectedVisible - presented));
        add(fields, "minimumPresentationRadius", minimumPresentationRadius);
        add(fields, "minimumTravelDistanceBlocks", bots.stream().mapToDouble(Bot::travelDistanceBlocks)
                .min().orElse(0));
        add(fields, "maximumTravelDistanceBlocks", bots.stream().mapToDouble(Bot::travelDistanceBlocks)
                .max().orElse(0));
        add(fields, "mutationRequests", bots.stream().mapToLong(Bot::mutationRequests).sum());
        add(fields, "mutationAccepted", bots.stream().mapToLong(Bot::mutationAccepted).sum());
        add(fields, "mutationRejected", bots.stream().mapToLong(Bot::mutationRejected).sum());
        add(fields, "mutationStateChangesObserved", bots.stream().mapToLong(Bot::mutationStateChanges).sum());
        add(fields, "sharedClientServerWorkers", sharedClientServerWorkers);
        add(fields, "clientWorkers", clientWorkers.workerCount());
        add(fields, "activeClientWorkers", clientWorkers.activeTasks());
        add(fields, "queuedClientTasks", clientWorkers.queuedTasks());
        add(fields, "interestToFirstInstalledMillis", maximumLatency(bots, Bot::firstChunkMillis));
        add(fields, "interestToCollisionNeighborhoodMillis", maximumLatency(bots, Bot::collisionReadyMillis));
        add(fields, "interestToFullViewMillis", maximumLatency(bots, Bot::fullViewMillis));
        add(fields, "catchUpToFullViewMillis", maximumLatency(bots, Bot::catchUpMillis));
        add(fields, "tickMedianMillis", tick.medianMillis());
        add(fields, "tickP95Millis", tick.p95Millis());
        add(fields, "tickMaximumMillis", tick.maximumMillis());
        add(fields, "receivedPackets", net.receivedPackets());
        add(fields, "receivedBytes", net.receivedBytes());
        add(fields, "sentPackets", net.sentPackets());
        add(fields, "sentBytes", net.sentBytes());
        add(fields, "medianRttMillis", net.medianRttMillis());
        add(fields, "p95RttMillis", net.p95RttMillis());
        add(fields, "inboundQueue", net.inboundQueue());
        add(fields, "outboundQueue", net.outboundQueue());
        add(fields, "trackedServerChunks", net.trackedChunks());
        add(fields, "residentServerChunks", net.residentServerChunks());
        add(fields, "chunksPending", net.chunksPending());
        add(fields, "snapshotsInFlight", net.snapshotsInFlight());
        add(fields, "snapshotRetriesPending", net.chunkSnapshotRetriesPending());
        add(fields, "chunksReadyToSend", net.chunksReadyToSend());
        add(fields, "chunksAwaitingAck", net.chunksAwaitingAck());
        add(fields, "chunksApplied", net.chunksApplied());
        add(fields, "chunksReadyBytes", net.chunksReadyBytes());
        add(fields, "chunksAwaitingAckBytes", net.chunksAwaitingAckBytes());
        add(fields, "snapshotRetries", net.chunkSnapshotRetries());
        add(fields, "chunkAckTimeouts", net.chunkAckTimeouts());
        add(fields, "chunkResyncRequests", net.chunkResyncRequests());
        add(fields, "worldWorkers", net.worldWorkers());
        add(fields, "activeWorldWorkers", net.activeWorldWorkers());
        add(fields, "queuedWorldTasks", net.queuedWorldTasks());
        add(fields, "logicalSnapshotBytes", net.logicalSnapshotBytes());
        add(fields, "encodedCacheBytes", net.encodedCacheBytes());
        add(fields, "compressedCacheBytes", net.compressedCacheBytes());
        add(fields, "replicationCacheBytes", net.replicationCacheBytes());
        add(fields, "cacheEvictions", net.cacheEvictions());
        add(fields, "cachePinnedBytes", net.cachePinnedBytes());
        add(fields, "snapshotRequests", net.snapshotRequests());
        add(fields, "snapshotCacheHits", net.snapshotCacheHits());
        add(fields, "snapshotCacheHitRate", hitRate(net.snapshotCacheHits(), net.snapshotRequests()));
        add(fields, "snapshotSharedReuses", Math.max(0, net.snapshotRequests() - net.snapshotCreates()));
        add(fields, "snapshotCreates", net.snapshotCreates());
        add(fields, "encodeRequests", net.encodeRequests());
        add(fields, "encodeCacheHits", net.encodeCacheHits());
        add(fields, "encodeCacheHitRate", hitRate(net.encodeCacheHits(), net.encodeRequests()));
        add(fields, "encodeCreates", net.encodeCreates());
        add(fields, "compressionRequests", net.compressionRequests());
        add(fields, "compressionCacheHits", net.compressionCacheHits());
        add(fields, "compressionCacheHitRate", hitRate(net.compressionCacheHits(), net.compressionRequests()));
        add(fields, "compressionCreates", net.compressionCreates());
        add(fields, "snapshotBytesAllocated", net.snapshotBytesAllocated());
        add(fields, "bytesCopied", net.bytesCopied());
        add(fields, "wireBytesProduced", net.wireBytesProduced());
        add(fields, "directBufferBytes", net.directBufferBytes());
        add(fields, "encodedBytesSaved", net.encodedBytesSaved());
        add(fields, "compressedBytesSaved", net.compressedBytesSaved());
        add(fields, "activeSnapshotLeases", net.activeSnapshotLeases());
        add(fields, "oldestLeaseAgeNanos", net.oldestLeaseAgeNanos());
        add(fields, "pinnedRevisionCount", net.pinnedRevisionCount());
        if (baseline != null) {
            add(fields, "warmSentPackets", nonNegativeDifference(net.sentPackets(), baseline.sentPackets()));
            add(fields, "warmSentBytes", nonNegativeDifference(net.sentBytes(), baseline.sentBytes()));
            add(fields, "warmSnapshotCreates", nonNegativeDifference(
                    net.snapshotCreates(), baseline.snapshotCreates()));
            add(fields, "warmSnapshotBytesAllocated", nonNegativeDifference(
                    net.snapshotBytesAllocated(), baseline.snapshotBytesAllocated()));
            add(fields, "warmBytesCopied", nonNegativeDifference(net.bytesCopied(), baseline.bytesCopied()));
            add(fields, "warmWireBytesProduced", nonNegativeDifference(
                    net.wireBytesProduced(), baseline.wireBytesProduced()));
        }
        String lanes = net.workerLanes().stream().map(lane -> String.format(Locale.ROOT,
                "{\"lane\":%d,\"queued\":%d,\"running\":%d,\"completed\":%d,"
                        + "\"oldestQueuedAgeMillis\":%.6f,\"queueWaitMedianMillis\":%.6f,"
                        + "\"queueWaitP95Millis\":%.6f,\"completedPerSecond\":%.6f}",
                lane.lane(), lane.queued(), lane.running(), lane.completed(),
                lane.oldestQueuedAgeNanos() / 1_000_000.0, lane.queueWaitMedianNanos() / 1_000_000.0,
                lane.queueWaitP95Nanos() / 1_000_000.0, lane.completedPerSecond()))
                .collect(java.util.stream.Collectors.joining(","));
        fields.add("\"workerLanes\":[" + lanes + "]");
        String clientLanes = java.util.stream.IntStream.range(0, 4).mapToObj(index -> {
            var lane = clientWorkers.laneStats(index);
            return String.format(Locale.ROOT,
                    "{\"lane\":%d,\"queued\":%d,\"running\":%d,\"completed\":%d,"
                            + "\"oldestQueuedAgeMillis\":%.6f,\"queueWaitMedianMillis\":%.6f,"
                            + "\"queueWaitP95Millis\":%.6f,\"completedPerSecond\":%.6f}",
                    index, lane.queued(), lane.running(), lane.completed(),
                    lane.oldestQueuedAgeNanos() / 1_000_000.0,
                    lane.queueWaitMedianNanos() / 1_000_000.0,
                    lane.queueWaitP95Nanos() / 1_000_000.0, lane.completedPerSecond());
        }).collect(java.util.stream.Collectors.joining(","));
        fields.add("\"clientWorkerLanes\":[" + clientLanes + "]");
        var profilerSnapshot = PerformanceProfiler.get().publishSnapshot();
        add(fields, "clientDeltaCowBytes", profilerSnapshot.counters().getOrDefault(
                PerformanceProfiler.Counter.L0_CLIENT_DELTA_COW_BYTES, 0L));
        var pipeline = profilerSnapshot.l0();
        String phases = java.util.Arrays.stream(PerformanceProfiler.WorkerSection.values())
                .map(section -> {
                    PerformanceProfiler.TimingStats stats = pipeline.getOrDefault(
                            section, PerformanceProfiler.TimingStats.EMPTY);
                    return String.format(Locale.ROOT,
                            "\"%s\":{\"meanMillis\":%.6f,\"p95Millis\":%.6f,"
                                    + "\"maxMillis\":%.6f,\"samples\":%d,\"jobsPerSecond\":%.6f}",
                            section.name(), stats.meanMillis(), stats.p95Millis(),
                            stats.maxNanos() / 1_000_000.0,
                            stats.samples(), stats.jobsPerSecond());
                }).collect(java.util.stream.Collectors.joining(","));
        fields.add("\"pipelinePhases\":{" + phases + "}");
        Files.writeString(report, "{\n  " + String.join(",\n  ", fields) + "\n}\n");
        System.out.println("JSON report: " + report.toAbsolutePath());
    }

    private static void add(List<String> fields, String name, String value) {
        fields.add("\"" + name + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    private static void add(List<String> fields, String name, long value) {
        fields.add("\"" + name + "\":" + value);
    }

    private static void add(List<String> fields, String name, boolean value) {
        fields.add("\"" + name + "\":" + value);
    }

    private static void add(List<String> fields, String name, double value) {
        fields.add(String.format(Locale.ROOT, "\"%s\":%.6f", name, value));
    }

    private static double hitRate(long hits, long requests) {
        return requests == 0 ? 0.0 : (double) hits / requests;
    }

    private static long nonNegativeDifference(long after, long before) {
        return Math.max(0, after - before);
    }

    private static void waitForWarmWorld(List<Bot> bots, ServerApplication server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long nextInput = 0;
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            if (now >= nextInput) {
                for (Bot bot : bots) bot.move("stationary");
                nextInput = now + TimeUnit.MILLISECONDS.toNanos(50);
            }
            for (Bot bot : bots) bot.update();
            var net = server.sessions().networkSnapshot();
            boolean clientsReady = bots.stream().allMatch(Bot::currentViewPresented);
            boolean pipelineIdle = net.chunksPending() == 0 && net.snapshotsInFlight() == 0
                    && net.chunksReadyToSend() == 0 && net.chunksAwaitingAck() == 0
                    && net.queuedWorldTasks() == 0 && net.activeWorldWorkers() == 0;
            if (clientsReady && pipelineIdle) return;
            Thread.sleep(1);
        }
        throw new IllegalStateException("Warm-world setup did not reach an idle fully presented view");
    }

    private static double maximumLatency(List<Bot> bots,
                                         java.util.function.ToDoubleFunction<Bot> metric) {
        double maximum = 0;
        for (Bot bot : bots) {
            double value = metric.applyAsDouble(bot);
            if (value < 0) return -1;
            maximum = Math.max(maximum, value);
        }
        return maximum;
    }

    private static ServerConfig benchmarkConfig(ServerConfig value, int maxPlayers, int viewDistance,
                                                int port, int bandwidthMiB, int workerThreads) {
        return new ServerConfig(value.serverDirectory(), "127.0.0.1", port, maxPlayers,
                viewDistance, Math.min(value.simulationDistance(), viewDistance), value.world(), value.motd(), value.tickRate(),
                value.keepAliveIntervalSeconds(), value.timeoutSeconds(), value.compression(),
                value.compressionLevel(), value.compressionThreshold(), value.maxPacketSize(),
                value.maxDecompressedPacketSize(), bandwidthMiB * 1024 * 1024, value.autosaveIntervalTicks(),
                value.authentication(), workerThreads);
    }

    private static int reservePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static double mib(long bytes) { return bytes / (1024.0 * 1024.0); }

    private static int visibleChunkCount(int radius) {
        int result = 0;
        int squared = radius * radius;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + z * z <= squared) result++;
            }
        }
        return result;
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static final class Bot implements AutoCloseable {
        private record MutationTarget(String dimension, int x, int y, int z, int originalState) { }
        private static final int[][] MUTATION_OFFSETS = createMutationOffsets();
        private final int id;
        private final TransportConnection connection;
        private final ReplicatedChunkCache chunks;
        private final ClientNetworkSession session;
        private final ChunkManager chunkManager;
        private final long renderGeneration;
        private final int viewDistance;
        private final long startedNanos = System.nanoTime();
        private final AtomicLong batches = new AtomicLong();
        private final AtomicLong meshBatches = new AtomicLong();
        private final AtomicLong meshSections = new AtomicLong();
        private final AtomicLong firstChunkNanos = new AtomicLong();
        private final AtomicLong collisionReadyNanos = new AtomicLong();
        private final AtomicLong fullViewNanos = new AtomicLong();
        private final AtomicLong catchUpStartedNanos = new AtomicLong();
        private final AtomicLong catchUpFullViewNanos = new AtomicLong();
        private final AtomicLong mutationRequests = new AtomicLong();
        private final AtomicLong mutationAccepted = new AtomicLong();
        private final AtomicLong mutationRejected = new AtomicLong();
        private final AtomicLong mutationStateChanges = new AtomicLong();
        private final AtomicReference<String> lastMutationRejection = new AtomicReference<>("");
        private final AtomicReference<String> failure = new AtomicReference<>();
        private volatile ReplicatedChunkWorldAdapter adapter;
        private long sequence;
        private long actionId;
        private volatile boolean joined;
        private boolean flightConfigured;
        private volatile double initialX = Double.NaN;
        private volatile double initialZ = Double.NaN;
        private volatile double latestX = Double.NaN;
        private volatile double latestY = Double.NaN;
        private volatile double latestZ = Double.NaN;
        private volatile String latestDimension;
        private MutationTarget mutationTarget;
        private int lastObservedMutationState = -1;
        private boolean awaitingMutationStateChange;

        private Bot(int id, TransportConnection connection, int viewDistance,
                    WorldWorkerPool clientWorkers) {
            this.id = id;
            this.connection = connection;
            this.viewDistance = viewDistance;
            this.chunkManager = new ChunkManager(null, null, clientWorkers, true);
            this.renderGeneration = this.chunkManager.attachRenderer();
            this.chunkManager.setRenderDistance(viewDistance);
            this.chunks = new ReplicatedChunkCache(null);
            this.chunks.setTrustedImmutableTransfer(connection.transfersImmutableObjects());
            this.session = new ClientNetworkSession(connection, this.chunks,
                    ignored -> ClientNetworkSession.PackValidation.acceptAll(),
                    new ClientNetworkSession.Listener() {
                        @Override public void registryReceived(
                                de.skyengine.shared.network.pack.RegistryMapping mapping) {
                            if (mapping.registry().equals("block_state") && !mapping.identifiers().isEmpty()) {
                                chunks.setBlockStateMapper(LegacyBlockStateNetworkMapper.create(mapping));
                            }
                        }

                        @Override public void joined(de.skyengine.shared.network.packets.CorePackets.JoinGame packet) {
                            ReplicatedChunkWorldAdapter created = new ReplicatedChunkWorldAdapter(
                                    packet.dimension(), chunkManager);
                            adapter = created;
                            chunks.setListener(created);
                            Bot.this.joined = true;
                        }

                        @Override public void authoritativePlayerState(
                                de.skyengine.shared.player.PlayerStateSnapshot state) {
                            if (Double.isNaN(initialX)) {
                                initialX = state.x();
                                initialZ = state.z();
                            }
                            latestX = state.x();
                            latestY = state.y();
                            latestZ = state.z();
                            latestDimension = state.dimension();
                            chunkManager.setReplicatedRenderAnchor(
                                    ((int) Math.floor(state.x())) >> 5,
                                    ((int) Math.floor(state.z())) >> 5);
                        }

                        @Override public void disconnected(de.skyengine.shared.network.DisconnectReason reason,
                                                           String message) {
                            failure.compareAndSet(null, "Bot " + id + " disconnected: " + reason + ": " + message);
                        }

                        @Override public void chunkBatchApplied(ReplicatedChunkCache.AppliedBatch batch) {
                            batches.incrementAndGet();
                        }

                        @Override public void blockActionResult(
                                de.skyengine.shared.network.packets.CorePackets.BlockActionResult result) {
                            if (mutationTarget == null) return;
                            if (result.accepted()) mutationAccepted.incrementAndGet();
                            else {
                                mutationRejected.incrementAndGet();
                                lastMutationRejection.set(result.message());
                                awaitingMutationStateChange = false;
                            }
                        }
                    });
            this.chunks.setResyncRequester(this.session::requestChunkResync);
        }

        void start() { this.session.start("Bot" + this.id, null); }

        void update() {
            this.session.update();
            ReplicatedChunkWorldAdapter current = this.adapter;
            if (current != null) current.drainPreparedChunks();
            this.chunkManager.processRemeshes();
            drainMeshUploads();
            observeMutationStateChange();
            recordReadiness();
        }

        void move(String route) {
            if (!this.joined) return;
            long next = ++this.sequence;
            if (route.equals("fast") && !this.flightConfigured) {
                this.session.sendCommand(++this.actionId, "gamemode spectator");
                for (int i = 0; i < 18; i++) {
                    this.session.sendAbility(++this.actionId, next,
                            PlayerAbilityAction.SPECTATOR_SPEED_UP);
                }
                this.flightConfigured = true;
            }
            this.session.sendInput(new PlayerInputFrame(next, next, route.equals("fast") ? 1 : 0,
                    0, 0, 0, 0, 0));
        }

        void beginCatchUp() {
            this.catchUpStartedNanos.compareAndSet(0, System.nanoTime());
            stopMovement();
        }

        void stopMovement() {
            if (!this.joined) return;
            long next = ++this.sequence;
            this.session.sendInput(new PlayerInputFrame(next, next, 0, 0, 0, 0, 0, 0));
        }

        boolean prepareMutationTarget(java.util.Set<Long> usedColumns) {
            if (!this.joined || Double.isNaN(this.latestX) || Double.isNaN(this.latestY)
                    || this.latestDimension == null) return false;
            int baseX = (int) Math.floor(this.latestX);
            int baseY = (int) Math.floor(this.latestY);
            int baseZ = (int) Math.floor(this.latestZ);
            int preferred = Math.floorMod(this.id, MUTATION_OFFSETS.length);
            for (int offsetIndex = 0; offsetIndex < MUTATION_OFFSETS.length; offsetIndex++) {
                int[] offset = MUTATION_OFFSETS[(preferred + offsetIndex) % MUTATION_OFFSETS.length];
                int x = baseX + offset[0], z = baseZ + offset[1];
                long columnKey = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                if (usedColumns.contains(columnKey)) continue;
                for (int y = Math.min(511, baseY + 1); y >= Math.max(0, baseY - 5); y--) {
                    int state = clientBlock(x, y, z);
                    if (state == 0 || de.skyengine.game.world.item.Items.forBlock(
                            de.skyengine.game.world.block.Blocks.getState(state).getBlock()) == null) continue;
                    double dx = x + 0.5 - this.latestX;
                    double dy = y + 0.5 - (this.latestY + 1.62);
                    double dz = z + 0.5 - this.latestZ;
                    if (dx * dx + dy * dy + dz * dz > 34.0) continue;
                    this.mutationTarget = new MutationTarget(this.latestDimension, x, y, z, state);
                    this.lastObservedMutationState = state;
                    usedColumns.add(columnKey);
                    return true;
                }
            }
            return false;
        }

        private static int[][] createMutationOffsets() {
            List<int[]> offsets = new ArrayList<>();
            for (int z = -5; z <= 5; z++) {
                for (int x = -5; x <= 5; x++) {
                    int distanceSquared = x * x + z * z;
                    if (distanceSquared < 4 || distanceSquared > 25) continue;
                    offsets.add(new int[]{x, z});
                }
            }
            offsets.sort(java.util.Comparator
                    .<int[]>comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1])
                    .thenComparingInt(offset -> offset[0])
                    .thenComparingInt(offset -> offset[1]));
            return offsets.toArray(int[][]::new);
        }

        void mutateWarmWorld() {
            MutationTarget target = this.mutationTarget;
            if (target == null) return;
            int current = clientBlock(target.x(), target.y(), target.z());
            if (this.awaitingMutationStateChange) return;
            BlockActionRequest request;
            long nextAction = ++this.actionId;
            if (current == target.originalState()) {
                request = new BlockActionRequest(nextAction, BlockActionRequest.Action.START_BREAK,
                        target.dimension(), target.x(), target.y(), target.z(), 1, 0, current);
            } else if (current == 0) {
                request = placementRequest(nextAction, target);
                if (request == null) return;
            } else {
                return;
            }
            this.session.sendBlockAction(request);
            this.mutationRequests.incrementAndGet();
            this.awaitingMutationStateChange = true;
        }

        private BlockActionRequest placementRequest(long nextAction, MutationTarget target) {
            // support dx/dy/dz followed by the face pointing from support into the target cell
            int[][] supports = {{0, -1, 0, 0}, {1, 0, 0, 4}, {-1, 0, 0, 5},
                    {0, 0, 1, 2}, {0, 0, -1, 3}};
            for (int[] support : supports) {
                int x = target.x() + support[0];
                int y = target.y() + support[1];
                int z = target.z() + support[2];
                if (y < 0 || y >= 512) continue;
                int supportState = clientBlock(x, y, z);
                if (supportState <= 0 || de.skyengine.game.world.block.Blocks.getState(supportState)
                        .getBlock().isReplaceable()) continue;
                double dx = x + 0.5 - this.latestX;
                double dy = y + 0.5 - (this.latestY + 1.62);
                double dz = z + 0.5 - this.latestZ;
                if (dx * dx + dy * dy + dz * dz > 34.0) continue;
                return new BlockActionRequest(nextAction, BlockActionRequest.Action.PLACE,
                        target.dimension(), x, y, z, support[3], 0, supportState,
                        target.originalState(), 0, 128, 128, 128, false);
            }
            return null;
        }

        private void observeMutationStateChange() {
            MutationTarget target = this.mutationTarget;
            if (target == null) return;
            int current = clientBlock(target.x(), target.y(), target.z());
            if (current == this.lastObservedMutationState) return;
            this.lastObservedMutationState = current;
            this.mutationStateChanges.incrementAndGet();
            this.awaitingMutationStateChange = false;
        }

        private int clientBlock(int x, int y, int z) {
            Chunk chunk = this.chunkManager.getChunk(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
            return chunk == null ? -1 : chunk.getBlock(Math.floorMod(x, 32), y, Math.floorMod(z, 32));
        }

        boolean joined() { return this.joined; }
        long chunks() { return this.chunks.installedChunkCount(); }
        long batches() { return this.batches.get(); }
        long residentChunks() { return this.chunks.size(); }
        long visibleResidentChunks() { return this.chunks.visibleSize(); }
        long unloadedChunks() { return this.chunks.unloadedChunkCount(); }
        long meshBatches() { return this.meshBatches.get(); }
        long meshSections() { return this.meshSections.get(); }
        long mutationRequests() { return this.mutationRequests.get(); }
        long mutationAccepted() { return this.mutationAccepted.get(); }
        long mutationRejected() { return this.mutationRejected.get(); }
        long mutationStateChanges() { return this.mutationStateChanges.get(); }
        String lastMutationRejection() { return this.lastMutationRejection.get(); }
        boolean mutationSettled() { return !this.awaitingMutationStateChange; }
        long presentedChunks() {
            return this.chunkManager.loadedChunks().stream()
                    .filter(chunk -> this.chunkManager.isChunkPresented(chunk.chunkX, chunk.chunkZ)).count();
        }
        int presentationRadius() { return this.chunkManager.replicatedPresentationRadius(); }
        boolean currentViewPresented() { return presentationRadius() >= this.viewDistance; }
        double travelDistanceBlocks() {
            if (Double.isNaN(this.initialX) || Double.isNaN(this.latestX)) return 0;
            return Math.hypot(this.latestX - this.initialX, this.latestZ - this.initialZ);
        }
        double firstChunkMillis() { return elapsedMillis(this.firstChunkNanos.get()); }
        double collisionReadyMillis() { return elapsedMillis(this.collisionReadyNanos.get()); }
        double fullViewMillis() { return elapsedMillis(this.fullViewNanos.get()); }
        double catchUpMillis() {
            long started = this.catchUpStartedNanos.get();
            long finished = this.catchUpFullViewNanos.get();
            return started == 0 ? 0 : finished == 0 ? -1 : (finished - started) / 1_000_000.0;
        }

        void throwIfFailed() {
            String problem = this.failure.get();
            if (problem != null) throw new IllegalStateException(problem);
        }

        private double elapsedMillis(long finishedNanos) {
            return finishedNanos == 0 ? -1 : (finishedNanos - this.startedNanos) / 1_000_000.0;
        }

        private void drainMeshUploads() {
            boolean applied = drainMeshQueue(this.chunkManager.getPlayerUploadQueue());
            applied |= drainMeshQueue(this.chunkManager.getPriorityUploadQueue());
            applied |= drainMeshQueue(this.chunkManager.getUploadQueue());
            if (applied) this.chunkManager.replicatedUploadsApplied();
            this.chunkManager.refreshReplicatedPresentation();
        }

        private boolean drainMeshQueue(java.util.Queue<ChunkManager.MeshBatch> queue) {
            boolean applied = false;
            ChunkManager.MeshBatch batch;
            while ((batch = queue.poll()) != null) {
                this.meshBatches.incrementAndGet();
                for (ChunkManager.MeshResult result : batch.results()) {
                    this.meshSections.incrementAndGet();
                    Chunk chunk = this.chunkManager.getChunk(result.chunkX(), result.chunkZ());
                    if (chunk != null && chunk.tryApplyMeshSection(this.renderGeneration,
                            result.sectionY(), result.meshSeq())) {
                        chunk.markSectionUploaded(this.renderGeneration, result.sectionY());
                    }
                }
                applied = true;
            }
            return applied;
        }

        private void recordReadiness() {
            long now = System.nanoTime();
            if (this.chunks.installedChunkCount() > 0) this.firstChunkNanos.compareAndSet(0, now);
            if (this.chunks.visibleSize() >= 9) this.collisionReadyNanos.compareAndSet(0, now);
            if (this.chunkManager.replicatedPresentationRadius() >= this.viewDistance) {
                this.fullViewNanos.compareAndSet(0, now);
                if (this.catchUpStartedNanos.get() != 0) {
                    this.catchUpFullViewNanos.compareAndSet(0, now);
                }
            }
        }

        @Override public void close() {
            this.connection.close();
            this.chunkManager.awaitWorkerTasks();
            this.chunkManager.detachRenderer(this.renderGeneration);
            this.chunkManager.dispose();
        }
    }

    /** Shared event loops keep a 32-player benchmark from measuring 32 artificial IO threads. */
    private static final class RemoteConnector implements AutoCloseable {
        private final EventLoopGroup eventLoop;
        private final PacketRegistry registry = CoreProtocol.createRegistry();

        private RemoteConnector(int clients) {
            this.eventLoop = new NioEventLoopGroup(Math.max(1, Math.min(4, clients)));
        }

        private TransportConnection connect(InetSocketAddress address) throws InterruptedException {
            AtomicReference<NettyTransportConnection> created = new AtomicReference<>();
            Bootstrap bootstrap = new Bootstrap()
                    .group(this.eventLoop)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            NettyTransportConnection.Handler handler = new NettyTransportConnection.Handler();
                            NettyTransportConnection transport = new NettyTransportConnection(
                                    channel, registry, false);
                            created.set(transport);
                            handler.connection(transport);
                            channel.pipeline().addLast("frame", new VarIntFrameDecoder(
                                    ProtocolLimits.MAX_FRAME_BYTES));
                            channel.pipeline().addLast("packet", handler);
                        }
                    });
            Channel channel = bootstrap.connect(address).sync().channel();
            NettyTransportConnection connection = created.get();
            if (connection == null) {
                channel.close().syncUninterruptibly();
                throw new IllegalStateException("Client channel initialized without transport");
            }
            return connection;
        }

        @Override public void close() {
            this.eventLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    private MultiplayerLoadHarness() {
    }
}
