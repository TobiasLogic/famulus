package dev.famulus.core;

public interface ContainerExecutor {
    void start(PlannedTask task);

    void cancel();

    boolean isActive();

    String lastStep();
}
