package dev.famulus.core;

import java.util.Objects;
import java.util.Set;

public record PlanRequest(String goal, String context, Set<String> gatherableItems) {
    public PlanRequest {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(gatherableItems, "gatherableItems");
        if (goal.isBlank()) {
            throw new IllegalArgumentException("A goal is required");
        }
        if (goal.length() > 2000) {
            throw new IllegalArgumentException("Goal is too long to be a goal");
        }
        gatherableItems = Set.copyOf(gatherableItems);
    }
}
