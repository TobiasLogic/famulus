package dev.famulus.core;

public interface GatherExecutor {
    void start(GatherTask task);

    void cancel();

    boolean isActive();
}
