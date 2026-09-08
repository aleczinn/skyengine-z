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
    public record Suggestions(List<String> lines, String hint) {
        public Suggestions {
            lines = List.copyOf(lines);
            hint = hint == null ? "" : hint;
        }
    }

    private static final List<String> MODES = List.of("creative", "spectator", "survival");
    private static final List<String> SERVER_COMMANDS = List.of(
            "list", "ping", "tps", "perf", "profile", "net", "gamemode", "gm", "tp");

    private final ServerApplication server;

    ServerCommandDispatcher(ServerApplication server) { this.server = server; }

    public Result execute(String input, PlayerSession source) {
        String value = input == null ? "" : input.strip();
        String canonical = value.startsWith("/") ? value : "/" + value;
        if (canonical.startsWith("//")) return gameplay(canonical, source);
        String command = canonical.substring(1).strip();
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
            case "tp", "teleport" -> teleport(parts, source);
            case "kick" -> source == null ? kick(parts) : denied();
            case "stop" -> source == null ? stop() : denied();
            default -> gameplay(canonical, source);
        };
    }

    public Suggestions suggest(String input, PlayerSession source) {
        String value = input == null ? "" : input;
        String canonical = value.startsWith("/") ? value : "/" + value;
        List<String> result = new java.util.ArrayList<>(
                source == null || source.identity() == null ? List.of()
                        : this.server.worldRuntime().suggestPlayerCommand(source.identity(), canonical));
        String hint = source == null || source.identity() == null ? ""
                : this.server.worldRuntime().hintPlayerCommand(source.identity(), canonical);
        if (canonical.startsWith("//")) return new Suggestions(distinctSorted(result), hint);

        String body = canonical.substring(1);
        int firstSpace = body.indexOf(' ');
        if (firstSpace < 0) {
            String prefix = body.toLowerCase(Locale.ROOT);
            SERVER_COMMANDS.stream().filter(name -> name.startsWith(prefix))
                    .map(name -> "/" + name).forEach(result::add);
            if (prefix.equals("gamemode") || prefix.equals("gm")) hint = gamemodeHint("");
            else if (prefix.equals("tp") || prefix.equals("teleport")) hint = teleportHint("");
            else if (prefix.equals("profile")) hint = " <start|stop|reset|status>";
        } else {
            String name = body.substring(0, firstSpace).toLowerCase(Locale.ROOT);
            String tail = body.substring(firstSpace + 1);
            if (name.equals("gamemode") || name.equals("gm")) {
                addGamemodeSuggestions(result, "/" + name, tail);
                hint = gamemodeHint(tail);
            } else if (name.equals("tp") || name.equals("teleport")) {
                addTeleportSuggestions(result, "/" + name, tail);
                hint = teleportHint(tail);
            } else if (name.equals("profile")) {
                addTailSuggestions(result, "/profile", tail, List.of("start", "stop", "reset", "status"));
                hint = " <start|stop|reset|status>";
            }
        }
        return new Suggestions(distinctSorted(result), hint);
    }

    private Result gameplay(String command, PlayerSession source) {
        if (source == null || source.identity() == null) {
            return new Result(false, List.of("This gameplay command requires a player"));
        }
        de.skyengine.game.command.CommandResult result =
                this.server.worldRuntime().executePlayerCommand(source.identity(), command);
        return new Result(result.success(), result.messages());
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
        if (arguments.length > 2) return new Result(false,
                List.of("Usage: gamemode <mode> or gamemode <player> <mode>"));
        PlayerGameMode firstMode = parseMode(arguments[0]);
        PlayerGameMode mode;
        PlayerSession target;
        if (arguments.length == 1) {
            mode = firstMode;
            target = source;
        } else if (firstMode != null) {
            // Retain the old console-friendly <mode> <player> spelling as an alias.
            mode = firstMode;
            target = findPlayer(arguments[1]);
        } else {
            target = findPlayer(arguments[0]);
            mode = parseMode(arguments[1]);
        }
        if (mode == null) return new Result(false, List.of("Unknown game mode"));
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

    private Result teleport(String[] parts, PlayerSession source) {
        if (parts.length < 2 || parts[1].isBlank()) {
            return new Result(false, List.of("Usage: tp <player> | tp <x> <y> <z> | tp <player> <x> <y> <z>"));
        }
        String[] args = parts[1].strip().split("\\s+");
        PlayerSession target = source;
        PlayerSession destinationPlayer = null;
        int coordinateOffset = 0;
        if (args.length == 1) {
            destinationPlayer = findPlayer(args[0]);
            if (source == null) return new Result(false, List.of("Console must specify a target and coordinates"));
        } else if (args.length == 4) {
            target = findPlayer(args[0]);
            coordinateOffset = 1;
        } else if (args.length != 3) {
            return new Result(false, List.of("Usage: tp <player> | tp <x> <y> <z> | tp <player> <x> <y> <z>"));
        }
        if (target == null || (args.length == 1 && destinationPlayer == null)) {
            return new Result(false, List.of("Player not found"));
        }
        String dimension;
        double x, y, z;
        float yaw, pitch;
        if (destinationPlayer != null) {
            var destination = destinationPlayer.playerState();
            dimension = destination.dimension(); x = destination.x(); y = destination.y(); z = destination.z();
            yaw = destination.yaw(); pitch = destination.pitch();
        } else {
            try {
                x = coordinate(args[coordinateOffset]);
                y = coordinate(args[coordinateOffset + 1]);
                z = coordinate(args[coordinateOffset + 2]);
            } catch (IllegalArgumentException invalid) {
                return new Result(false, List.of("Invalid coordinates"));
            }
            var current = target.playerState();
            dimension = current.dimension(); yaw = current.yaw(); pitch = current.pitch();
        }
        if (!this.server.sessions().teleport(target, dimension, x, y, z, yaw, pitch)) {
            return new Result(false, List.of("Teleport failed"));
        }
        return new Result(true, List.of("Teleported " + target.identity().name()
                + " to " + format(x) + " " + format(y) + " " + format(z)));
    }

    private PlayerSession findPlayer(String value) {
        return this.server.sessions().sessions().stream()
                .filter(player -> player.identity() != null && player.playerState() != null)
                .filter(player -> player.identity().name().equalsIgnoreCase(value)
                        || player.identity().uuid().toString().equalsIgnoreCase(value))
                .findFirst().orElse(null);
    }

    private List<String> playerNames() {
        return this.server.sessions().sessions().stream()
                .filter(player -> player.identity() != null && player.playerState() != null)
                .map(player -> player.identity().name()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static PlayerGameMode parseMode(String value) {
        try { return PlayerGameMode.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static double coordinate(String value) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException();
        return parsed;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private void addGamemodeSuggestions(List<String> result, String command, String tail) {
        List<String> completed = tokens(tail);
        boolean trailing = tail.endsWith(" ");
        String current = trailing || completed.isEmpty() ? "" : completed.removeLast();
        List<String> candidates;
        if (completed.isEmpty()) {
            candidates = new java.util.ArrayList<>(MODES);
            candidates.addAll(playerNames());
        } else if (completed.size() == 1) {
            candidates = parseMode(completed.getFirst()) == null ? MODES : playerNames();
        } else return;
        appendCandidates(result, command, completed, current, candidates);
    }

    private void addTeleportSuggestions(List<String> result, String command, String tail) {
        List<String> completed = tokens(tail);
        boolean trailing = tail.endsWith(" ");
        String current = trailing || completed.isEmpty() ? "" : completed.removeLast();
        if (!completed.isEmpty()) return;
        appendCandidates(result, command, completed, current, playerNames());
    }

    private static void addTailSuggestions(List<String> result, String command, String tail,
                                           List<String> candidates) {
        List<String> completed = tokens(tail);
        boolean trailing = tail.endsWith(" ");
        String current = trailing || completed.isEmpty() ? "" : completed.removeLast();
        if (!completed.isEmpty()) return;
        appendCandidates(result, command, completed, current, candidates);
    }

    private static void appendCandidates(List<String> result, String command, List<String> completed,
                                         String current, List<String> candidates) {
        String prefix = current.toLowerCase(Locale.ROOT);
        String fixed = command + " " + (completed.isEmpty() ? "" : String.join(" ", completed) + " ");
        candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .map(candidate -> fixed + candidate).forEach(result::add);
    }

    private static List<String> tokens(String input) {
        List<String> result = new java.util.ArrayList<>();
        for (String token : input.strip().split("\\s+")) if (!token.isEmpty()) result.add(token);
        return result;
    }

    private static String gamemodeHint(String tail) {
        int count = tokens(tail).size();
        return count == 0 ? " <creative|survival|spectator> | <player> <mode>"
                : count == 1 ? " <mode|player>" : "";
    }

    private static String teleportHint(String tail) {
        int count = tokens(tail).size();
        return switch (count) {
            case 0 -> " <player> | <x> <y> <z> | <player> <x> <y> <z>";
            case 1 -> " <y> <z> | <x> <y> <z>";
            case 2 -> " <z> | <y> <z>";
            case 3 -> " <z>";
            default -> "";
        };
    }

    private static List<String> distinctSorted(List<String> values) {
        return values.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).limit(128).toList();
    }

    private Result stop() {
        this.server.requestStop("Console command");
        return new Result(true, List.of("Stopping server"));
    }

    private static Result denied() { return new Result(false, List.of("Insufficient permission")); }
}
