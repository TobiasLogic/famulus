package dev.famulus.core;

import java.util.Objects;

public final class BuildController {
    public static final long SETTLE_GRACE_MILLIS = 2_000;

    private final BuildExecutor executor;
    private final GatherConfig config;
    private PlannedTask task;
    private TaskResult result = new TaskResult(TaskStatus.IDLE, "No build", 0, 0, 0);
    private String initialWorldKey;
    private int remaining;
    private int total;
    private int bestRemaining;
    private int attempts;
    private long startedAt;
    private long attemptStartedAt;
    private long lastProgressAt;
    private long lastObservedAt;
    private long recoveryStartedAt;
    private long inactiveSince;
    private boolean observedInactive;
    private boolean ownsExecution;

    public BuildController(BuildExecutor executor, GatherConfig config) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start(PlannedTask nextTask, BuildSnapshot snapshot, long nowMillis) {
        Objects.requireNonNull(nextTask, "task");
        Objects.requireNonNull(snapshot, "snapshot");
        if (isRunning() || ownsExecution) {
            throw new IllegalStateException("Stop the current build and resolve cancellation first");
        }
        task = nextTask;
        initialWorldKey = snapshot.worldKey();
        remaining = snapshot.remainingBlocks();
        total = snapshot.totalBlocks();
        bestRemaining = remaining;
        attempts = 0;
        startedAt = nowMillis;
        lastObservedAt = nowMillis;
        lastProgressAt = nowMillis;
        observedInactive = false;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (snapshot.totalBlocks() == 0) {
            finish(TaskStatus.INVALID_TARGET, "Nothing to do for " + task.describe());
        } else if (snapshot.isComplete()) {
            finish(TaskStatus.SUCCESS, "Already in the wanted state");
        } else if (!snapshot.hasMaterials()) {
            finish(TaskStatus.RESOURCE_MISSING, "Missing materials for " + remaining + " blocks");
        } else {
            beginAttempt(nowMillis);
        }
    }

    public void tick(BuildSnapshot snapshot, long nowMillis) {
        if (!isRunning()) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");
        remaining = snapshot.remainingBlocks();
        total = snapshot.totalBlocks();
        if (nowMillis < lastObservedAt) {
            finish(TaskStatus.FAILED, "Monotonic clock moved backwards; supply one monotonic source");
            return;
        }
        lastObservedAt = nowMillis;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (elapsed(nowMillis, startedAt) >= config.taskTimeoutMillis()) {
            finish(TaskStatus.TIMEOUT, "Build exceeded its total time budget with "
                    + remaining + " blocks left");
            return;
        }
        if (snapshot.isComplete()) {
            finish(TaskStatus.SUCCESS, "World matches what was asked for");
            return;
        }
        if (remaining < bestRemaining) {
            bestRemaining = remaining;
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
            recover("No blocks placed within the stall timeout", nowMillis);
            return;
        }
        final boolean active;
        try {
            active = executor.isActive();
        } catch (RuntimeException failure) {
            recover("Could not inspect the builder: " + describe(failure), nowMillis);
            return;
        }
        if (active) {
            observedInactive = false;
            publish(TaskStatus.RUNNING, "Placing blocks");
            return;
        }
        if (!observedInactive) {
            observedInactive = true;
            inactiveSince = nowMillis;
        }
        if (elapsed(nowMillis, inactiveSince) >= Math.min(SETTLE_GRACE_MILLIS, config.stallTimeoutMillis())) {
            if (!snapshot.hasMaterials()) {
                recover("Ran out of materials with " + remaining + " blocks left", nowMillis);
            } else {
                recover("Builder stopped with " + remaining + " blocks left", nowMillis);
            }
        } else {
            publish(TaskStatus.RUNNING, "Waiting for the world to settle");
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

    public TaskResult result() {
        return result;
    }

    public boolean isRunning() {
        return result.status() == TaskStatus.RUNNING || result.status() == TaskStatus.RECOVERING;
    }

    public PlannedTask task() {
        return task;
    }

    private boolean validateWorld(BuildSnapshot snapshot) {
        if (!snapshot.connected()) {
            finish(TaskStatus.BLOCKED, "Disconnected from the world");
            return false;
        }
        if (!snapshot.alive()) {
            finish(TaskStatus.FAILED, "Player is dead; building stopped");
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
        publish(TaskStatus.RUNNING, "Starting build attempt " + attempts + " of " + config.maxAttempts());
        ownsExecution = true;
        try {
            executor.start(task);
        } catch (RuntimeException failure) {
            recover("Builder could not start: " + describe(failure), nowMillis);
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
        result = new TaskResult(status, message, total - remaining, total, attempts);
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
