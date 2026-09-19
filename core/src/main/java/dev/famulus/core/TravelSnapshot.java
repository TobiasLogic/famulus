package dev.famulus.core;

public record TravelSnapshot(boolean connected, boolean alive, String worldKey, double distance) {
    public TravelSnapshot {
        if (distance < 0) {
            throw new IllegalArgumentException("Distance must not be negative");
        }
    }

    public boolean hasArrived(double tolerance) {
        return distance <= tolerance;
    }
}
