package dev.famulus.core;

public class PlannerException extends Exception {
    public PlannerException(String message) {
        super(message);
    }

    public PlannerException(String message, Throwable cause) {
        super(message, cause);
    }
}
