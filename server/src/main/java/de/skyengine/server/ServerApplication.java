package de.skyengine.server;

import de.skyengine.server.network.NettyTransportServer;
import de.skyengine.server.network.PlayerSession;
import de.skyengine.server.network.ServerSessionManager;
import de.skyengine.server.profile.ServerProfiler;
import de.skyengine.server.world.HeadlessWorldRuntime;
import de.skyengine.server.world.AuthoritativeWorldRuntime;
import de.skyengine.server.world.ServerWorldRuntime;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.PacketRegistry;
import de.skyengine.shared.network.transport.LocalTransport;
import de.skyengine.shared.network.transport.TransportConnection;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerApplication implements AutoCloseable {
    private final ServerConfig config;
    private final ServerWorldRuntime world;
    private final PacketRegistry protocol;
    private final ServerSessionManager sessions;
    private final ServerProfiler profiler = new ServerProfiler();
    private final ServerCommandDispatcher commands;
    private final de.skyengine.server.event.ServerEventBus events = new de.skyengine.server.event.ServerEventBus();
    private final ConcurrentLinkedQueue<Runnable> tickTasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> profilerResetRequests =
            new ConcurrentLinkedQueue<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private NettyTransportServer network;
    private ServerTickLoop tickLoop;
    private long serverTick;

    public ServerApplication(ServerConfig config, ServerWorldRuntime world) {
        this.config = Objects.requireNonNull(config);
        this.world = Objects.requireNonNull(world);
        this.protocol = CoreProtocol.createRegistry();
        this.sessions = new ServerSessionManager(config, new de.skyengine.server.player.OfflineIdentityProvider(),
                world, this.events, this.profiler);
        this.commands = new ServerCommandDispatcher(this);
        this.sessions.commandDispatcher(this.commands);
    }

    public static ServerApplication dedicated(ServerConfig config) throws IOException {
        return new ServerApplication(config, new AuthoritativeWorldRuntime(config));
    }

    public synchronized void startDedicated() throws InterruptedException {
        if (this.tickLoop != null) throw new IllegalStateException("Server already started");
        this.sessions.lifecycleLogger(message -> System.out.println("[Server] " + message));
        boolean sharedEncoder = this.world instanceof AuthoritativeWorldRuntime;
        this.network = sharedEncoder
                ? new NettyTransportServer(this.protocol, this.config.maxPacketSize(),
                ((AuthoritativeWorldRuntime) this.world).workerPool().executorForLane(2),
                this.sessions::accept, this.sessions::statusResponse, this.world.replicationCacheBudget())
                : new NettyTransportServer(this.protocol, this.config.maxPacketSize(),
                Math.max(1, Math.min(4, this.config.workerThreads() / 2)), this.sessions::accept,
                this.sessions::statusResponse, this.world.replicationCacheBudget());
        this.network.bind(this.config.listenAddress());
        System.out.println("[Server] World loaded: " + this.world.directory()
                + (this.world instanceof AuthoritativeWorldRuntime authoritative
                ? " (seed " + authoritative.seed() + ")"
                : this.world instanceof HeadlessWorldRuntime headless
                ? " (seed " + headless.seed() + ")" : ""));
        System.out.println("[Server] SkyEngine server listening on " + this.network.localAddress());
        System.out.println("[Server] Workers: world+snapshot=" + this.config.workerThreads()
                + ", packet-encode=" + (sharedEncoder ? "shared/fair-lane-2" : "owned")
                + ", network-io=" + Math.min(4, Math.max(2,
                Runtime.getRuntime().availableProcessors() / 8)));
        startTickLoop("Server Tick");
    }

    public synchronized LocalTransport.Pair startIntegrated() {
        if (this.tickLoop != null) throw new IllegalStateException("Server already started");
        LocalTransport.Pair pair = LocalTransport.create();
        this.sessions.accept(pair.server());
        if (this.world instanceof AuthoritativeWorldRuntime authoritative) {
            System.out.println("[Integrated Server] Workers: shared-world-snapshot-client="
                    + authoritative.workerPool().workerCount());
        }
        startTickLoop("Integrated Server Tick");
        return pair;
    }

    /** Adds another in-process client to a running integrated server (tests/bots/tooling). */
    public synchronized TransportConnection connectLocalClient() {
        if (this.tickLoop == null || this.stopRequested.get()) {
            throw new IllegalStateException("Integrated server is not running");
        }
        LocalTransport.Pair pair = LocalTransport.create();
        this.sessions.accept(pair.server());
        return pair.client();
    }

    private void startTickLoop(String name) {
        this.tickLoop = new ServerTickLoop(this, name);
        this.tickLoop.start();
    }

    void tick(long nowNanos) {
        CompletableFuture<Void> profilerReset;
        if ((profilerReset = this.profilerResetRequests.poll()) != null) {
            this.profiler.reset();
            profilerReset.complete(null);
            while ((profilerReset = this.profilerResetRequests.poll()) != null) {
                profilerReset.complete(null);
            }
        }
        this.profiler.begin(ServerProfiler.Phase.SERVER_TICK_TOTAL);
        Runnable task;
        while ((task = this.tickTasks.poll()) != null) task.run();

        this.profiler.begin(ServerProfiler.Phase.NETWORK_INPUT);
        this.sessions.processNetwork(this.serverTick, nowNanos);
        this.profiler.end(ServerProfiler.Phase.NETWORK_INPUT);

        this.profiler.begin(ServerProfiler.Phase.CHUNK_SIMULATION);
        this.world.tick(this.serverTick);
        this.profiler.end(ServerProfiler.Phase.CHUNK_SIMULATION);

        this.profiler.begin(ServerProfiler.Phase.REPLICATION);
        this.sessions.replicate();
        this.sessions.flushOutbound();
        this.profiler.end(ServerProfiler.Phase.REPLICATION);

        if (this.serverTick > 0 && this.serverTick % this.config.autosaveIntervalTicks() == 0) {
            this.profiler.begin(ServerProfiler.Phase.PERSISTENCE);
            this.world.autosave(this.serverTick);
            this.profiler.end(ServerProfiler.Phase.PERSISTENCE);
        }
        this.serverTick++;
        this.profiler.end(ServerProfiler.Phase.SERVER_TICK_TOTAL);
        this.profiler.finishTick();
    }

    public void executeOnTick(Runnable task) { this.tickTasks.add(Objects.requireNonNull(task)); }
    /** Resets rolling tick metrics before the next tick begins, never inside an open phase. */
    public CompletableFuture<Void> resetProfilerOnTick() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        this.profilerResetRequests.add(completion);
        return completion;
    }
    public long serverTick() { return this.serverTick; }
    public ServerConfig config() { return this.config; }
    public ServerSessionManager sessions() { return this.sessions; }
    public ServerProfiler profiler() { return this.profiler; }
    public ServerCommandDispatcher commands() { return this.commands; }
    public de.skyengine.server.event.ServerEventBus events() { return this.events; }
    public boolean stopRequested() { return this.stopRequested.get(); }

    public void requestStop(String reason) {
        if (this.stopRequested.compareAndSet(false, true)) System.out.println("Stopping server: " + reason);
    }

    void finishStop() {
        if (!this.stopped.compareAndSet(false, true)) return;
        try { this.sessions.close(); }
        finally {
            try { if (this.network != null) this.network.close(); }
            finally { this.world.close(); }
        }
    }

    @Override public void close() {
        requestStop("Server closed");
        ServerTickLoop loop = this.tickLoop;
        if (loop != null) loop.close();
        else finishStop();
    }
}
