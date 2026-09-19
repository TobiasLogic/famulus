package dev.famulus.core;

public interface BuildExecutor {
    void start(PlannedTask.Build task);

    void cancel();

    boolean isActive();
}
