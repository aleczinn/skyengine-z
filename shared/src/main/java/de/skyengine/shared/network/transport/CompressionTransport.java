package de.skyengine.shared.network.transport;

/** Optional framed-transport capability negotiated before LOGIN. */
public interface CompressionTransport {
    void enableCompression(String algorithm, int threshold, int maximumDecompressedBytes, int level);
}
