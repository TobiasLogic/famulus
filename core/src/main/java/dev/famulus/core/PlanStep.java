package dev.famulus.core;

public enum PlanStep {
    NOT_STARTED,

    RUN_CURRENT,

    CONSULT_POLICY,

    EXPLORE,
    PLAN_COMPLETE,
    PLAN_FAILED,
    PLAN_CANCELLED,

    REPLAN_REQUIRED;

    public boolean isTerminal() {
        return this == PLAN_COMPLETE || this == PLAN_FAILED
                || this == PLAN_CANCELLED || this == REPLAN_REQUIRED;
    }

    public boolean isSuccess() {
        return this == PLAN_COMPLETE;
    }
}
