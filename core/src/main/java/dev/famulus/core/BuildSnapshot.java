package dev.famulus.core;

public record BuildSnapshot(boolean connected, boolean alive, String worldKey,
                            int remainingBlocks, int totalBlocks, boolean hasMaterials) {
    public BuildSnapshot {
        if (remainingBlocks < 0 || totalBlocks < 0) {
            throw new IllegalArgumentException("Block counts must not be negative");
        }
        if (remainingBlocks > totalBlocks) {
            throw new IllegalArgumentException("Remaining cannot exceed the total");
        }
    }

    public int placedBlocks() {
        return totalBlocks - remainingBlocks;
    }

    public boolean isComplete() {
        return remainingBlocks == 0;
    }
}
