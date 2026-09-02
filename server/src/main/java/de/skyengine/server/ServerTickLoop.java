package de.skyengine.server;

import de.skyengine.shared.EngineInfo;

import java.util.concurrent.locks.LockSupport;

/** Dedicated fixed-rate tick loop. It never advances the authoritative tick without simulating it. */
public final class ServerTickLoop implements AutoCloseable {
    private static final long TICK_NANOS = 1_000_000_000L / EngineInfo.TICKS_PER_SECOND;
    private static final int MAX_CATCH_UP_TICKS = 5;
    private final ServerApplication server;
    private final Thread thread;
    private volatile boolean running;

    ServerTickLoop(ServerApplication server, String threadName) {
        this.server = server;
        this.thread = new Thread(this::run, threadName);
        this.thread.setDaemon(false);
    }

    public synchronized void start() {
        if (this.running) throw new IllegalStateException("Server tick loop already started");
        this.running = true;
        this.thread.start();
    }

    public boolean running() { return this.running; }

    private void run() {
        long deadline = System.nanoTime();
        try {
            while (this.running && !this.server.stopRequested()) {
                long now = System.nanoTime();
                if (now < deadline) {
                    LockSupport.parkNanos(Math.min(deadline - now, 1_000_000L));
                    continue;
                }
                int catchUps = 0;
                do {
                    this.server.tick(System.nanoTime());
                    deadline += TICK_NANOS;
                    catchUps++;
                } while (this.running && !this.server.stopRequested()
                        && System.nanoTime() >= deadline && catchUps < MAX_CATCH_UP_TICKS);
                if (catchUps == MAX_CATCH_UP_TICKS && System.nanoTime() >= deadline) {
                    long behind = System.nanoTime() - deadline;
                    if (behind > 5L * TICK_NANOS) {
                        System.err.printf("Server is %.1f ms behind; simulation TPS will drop%n", behind / 1_000_000.0);
                    }
                    deadline = System.nanoTime();
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            this.server.requestStop("Tick thread crashed");
        } finally {
            this.running = false;
            this.server.finishStop();
        }
    }

    @Override public void close() {
        this.running = false;
        if (Thread.currentThread() == this.thread) return;
        this.thread.interrupt();
        try { this.thread.join(10_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
