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
        return new Result(true, List.of(("Players %d, RX %d packets/%d bytes, TX %d packets/%d bytes, "
                        + "queues %d/%d, RTT median/p95 %.1f/%.1f ms, tracked chunks %d, "
                        + "chunk encode %.1f ms/%d batches")
                .formatted(stats.players(), stats.receivedPackets(), stats.receivedBytes(), stats.sentPackets(),
                        stats.sentBytes(), stats.inboundQueue(), stats.outboundQueue(),
                        stats.medianRttMillis(), stats.p95RttMillis(), stats.trackedChunks(),
                        stats.chunkEncodingMillis(), stats.chunkBatchesEncoded())));
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
