package dev.famulus.core;

public interface BuildExecutor {
    void start(PlannedTask task);

    void cancel();

    boolean isActive();
}
