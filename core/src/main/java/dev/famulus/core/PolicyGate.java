package dev.famulus.core;

import java.util.Objects;

public final class PolicyGate {
    private final PolicyClient client;
    private final PolicyGateConfig config;
    private final AgentAction fallback;
    private int consecutiveEscalations;
    private String lastReason = "No decision yet";

    public PolicyGate(PolicyClient client, PolicyGateConfig config, AgentAction fallback) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (!fallback.isExecutable()) {
            throw new IllegalArgumentException("Fallback action must be executable: " + fallback);
        }
    }

    public AgentAction next(PolicyRequest request) {
        if (consecutiveEscalations >= config.maxConsecutiveEscalations()) {
            lastReason = "Escalated " + consecutiveEscalations + " times without progress; aborting";
            return AgentAction.ABORT_TASK;
        }
        PolicyDecision decision;
        try {
            decision = client.decide(request);
        } catch (PolicyException failure) {
            consecutiveEscalations++;
            lastReason = "Policy unavailable, using " + fallback + ": " + failure.getMessage();
            return fallback;
        }
        if (!request.options().containsKey(decision.action())) {
            consecutiveEscalations++;
            lastReason = "Policy chose " + decision.action() + ", which was not offered";
            return AgentAction.REQUEST_REPLAN;
        }
        if (decision.replanUrgency() >= config.replanUrgencyCeiling()) {
            consecutiveEscalations++;
            lastReason = "Policy reported replan urgency " + round(decision.replanUrgency());
            return AgentAction.REQUEST_REPLAN;
        }
        if (decision.confidence() < config.confidenceFloor()) {
            consecutiveEscalations++;
            lastReason = "Confidence " + round(decision.confidence()) + " below floor "
                    + round(config.confidenceFloor()) + " for " + decision.action();
            return AgentAction.REQUEST_REPLAN;
        }
        if (!decision.action().isExecutable()) {
            consecutiveEscalations++;
            lastReason = "No executor exists for " + decision.action();
            return AgentAction.REQUEST_REPLAN;
        }
        consecutiveEscalations = 0;
        lastReason = "Accepted " + decision.action() + " at confidence " + round(decision.confidence());
        return decision.action();
    }

    public String lastReason() {
        return lastReason;
    }

    public int consecutiveEscalations() {
        return consecutiveEscalations;
    }

    public void reset() {
        consecutiveEscalations = 0;
        lastReason = "Reset after progress";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
