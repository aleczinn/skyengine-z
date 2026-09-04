package de.skyengine.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Blocking stdin reader; commands are always executed on the authoritative tick thread. */
public final class ServerConsole implements AutoCloseable {
    private final ServerApplication server;
    private final Thread thread;
    private volatile boolean running = true;

    public ServerConsole(ServerApplication server) {
        this.server = server;
        this.thread = new Thread(this::readLoop, "Server Console");
        this.thread.setDaemon(true);
    }

    public void start() { this.thread.start(); }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (this.running) {
                String line = reader.readLine();
                if (line == null) return;
                String command = line.trim();
                if (!command.isEmpty()) this.server.executeOnTick(() -> execute(command));
            }
        } catch (IOException e) {
            if (this.running) System.err.println("Server console stopped: " + e.getMessage());
        }
    }

    private void execute(String command) {
        ServerCommandDispatcher.Result result = this.server.commands().execute(command, null);
        for (String message : result.messages()) System.out.println(message);
    }

    @Override public void close() { this.running = false; this.thread.interrupt(); }
}
