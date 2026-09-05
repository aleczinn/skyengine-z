package de.skyengine.server;

import de.skyengine.server.network.PlayerSession;
import de.skyengine.server.profile.ServerProfiler;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.player.PlayerGameMode;

import java.util.List;
import java.util.Locale;

/** Single command implementation used by remote players and the dedicated-server console. */
public final class ServerCommandDispatcher {
    public record Result(boolean success, List<String> messages) {
        public Result { messages = List.copyOf(messages); }
    }

    private final ServerApplication server;

    ServerCommandDispatcher(ServerApplication server) { this.server = server; }

    public Result execute(String input, PlayerSession source) {
        String command = input.strip();
        if (command.startsWith("/")) command = command.substring(1);
        String[] parts = command.split("\\s+", 2);
        String name = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        return switch (name) {
            case "list" -> list();
            case "ping" -> source == null
                    ? new Result(false, List.of("Ping is only available to a player"))
                    : new Result(true, List.of("Ping: "
                    + Math.round(source.roundTripNanos() / 1_000_000.0) + " ms"));
            case "tps", "perf" -> performance();
            case "profile" -> profile(parts);
            case "net" -> network();
            case "gamemode", "gm" -> gameMode(parts, source);
            case "kick" -> source == null ? kick(parts) : denied();
            case "stop" -> source == null ? stop() : denied();
            default -> new Result(false, List.of("Unknown command"));
        };
    }

    private Result list() {
        List<String> names = this.server.sessions().sessions().stream()
                .filter(player -> player.state() == ConnectionState.PLAY && player.identity() != null)
                .map(player -> player.identity().name()).toList();
        return new Result(true, List.of("Players (" + names.size() + "): " + String.join(", ", names)));
    }

    private Result performance() {
        ServerProfiler.Stats stats = this.server.profiler().stats(ServerProfiler.Phase.SERVER_TICK_TOTAL);
        return new Result(true, List.of("Tick median/p95/max %.3f/%.3f/%.3f ms (%d samples)"
                .formatted(stats.medianMillis(), stats.p95Millis(), stats.maximumMillis(), stats.samples())));
    }

    private Result network() {
        var stats = this.server.sessions().networkSnapshot();
        String summary = ("Players %d, RX %d packets/%d bytes, TX %d packets/%d bytes, "
                        + "queues %d/%d, RTT median/p95 %.1f/%.1f ms, tracked/resident chunks %d/%d, "
                        + "chunk encode %.1f ms/%d batches, stream pending/in-flight/retry/ready/ack/applied "
                        + "%d/%d/%d/%d/%d/%d (ready/ack %.1f/%.1f MiB), "
                        + "retry/timeout/resync %d/%d/%d, world workers active/total + queued %d/%d + %d, "
                        + "replication cache logical/encoded/compressed/total %.1f/%.1f/%.1f/%.1f MiB, "
                        + "evictions %d, leases %d (%.1f MiB, oldest %.1f ms), "
                        + "pinned revisions %d, snapshot hit/create/request %d/%d/%d, "
                        + "encode hit/create/request %d/%d/%d, compression hit/create/request %d/%d/%d, "
                        + "allocated/copied/wire/direct %.1f/%.1f/%.1f/%.1f MiB, "
                        + "reuse saved encoded/compressed %.1f/%.1f MiB")
                .formatted(stats.players(), stats.receivedPackets(), stats.receivedBytes(), stats.sentPackets(),
                        stats.sentBytes(), stats.inboundQueue(), stats.outboundQueue(),
                        stats.medianRttMillis(), stats.p95RttMillis(), stats.trackedChunks(),
                        stats.residentServerChunks(),
                        stats.chunkEncodingMillis(), stats.chunkBatchesEncoded(), stats.chunksPending(),
                        stats.snapshotsInFlight(), stats.chunkSnapshotRetriesPending(),
                        stats.chunksReadyToSend(), stats.chunksAwaitingAck(), stats.chunksApplied(),
                        stats.chunksReadyBytes() / 1048576.0,
                        stats.chunksAwaitingAckBytes() / 1048576.0,
                        stats.chunkSnapshotRetries(), stats.chunkAckTimeouts(),
                        stats.chunkResyncRequests(), stats.activeWorldWorkers(), stats.worldWorkers(),
                        stats.queuedWorldTasks(), stats.logicalSnapshotBytes() / 1048576.0,
                        stats.encodedCacheBytes() / 1048576.0,
                        stats.compressedCacheBytes() / 1048576.0,
                        stats.replicationCacheBytes() / 1048576.0, stats.cacheEvictions(),
                        stats.activeSnapshotLeases(), stats.cachePinnedBytes() / 1048576.0,
                        stats.oldestLeaseAgeNanos() / 1_000_000.0, stats.pinnedRevisionCount(),
                        stats.snapshotCacheHits(),
                        stats.snapshotCreates(), stats.snapshotRequests(), stats.encodeCacheHits(),
                        stats.encodeCreates(), stats.encodeRequests(), stats.compressionCacheHits(),
                        stats.compressionCreates(), stats.compressionRequests(),
                        stats.snapshotBytesAllocated() / 1048576.0, stats.bytesCopied() / 1048576.0,
                        stats.wireBytesProduced() / 1048576.0,
                        stats.directBufferBytes() / 1048576.0,
                        stats.encodedBytesSaved() / 1048576.0,
                        stats.compressedBytesSaved() / 1048576.0);
        List<String> messages = new java.util.ArrayList<>();
        messages.add(summary);
        if (!stats.workerLanes().isEmpty()) {
            messages.add("worker lanes " + stats.workerLanes().stream().map(lane ->
                    "%d:q%d/r%d wait %.1f/%.1f ms oldest %.1f ms %.1f jobs/s".formatted(
                            lane.lane(), lane.queued(), lane.running(),
                            lane.queueWaitMedianNanos() / 1_000_000.0,
                            lane.queueWaitP95Nanos() / 1_000_000.0,
                            lane.oldestQueuedAgeNanos() / 1_000_000.0,
                            lane.completedPerSecond())).collect(java.util.stream.Collectors.joining(", ")));
        }
        return new Result(true, messages);
    }

