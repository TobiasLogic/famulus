package dev.famulus.core;

public record WorldSnapshot(boolean connected, boolean alive, String worldKey,
                            int itemCount, boolean inventoryHasSpace) {
    public WorldSnapshot {
        if (itemCount < 0) {
            throw new IllegalArgumentException("Inventory count must not be negative");
        }
    }
}
