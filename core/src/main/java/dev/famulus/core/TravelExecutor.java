package dev.famulus.core;

public interface TravelExecutor {
    void start(PlannedTask.Travel task);

    void cancel();

    boolean isActive();
}
