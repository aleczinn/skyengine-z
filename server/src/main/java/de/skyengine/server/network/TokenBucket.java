package de.skyengine.server.network;

/** Tick-thread token bucket. */
public final class TokenBucket {
    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, double capacity, long nowNanos) {
        if (ratePerSecond <= 0 || capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.tokensPerNano = ratePerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    public boolean tryConsume(double amount, long nowNanos) {
        if (amount <= 0) throw new IllegalArgumentException();
        long elapsed = Math.max(0, nowNanos - this.lastRefillNanos);
        this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.tokensPerNano);
        this.lastRefillNanos = nowNanos;
        if (this.tokens < amount) return false;
        this.tokens -= amount;
        return true;
    }
}
