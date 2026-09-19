package dev.famulus.core;

public record DepositSnapshot(boolean connected, boolean alive, String worldKey,
                              int heldCount, boolean storageAvailable) {
    public DepositSnapshot {
        if (heldCount < 0) {
            throw new IllegalArgumentException("Held count must not be negative");
        }
    }
}
