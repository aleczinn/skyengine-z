package de.skyengine.server;

import de.skyengine.server.world.ServerWorldRuntime;
import de.skyengine.shared.network.transport.TransportConnection;

import java.util.Objects;

/** Owns the same ServerApplication used by dedicated mode and exposes only the local client endpoint. */
public final class IntegratedServerHost implements AutoCloseable {
    private final ServerApplication server;
    private final TransportConnection clientConnection;

    public IntegratedServerHost(ServerConfig config, ServerWorldRuntime world) {
        this.server = new ServerApplication(config, Objects.requireNonNull(world));
        this.clientConnection = this.server.startIntegrated().client();
    }

    public ServerApplication server() { return this.server; }
    public TransportConnection clientConnection() { return this.clientConnection; }

    @Override public void close() {
        this.clientConnection.close();
        this.server.close();
    }
}
