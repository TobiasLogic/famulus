package dev.famulus.core;

import java.util.Objects;

public record TaskResult(TaskStatus status, String message, int currentCount,
                         int targetCount, int attempts) {
    public TaskResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }
}
