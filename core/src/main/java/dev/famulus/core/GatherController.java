package dev.famulus.core;

import java.util.Objects;

public final class GatherController {
    public static final long PICKUP_GRACE_MILLIS = 1_000;

    private final GatherExecutor executor;
    private final GatherConfig config;
    private GatherTask task;
    private TaskResult result = new TaskResult(TaskStatus.IDLE, "No task", 0, 0, 0);
    private String initialWorldKey;
    private int currentCount;
    private int highWaterCount;
    private int attempts;
    private long startedAt;
    private long attemptStartedAt;
    private long lastProgressAt;
    private long lastObservedAt;
    private long recoveryStartedAt;
    private long inactiveSince;
    private boolean observedInactive;
    private boolean ownsExecution;

    public GatherController(GatherExecutor executor, GatherConfig config) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start(GatherTask nextTask, WorldSnapshot snapshot, long nowMillis) {
        Objects.requireNonNull(nextTask, "task");
        Objects.requireNonNull(snapshot, "snapshot");
        if (isRunning() || ownsExecution) {
            throw new IllegalStateException("Stop the current task and resolve cancellation before starting another");
        }
        task = nextTask;
        initialWorldKey = snapshot.worldKey();
        currentCount = snapshot.itemCount();
        highWaterCount = currentCount;
        attempts = 0;
        startedAt = nowMillis;
        lastObservedAt = nowMillis;
        lastProgressAt = nowMillis;
        observedInactive = false;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (currentCount >= task.targetCount()) {
            finish(TaskStatus.SUCCESS, "Inventory target already satisfied");
        } else if (!snapshot.inventoryHasSpace()) {
            finish(TaskStatus.BLOCKED, "Inventory has no space for the requested item");
        } else {
            beginAttempt(nowMillis);
        }
    }

    public void tick(WorldSnapshot snapshot, long nowMillis) {
        if (!isRunning()) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");
        currentCount = snapshot.itemCount();
        if (nowMillis < lastObservedAt) {
            finish(TaskStatus.FAILED, "Monotonic clock moved backwards; supply one monotonic time source");
            return;
        }
        lastObservedAt = nowMillis;
        if (!validateWorld(snapshot)) {
            return;
        }

        if (elapsed(nowMillis, startedAt) >= config.taskTimeoutMillis()) {
            finish(TaskStatus.TIMEOUT, "Task exceeded its total time budget");
            return;
        }
        if (currentCount >= task.targetCount()) {
            finish(TaskStatus.SUCCESS, "Inventory target reached");
            return;
        }
        if (!snapshot.inventoryHasSpace()) {
            finish(TaskStatus.BLOCKED, "Inventory has no space for the requested item");
            return;
        }
        if (currentCount > highWaterCount) {
            highWaterCount = currentCount;
            lastProgressAt = nowMillis;
        }
        if (result.status() == TaskStatus.RECOVERING) {
            if (elapsed(nowMillis, recoveryStartedAt) >= config.retryDelayMillis()) {
                beginAttempt(nowMillis);
            } else {
                publish(TaskStatus.RECOVERING, result.message());
            }
            return;
        }

        if (elapsed(nowMillis, Math.max(attemptStartedAt, lastProgressAt))
                >= config.stallTimeoutMillis()) {
            recover("No inventory progress within the stall timeout", nowMillis);
            return;
        }
        final boolean active;
        try {
            active = executor.isActive();
        } catch (RuntimeException failure) {
            recover("Could not inspect executor: " + describe(failure), nowMillis);
            return;
        }
        if (active) {
            observedInactive = false;
            publish(TaskStatus.RUNNING, "Gathering inventory items");
            return;
        }
        if (!observedInactive) {
            observedInactive = true;
            inactiveSince = nowMillis;
        }
        if (elapsed(nowMillis, inactiveSince) >= Math.min(PICKUP_GRACE_MILLIS, config.stallTimeoutMillis())) {
            recover("Executor became inactive before inventory target was reached", nowMillis);
        } else {
            publish(TaskStatus.RUNNING, "Waiting briefly for mined item pickup");
        }
    }

    public void stop(String reason) {
        if (isRunning()) {
            finish(TaskStatus.CANCELLED, reason == null || reason.isBlank() ? "Stopped by user" : reason);
        } else if (ownsExecution) {
            String cancellationError = cancelOwnedExecution();
            if (cancellationError != null) {
                publish(TaskStatus.FAILED, "Cancellation retry failed: " + cancellationError);
            }
        }
    }

    public void fail(String reason) {
        if (isRunning()) {
            finish(TaskStatus.FAILED, reason == null || reason.isBlank() ? "Gather task failed" : reason);
        }
    }

    public TaskResult result() {
        return result;
    }

    public boolean isRunning() {
        return result.status() == TaskStatus.RUNNING || result.status() == TaskStatus.RECOVERING;
    }

    public GatherTask task() {
        return task;
    }

    private boolean validateWorld(WorldSnapshot snapshot) {
        if (!snapshot.connected()) {
            finish(TaskStatus.BLOCKED, "Disconnected from the world");
            return false;
        }
        if (!snapshot.alive()) {
            finish(TaskStatus.FAILED, "Player is dead; gathering stopped");
            return false;
        }
        if (snapshot.worldKey() == null || snapshot.worldKey().isBlank()) {
            finish(TaskStatus.BLOCKED, "World session and dimension identity are unavailable");
            return false;
        }
        if (!snapshot.worldKey().equals(initialWorldKey)) {
            finish(TaskStatus.WORLD_CHANGED, "World session or dimension changed; submit a new task");
            return false;
        }
        return true;
    }

    private void beginAttempt(long nowMillis) {
        attempts++;
        attemptStartedAt = nowMillis;
        observedInactive = false;
        publish(TaskStatus.RUNNING, "Starting gather attempt " + attempts + " of " + config.maxAttempts());

        ownsExecution = true;
        try {
            executor.start(task);
        } catch (RuntimeException failure) {
            recover("Executor could not start: " + describe(failure), nowMillis);
        }
    }

    private void recover(String reason, long nowMillis) {
        String cancellationError = cancelOwnedExecution();
        if (cancellationError != null) {
            publish(TaskStatus.FAILED, reason + "; cancellation failed: " + cancellationError);
        } else if (attempts >= config.maxAttempts()) {
            publish(TaskStatus.REPLAN_REQUIRED, reason + "; exhausted " + attempts + " attempts");
        } else {
            recoveryStartedAt = nowMillis;
            publish(TaskStatus.RECOVERING, reason + "; retry scheduled");
        }
    }

    private void finish(TaskStatus status, String message) {
        String cancellationError = cancelOwnedExecution();
        if (cancellationError == null) {
            publish(status, message);
        } else {
            publish(TaskStatus.FAILED, message + "; cancellation failed: " + cancellationError);
        }
    }

    private String cancelOwnedExecution() {
        if (!ownsExecution) {
            return null;
        }
        try {
            executor.cancel();
            ownsExecution = false;
            return null;
        } catch (RuntimeException failure) {
            return describe(failure);
        }
    }

    private void publish(TaskStatus status, String message) {
        result = new TaskResult(status, message, currentCount, task == null ? 0 : task.targetCount(), attempts);
    }

    private static long elapsed(long nowMillis, long thenMillis) {
        try {
            return Math.subtractExact(nowMillis, thenMillis);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String describe(RuntimeException failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
}
