package dev.famulus.core;

import java.util.Objects;

public final class TravelController {
    public static final double ARRIVAL_TOLERANCE = 3.0;
    public static final long SETTLE_GRACE_MILLIS = 1_500;

    private final TravelExecutor executor;
    private final GatherConfig config;
    private PlannedTask.Travel task;
    private TaskResult result = new TaskResult(TaskStatus.IDLE, "No journey", 0, 0, 0);
    private String initialWorldKey;
    private double distance;
    private double closest;
    private int attempts;
    private long startedAt;
    private long attemptStartedAt;
    private long lastProgressAt;
    private long lastObservedAt;
    private long recoveryStartedAt;
    private long inactiveSince;
    private boolean observedInactive;
    private boolean ownsExecution;

    public TravelController(TravelExecutor executor, GatherConfig config) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start(PlannedTask.Travel nextTask, TravelSnapshot snapshot, long nowMillis) {
        Objects.requireNonNull(nextTask, "task");
        Objects.requireNonNull(snapshot, "snapshot");
        if (isRunning() || ownsExecution) {
            throw new IllegalStateException("Stop the current journey and resolve cancellation first");
        }
        task = nextTask;
        initialWorldKey = snapshot.worldKey();
        distance = snapshot.distance();
        closest = distance;
        attempts = 0;
        startedAt = nowMillis;
        lastObservedAt = nowMillis;
        lastProgressAt = nowMillis;
        observedInactive = false;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (snapshot.hasArrived(ARRIVAL_TOLERANCE)) {
            finish(TaskStatus.SUCCESS, "Already at the destination");
        } else {
            beginAttempt(nowMillis);
        }
    }

    public void tick(TravelSnapshot snapshot, long nowMillis) {
        if (!isRunning()) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");
        distance = snapshot.distance();
        if (nowMillis < lastObservedAt) {
            finish(TaskStatus.FAILED, "Monotonic clock moved backwards; supply one monotonic source");
            return;
        }
        lastObservedAt = nowMillis;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (elapsed(nowMillis, startedAt) >= config.taskTimeoutMillis()) {
            finish(TaskStatus.TIMEOUT, "Journey exceeded its time budget, still "
                    + Math.round(distance) + " blocks away");
            return;
        }
        if (snapshot.hasArrived(ARRIVAL_TOLERANCE)) {
            finish(TaskStatus.SUCCESS, "Arrived at the destination");
            return;
        }
        if (distance < closest - 1.0) {
            closest = distance;
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
            recover("No progress toward the destination within the stall timeout", nowMillis);
            return;
        }
        final boolean active;
        try {
            active = executor.isActive();
        } catch (RuntimeException failure) {
            recover("Could not inspect the pathfinder: " + describe(failure), nowMillis);
            return;
        }
        if (active) {
            observedInactive = false;
            publish(TaskStatus.RUNNING, Math.round(distance) + " blocks to go");
            return;
        }
        if (!observedInactive) {
            observedInactive = true;
            inactiveSince = nowMillis;
        }
        if (elapsed(nowMillis, inactiveSince) >= Math.min(SETTLE_GRACE_MILLIS, config.stallTimeoutMillis())) {
            recover("Pathfinder stopped " + Math.round(distance) + " blocks short", nowMillis);
        } else {
            publish(TaskStatus.RUNNING, "Settling");
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

    public PlannedTask.Travel task() {
        return task;
    }

    private boolean validateWorld(TravelSnapshot snapshot) {
        if (!snapshot.connected()) {
            finish(TaskStatus.BLOCKED, "Disconnected from the world");
            return false;
        }
        if (!snapshot.alive()) {
            finish(TaskStatus.FAILED, "Player is dead; travel stopped");
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
        publish(TaskStatus.RUNNING, "Travelling, attempt " + attempts + " of " + config.maxAttempts());
        ownsExecution = true;
        try {
            executor.start(task);
        } catch (RuntimeException failure) {
            recover("Pathfinder could not start: " + describe(failure), nowMillis);
        }
    }

    private void recover(String reason, long nowMillis) {
        String cancellationError = cancelOwnedExecution();
        if (cancellationError != null) {
            publish(TaskStatus.FAILED, reason + "; cancellation failed: " + cancellationError);
        } else if (attempts >= config.maxAttempts()) {
            publish(TaskStatus.PATH_NOT_FOUND, reason + "; exhausted " + attempts + " attempts");
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
        result = new TaskResult(status, message, (int) Math.round(distance), 0, attempts);
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
