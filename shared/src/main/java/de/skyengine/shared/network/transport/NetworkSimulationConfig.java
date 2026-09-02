package de.skyengine.shared.network.transport;

public record NetworkSimulationConfig(long latencyMillis, long jitterMillis, long bytesPerSecond,
                                      double packetLoss, double reordering, double duplication,
                                      long randomSeed) {
    public NetworkSimulationConfig {
        if (latencyMillis < 0 || latencyMillis > 60_000 || jitterMillis < 0 || jitterMillis > 60_000
                || bytesPerSecond < 0 || packetLoss < 0 || packetLoss > 1 || reordering < 0
                || reordering > 1 || duplication < 0 || duplication > 1) {
            throw new IllegalArgumentException("Invalid network simulation settings");
        }
    }
    public static NetworkSimulationConfig disabled() { return new NetworkSimulationConfig(0, 0, 0, 0, 0, 0, 1); }
}
