package dev.famulus.core;

public enum TaskStatus {
    IDLE,
    RUNNING,
    RECOVERING,
    SUCCESS,
    FAILED,
    BLOCKED,
    INVALID_TARGET,
    RESOURCE_MISSING,
    PATH_NOT_FOUND,
    TIMEOUT,
    WORLD_CHANGED,
    REPLAN_REQUIRED,
    CANCELLED
}