    private Result profile(String[] parts) {
        de.skyengine.graphics.PerformanceProfiler profiler =
                de.skyengine.graphics.PerformanceProfiler.get();
        String action = parts.length < 2 ? "status" : parts[1].strip().toLowerCase(Locale.ROOT);
        if (action.equals("start") || action.equals("on")) {
            profiler.setEnabled(true);
            return new Result(true, List.of("Chunk pipeline profiler enabled"));
        }
        if (action.equals("stop") || action.equals("off")) {
            profiler.setEnabled(false);
            return new Result(true, List.of("Chunk pipeline profiler disabled"));
        }
        if (action.equals("reset")) {
            profiler.reset();
            return new Result(true, List.of("Chunk pipeline profiler reset"));
        }
        var snapshot = profiler.publishSnapshot();
        return new Result(true, List.of(
                "Chunk pipeline profiler: " + (profiler.isEnabled() ? "enabled" : "disabled"),
                pipelineLine(snapshot, "freeze", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_SNAPSHOT_FREEZE),
                pipelineLine(snapshot, "encode", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_WIRE_ENCODE),
                pipelineLine(snapshot, "zstd", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_WIRE_COMPRESSION),
                pipelineLine(snapshot, "decode", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_REMOTE_DECODE),
                pipelineLine(snapshot, "install", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_CLIENT_INSTALL),
                pipelineLine(snapshot, "delta-cow", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_CLIENT_DELTA_COW),
                pipelineLine(snapshot, "mesh/section", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_INITIAL_MESH),
                pipelineLine(snapshot, "upload/section", de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_UPLOAD)));
    }

    private static String pipelineLine(de.skyengine.graphics.PerformanceProfiler.ProfilerSnapshot snapshot,
                                       String name,
                                       de.skyengine.graphics.PerformanceProfiler.WorkerSection section) {
        var stats = snapshot.l0().getOrDefault(section,
                de.skyengine.graphics.PerformanceProfiler.TimingStats.EMPTY);
        return "%s mean/p95/max %.3f/%.3f/%.3f ms, %.1f jobs/s (%d)".formatted(name,
                stats.meanMillis(), stats.p95Millis(), stats.maxMillis(), stats.jobsPerSecond(),
                stats.samples());
    }

    private Result kick(String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) return new Result(false, List.of("Usage: kick <player>"));
        PlayerSession target = this.server.sessions().sessions().stream()
                .filter(player -> player.identity() != null
                        && player.identity().name().equalsIgnoreCase(parts[1].strip()))
                .findFirst().orElse(null);
        if (target == null) return new Result(false, List.of("Player not found"));
        String name = target.identity().name();
        this.server.sessions().disconnect(target, DisconnectReason.KICKED, "Kicked by console");
        return new Result(true, List.of("Kicked " + name));
    }

    private Result gameMode(String[] parts, PlayerSession source) {
        if (parts.length < 2 || parts[1].isBlank()) {
            return new Result(false, List.of("Usage: gamemode <survival|creative|spectator> [player]"));
        }
        String[] arguments = parts[1].strip().split("\\s+");
        PlayerGameMode mode;
        try { mode = PlayerGameMode.valueOf(arguments[0].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            return new Result(false, List.of("Unknown game mode: " + arguments[0]));
        }
        PlayerSession target = source;
        if (arguments.length >= 2) {
            if (source != null) return denied();
            target = this.server.sessions().sessions().stream()
                    .filter(player -> player.identity() != null
                            && player.identity().name().equalsIgnoreCase(arguments[1]))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            return new Result(false, List.of(source == null
                    ? "Console must specify a player" : "Player not found"));
        }
        if (!this.server.sessions().setGameMode(target, mode)) {
            return new Result(false, List.of("Player is not in PLAY"));
        }
        return new Result(true, List.of("Set " + target.identity().name() + " to "
                + mode.name().toLowerCase(Locale.ROOT)));
    }

    private Result stop() {
        this.server.requestStop("Console command");
        return new Result(true, List.of("Stopping server"));
    }

    private static Result denied() { return new Result(false, List.of("Insufficient permission")); }
}
