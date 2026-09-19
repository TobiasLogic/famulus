package dev.famulus.core;

public record GatherConfig(long stallTimeoutMillis, long taskTimeoutMillis,
                           long retryDelayMillis, int maxAttempts) {
    public GatherConfig {
        if (stallTimeoutMillis <= 0 || taskTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Stall and task timeouts must be positive");
        }
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("Retry delay must not be negative");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Maximum attempts must be at least one");
        }
    }

    public static GatherConfig defaults() {
        return new GatherConfig(60_000, 600_000, 2_000, 3);
    }
}
