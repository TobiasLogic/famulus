package dev.famulus.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TaskPlan(String goal, List<PlannedTask> tasks) {
    public TaskPlan {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(tasks, "tasks");
        if (goal.isBlank()) {
            throw new IllegalArgumentException("A plan needs a goal");
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("A plan needs at least one task");
        }
        Set<String> seen = new HashSet<>();
        for (PlannedTask task : tasks) {
            Objects.requireNonNull(task, "task");
            if (!seen.add(task.id())) {
                throw new IllegalArgumentException("Duplicate task id: " + task.id());
            }
        }
        tasks = List.copyOf(tasks);
    }

    public List<PlannedTask> unexecutable() {
        return tasks.stream().filter(task -> !task.action().isExecutable()).toList();
    }

    public boolean isExecutable() {
        return unexecutable().isEmpty();
    }
}
