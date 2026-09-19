package dev.famulus.core;

import java.util.Objects;

public final class DepositController {
    public static final long SETTLE_GRACE_MILLIS = 2_000;

    private final ContainerExecutor executor;
    private final GatherConfig config;
    private PlannedTask task;
    private TaskResult result = new TaskResult(TaskStatus.IDLE, "No transfer", 0, 0, 0);
    private String initialWorldKey;
    private boolean withdrawing;
    private int held;
    private int startingHeld;
    private int targetHeld;
    private int bestHeld;
    private int attempts;
    private long startedAt;
    private long attemptStartedAt;
    private long lastProgressAt;
    private long lastObservedAt;
    private long recoveryStartedAt;
    private long inactiveSince;
    private boolean observedInactive;
    private boolean ownsExecution;

    public DepositController(ContainerExecutor executor, GatherConfig config) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start(PlannedTask nextTask, DepositSnapshot snapshot, long nowMillis) {
        Objects.requireNonNull(nextTask, "task");
        Objects.requireNonNull(snapshot, "snapshot");
        if (isRunning() || ownsExecution) {
            throw new IllegalStateException("Stop the current transfer and resolve cancellation first");
        }
        int amount;
        if (nextTask instanceof PlannedTask.Deposit deposit) {
            withdrawing = false;
            amount = deposit.count();
        } else if (nextTask instanceof PlannedTask.Withdraw withdraw) {
            withdrawing = true;
            amount = withdraw.count();
        } else {
            throw new IllegalArgumentException("Not a transfer task: " + nextTask.describe());
        }
        task = nextTask;
        initialWorldKey = snapshot.worldKey();
        held = snapshot.heldCount();
        startingHeld = held;
        targetHeld = withdrawing ? held + amount : Math.max(0, held - amount);
        bestHeld = held;
        attempts = 0;
        startedAt = nowMillis;
        lastObservedAt = nowMillis;
        lastProgressAt = nowMillis;
        observedInactive = false;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (isSatisfied()) {
            finish(TaskStatus.SUCCESS, "Nothing to move");
        } else if (!withdrawing && held < amount) {
            finish(TaskStatus.RESOURCE_MISSING, "Holding " + held + ", asked to store " + amount);
        } else if (!snapshot.storageAvailable()) {
            finish(TaskStatus.RESOURCE_MISSING, withdrawing
                    ? "No container in reach to take from"
                    : "No container or shulker box available to store into");
        } else {
            beginAttempt(nowMillis);
        }
    }

    public void tick(DepositSnapshot snapshot, long nowMillis) {
        if (!isRunning()) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");
        held = snapshot.heldCount();
        if (nowMillis < lastObservedAt) {
            finish(TaskStatus.FAILED, "Monotonic clock moved backwards; supply one monotonic source");
            return;
        }
        lastObservedAt = nowMillis;
        if (!validateWorld(snapshot)) {
            return;
        }
        if (elapsed(nowMillis, startedAt) >= config.taskTimeoutMillis()) {
            finish(TaskStatus.TIMEOUT, "Transfer exceeded its time budget at " + moved() + " moved");
            return;
        }
        final boolean stillWorking;
        try {
            stillWorking = executor.isActive();
        } catch (RuntimeException failure) {
            recover("Could not inspect the container handler: " + describe(failure), nowMillis);
            return;
        }
        if (isSatisfied() && !stillWorking) {
            finish(TaskStatus.SUCCESS, moved() + " moved");
            return;
        }
        if (madeProgress()) {
            bestHeld = held;
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
            recover("No items moved within the stall timeout", nowMillis);
            return;
        }
        if (stillWorking) {
            observedInactive = false;
            publish(TaskStatus.RUNNING, executor.lastStep());
            return;
        }
        if (!observedInactive) {
            observedInactive = true;
            inactiveSince = nowMillis;
        }
        if (elapsed(nowMillis, inactiveSince) >= Math.min(SETTLE_GRACE_MILLIS, config.stallTimeoutMillis())) {
            recover("Container handler stopped after moving " + moved(), nowMillis);
        } else {
            publish(TaskStatus.RUNNING, "Waiting for the container to settle");
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

    private boolean isSatisfied() {
        return withdrawing ? held >= targetHeld : held <= targetHeld;
    }

    private boolean madeProgress() {
        return withdrawing ? held > bestHeld : held < bestHeld;
    }

    private int moved() {
        return Math.abs(held - startingHeld);
    }

    private boolean validateWorld(DepositSnapshot snapshot) {
        if (!snapshot.connected()) {
            finish(TaskStatus.BLOCKED, "Disconnected from the world");
            return false;
        }
        if (!snapshot.alive()) {
            finish(TaskStatus.FAILED, "Player is dead; transfer stopped");
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
        publish(TaskStatus.RUNNING, "Starting transfer attempt " + attempts + " of " + config.maxAttempts());
        ownsExecution = true;
        try {
            executor.start(task);
        } catch (RuntimeException failure) {
            recover("Container handler could not start: " + describe(failure), nowMillis);
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
        result = new TaskResult(status, message, moved(), Math.abs(targetHeld - startingHeld), attempts);
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
