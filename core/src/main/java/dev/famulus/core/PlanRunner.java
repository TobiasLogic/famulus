package dev.famulus.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlanRunner {
    private final TaskPlan plan;
    private final int maxAttemptsPerTask;
    private final List<TaskResult> completed = new ArrayList<>();

    private int index;
    private int attempts;
    private PlanStep step = PlanStep.NOT_STARTED;
    private String reason = "Not started";

    public PlanRunner(TaskPlan plan, int maxAttemptsPerTask) {
        this.plan = Objects.requireNonNull(plan, "plan");
        if (maxAttemptsPerTask < 1) {
            throw new IllegalArgumentException("Each task needs at least one attempt");
        }
        this.maxAttemptsPerTask = maxAttemptsPerTask;
        if (!plan.isExecutable()) {
            throw new IllegalArgumentException("Plan contains tasks with no executor: "
                    + plan.unexecutable().stream().map(PlannedTask::describe).toList());
        }
    }

    public PlanStep start() {
        if (step != PlanStep.NOT_STARTED) {
            throw new IllegalStateException("This plan has already been started");
        }
        attempts = 1;
        reason = "Starting " + current().describe();
        return step = PlanStep.RUN_CURRENT;
    }

    public PlannedTask current() {
        if (index >= plan.tasks().size()) {
            return null;
        }
        return plan.tasks().get(index);
    }

    public PlanStep onTaskResult(TaskResult result) {
        Objects.requireNonNull(result, "result");
        requireActive();
        completed.add(result);
        return switch (result.status()) {
            case SUCCESS -> advance("Finished " + describeCurrent());

            case CANCELLED -> fail(PlanStep.PLAN_CANCELLED, "Cancelled during " + describeCurrent());
            default -> {
                if (attempts >= maxAttemptsPerTask) {
                    yield fail(PlanStep.PLAN_FAILED, describeCurrent() + " failed "
                            + attempts + " times: " + result.message());
                }
                reason = describeCurrent() + " returned " + result.status() + ": " + result.message();
                yield step = PlanStep.CONSULT_POLICY;
            }
        };
    }

    public Map<AgentAction, String> policyOptions() {
        if (step != PlanStep.CONSULT_POLICY) {
            throw new IllegalStateException("No decision is pending");
        }
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.RECOVER, "Retry the current task; the problem looks temporary");
        options.put(AgentAction.EXPLORE,
                "Nothing suitable is nearby; range outward to find some, then retry the task");
        options.put(AgentAction.COMPLETE_TASK,
                "Treat the current task as finished and move on to the next one");
        options.put(AgentAction.REQUEST_REPLAN,
                "The plan itself looks wrong; hand the situation to the planner");
        options.put(AgentAction.ABORT_TASK, "Give up on the whole plan");
        return options;
    }

    public PlanStep onPolicyDecision(AgentAction action) {
        Objects.requireNonNull(action, "action");
        if (step != PlanStep.CONSULT_POLICY) {
            throw new IllegalStateException("No decision is pending");
        }
        return switch (action) {
            case RECOVER -> {
                attempts++;
                reason = "Retrying " + describeCurrent() + ", attempt " + attempts
                        + " of " + maxAttemptsPerTask;
                yield step = PlanStep.RUN_CURRENT;
            }
            case EXPLORE -> {
                attempts++;
                reason = "Exploring for " + describeCurrent() + ", attempt " + attempts
                        + " of " + maxAttemptsPerTask;
                yield step = PlanStep.EXPLORE;
            }
            case COMPLETE_TASK -> advance("Policy accepted " + describeCurrent() + " as finished");
            case REQUEST_REPLAN -> fail(PlanStep.REPLAN_REQUIRED,
                    "Policy escalated during " + describeCurrent());
            default -> fail(PlanStep.PLAN_FAILED,
                    "Policy chose " + action + " during " + describeCurrent());
        };
    }

    public PlanStep onExploreComplete(String summary) {
        if (step != PlanStep.EXPLORE) {
            throw new IllegalStateException("Not exploring, the plan is " + step);
        }
        reason = summary + "; retrying " + describeCurrent();
        return step = PlanStep.RUN_CURRENT;
    }

    private PlanStep advance(String why) {
        index++;
        attempts = 1;
        if (index >= plan.tasks().size()) {
            reason = "Plan complete: " + plan.goal();
            return step = PlanStep.PLAN_COMPLETE;
        }
        reason = why + "; next is " + current().describe();
        return step = PlanStep.RUN_CURRENT;
    }

    private PlanStep fail(PlanStep terminal, String why) {
        reason = why;
        return step = terminal;
    }

    private void requireActive() {
        if (step.isTerminal() || step == PlanStep.NOT_STARTED) {
            throw new IllegalStateException("Plan is not running, it is " + step);
        }
    }

    private String describeCurrent() {
        PlannedTask task = current();
        return task == null ? "the plan" : task.describe();
    }

    public PlanStep step() {
        return step;
    }

    public String reason() {
        return reason;
    }

    public TaskPlan plan() {
        return plan;
    }

    public int taskIndex() {
        return Math.min(index, plan.tasks().size() - 1);
    }

    public int taskCount() {
        return plan.tasks().size();
    }

    public int completedCount() {
        return Math.min(index, plan.tasks().size());
    }

    public int attempts() {
        return attempts;
    }

    public List<TaskResult> results() {
        return List.copyOf(completed);
    }

    public String progress() {
        return "[" + completedCount() + "/" + taskCount() + "] " + step + " - " + reason;
    }
}
