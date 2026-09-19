package dev.famulus.core;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolicyGateTest {
    private static final PolicyGateConfig CONFIG = new PolicyGateConfig(0.55, 0.70, 3);

    private static PolicyRequest request() {
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.GATHER, "Keep gathering");
        options.put(AgentAction.COMPLETE_TASK, "Goal is met");
        options.put(AgentAction.RECOVER, "Try local recovery");
        return new PolicyRequest("31 of 32 oak logs held", options);
    }

    private static PolicyDecision decision(AgentAction action, double confidence, double replan) {
        Map<AgentAction, Double> probabilities = new EnumMap<>(AgentAction.class);
        probabilities.put(action, confidence);
        return new PolicyDecision(action, confidence, probabilities, replan);
    }

    private static PolicyGate gate(PolicyClient client) {
        return new PolicyGate(client, CONFIG, AgentAction.WAIT);
    }

    @Test
    void confidentExecutableChoiceIsAccepted() {
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, 0.86, 0.05));
        assertEquals(AgentAction.GATHER, gate.next(request()));
        assertEquals(0, gate.consecutiveEscalations());
        assertTrue(gate.lastReason().contains("Accepted GATHER"));
    }

    @Test
    void confidenceBelowFloorEscalatesInsteadOfActing() {
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, 0.54, 0.0));
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(request()));
        assertEquals(1, gate.consecutiveEscalations());
        assertTrue(gate.lastReason().contains("below floor"));
    }

    @Test
    void confidenceExactlyAtFloorIsAccepted() {
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, 0.55, 0.0));
        assertEquals(AgentAction.GATHER, gate.next(request()));
    }

    @Test
    void highReplanUrgencyEscalatesEvenWhenConfident() {
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, 0.99, 0.70));
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(request()));
        assertTrue(gate.lastReason().contains("replan urgency"));
    }

    @Test
    void anActionThatWasNotOfferedIsNeverDispatched() {
        PolicyGate gate = gate(r -> decision(AgentAction.ABORT_TASK, 0.99, 0.0));
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(request()));
        assertTrue(gate.lastReason().contains("not offered"));
    }

    @Test
    void anActionWithoutAnExecutorIsNeverDispatched() {
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.GATHER, "Keep gathering");
        options.put(AgentAction.CRAFT, "Craft the item");
        PolicyRequest offered = new PolicyRequest("state", options);
        PolicyGate gate = gate(r -> decision(AgentAction.CRAFT, 0.99, 0.0));
        assertFalse(AgentAction.CRAFT.isExecutable());
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(offered));
        assertTrue(gate.lastReason().contains("No executor"));
    }

    @Test
    void unreachablePolicyFallsBackDeterministicallyRatherThanFailing() {
        PolicyGate gate = gate(r -> { throw new PolicyException("connection refused"); });
        assertEquals(AgentAction.WAIT, gate.next(request()));
        assertTrue(gate.lastReason().contains("connection refused"));
        assertEquals(1, gate.consecutiveEscalations());
    }

    @Test
    void repeatedEscalationAbortsInsteadOfLoopingForever() {
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, 0.10, 0.0));
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(request()));
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(request()));
        assertEquals(AgentAction.REQUEST_REPLAN, gate.next(request()));
        assertEquals(AgentAction.ABORT_TASK, gate.next(request()));
        assertEquals(AgentAction.ABORT_TASK, gate.next(request()));
        assertTrue(gate.lastReason().contains("aborting"));
    }

    @Test
    void repeatedFallbackAlsoCountsTowardTheAbortLimit() {
        PolicyGate gate = gate(r -> { throw new PolicyException("down"); });
        assertEquals(AgentAction.WAIT, gate.next(request()));
        assertEquals(AgentAction.WAIT, gate.next(request()));
        assertEquals(AgentAction.WAIT, gate.next(request()));
        assertEquals(AgentAction.ABORT_TASK, gate.next(request()));
    }

    @Test
    void progressClearsAccumulatedConfusion() {
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, 0.10, 0.0));
        gate.next(request());
        gate.next(request());
        assertEquals(2, gate.consecutiveEscalations());
        gate.reset();
        assertEquals(0, gate.consecutiveEscalations());
    }

    @Test
    void oneGoodDecisionClearsAccumulatedConfusion() {
        double[] confidence = {0.10};
        PolicyGate gate = gate(r -> decision(AgentAction.GATHER, confidence[0], 0.0));
        gate.next(request());
        gate.next(request());
        confidence[0] = 0.90;
        assertEquals(AgentAction.GATHER, gate.next(request()));
        assertEquals(0, gate.consecutiveEscalations());
    }

    @Test
    void fallbackMustBeExecutableBecauseItIsUsedWhenNothingElseWorks() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyGate(r -> decision(AgentAction.GATHER, 1, 0), CONFIG, AgentAction.CRAFT));
    }

    @Test
    void requestAndDecisionValidateTheirInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyRequest("  ", Map.of(AgentAction.GATHER, "a", AgentAction.WAIT, "b")));
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyRequest("state", Map.of(AgentAction.GATHER, "only one")));
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyRequest("state", Map.of(AgentAction.GATHER, "a", AgentAction.WAIT, " ")));
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyDecision(AgentAction.GATHER, 1.4, Map.of(), 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyDecision(AgentAction.GATHER, 0.5, Map.of(), -0.1));
        assertThrows(IllegalArgumentException.class, () -> new PolicyGateConfig(0.5, 0.5, 0));
    }

    @Test
    void offeredOptionsAreImmutableOnceCaptured() {
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.GATHER, "a");
        options.put(AgentAction.WAIT, "b");
        PolicyRequest offered = new PolicyRequest("state", options);
        options.put(AgentAction.ABORT_TASK, "sneaked in later");
        assertEquals(2, offered.options().size());
        assertThrows(UnsupportedOperationException.class,
                () -> offered.options().put(AgentAction.MINE, "no"));
    }

    @Test
    void actionParsingIsStrictAndCaseInsensitive() {
        assertEquals(AgentAction.GATHER, AgentAction.parse("GATHER"));
        assertEquals(AgentAction.GATHER, AgentAction.parse(" gather "));
        assertNull(AgentAction.parse("gather the logs"));
        assertNull(AgentAction.parse("DESTROY_EVERYTHING"));
        assertNull(AgentAction.parse(""));
        assertNull(AgentAction.parse(null));
    }
}
