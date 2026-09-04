package de.skyengine.client.network;

import java.net.InetSocketAddress;

/** Validated multiplayer endpoint with Minecraft-style optional port syntax. */
public record ServerAddress(String host, int port) {
    public static final int DEFAULT_PORT = 25565;

    public ServerAddress {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Server address is empty");
        host = host.trim();
        if (host.length() > 253) throw new IllegalArgumentException("Server host is too long");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Server port must be in [1, 65535]");
    }

    public static ServerAddress parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Server address is empty");
        String text = value.trim();
        String host;
        int port = DEFAULT_PORT;

        if (text.startsWith("[")) {
            int closing = text.indexOf(']');
            if (closing < 0) throw new IllegalArgumentException("IPv6 address is missing ']'");
            host = text.substring(1, closing);
            String suffix = text.substring(closing + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) throw new IllegalArgumentException("Invalid server address");
                port = parsePort(suffix.substring(1));
            }
        } else {
            int firstColon = text.indexOf(':');
            int lastColon = text.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                host = text.substring(0, firstColon);
                port = parsePort(text.substring(firstColon + 1));
            } else {
                // An unbracketed IPv6 literal is accepted only with the default port.
                host = text;
            }
        }
        return new ServerAddress(host, port);
    }

    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(this.host, this.port);
    }

    public String display() {
        String renderedHost = this.host.indexOf(':') >= 0 ? '[' + this.host + ']' : this.host;
        return this.port == DEFAULT_PORT ? renderedHost : renderedHost + ':' + this.port;
    }

    private static int parsePort(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("Server port is empty");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid server port", error);
        }
    }
}
