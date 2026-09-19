package dev.famulus.core;

public interface PlannerClient {
    TaskPlan plan(PlanRequest request) throws PlannerException;
}
