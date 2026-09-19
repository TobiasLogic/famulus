package dev.famulus.jev;

import dev.famulus.core.AgentAction;
import dev.famulus.core.PolicyDecision;
import dev.famulus.core.PolicyGate;
import dev.famulus.core.PolicyGateConfig;
import dev.famulus.core.PolicyRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JevLiveSmokeTest {
    private static JevClient liveClient() {
        String key = System.getenv(JevConfig.API_KEY_VARIABLE);
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                JevConfig.API_KEY_VARIABLE + " not set; skipping the live Jev call");
        return new JevClient(JevConfig.fromEnvironment());
    }

    @Test
    void anObviousSituationProducesTheObviousDecision() throws Exception {
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.GATHER, "Keep gathering the current target item");
        options.put(AgentAction.DEPOSIT_ITEM, "Put the items into the nearby chest");
        options.put(AgentAction.COMPLETE_TASK, "The task goal is met; finish the task");
        options.put(AgentAction.RECOVER, "Execution stalled; attempt local recovery");

        PolicyRequest request = new PolicyRequest("""
                Minecraft agent state.
                Goal: collect 32 oak logs, then deposit them in a chest.
                Current task: gather minecraft:oak_log, target 32.
                Inventory: 32 oak logs, 1 diamond axe. 34 of 36 slots free.
                Executor: Baritone mine process inactive, no path in progress.
                Last result: SUCCESS, 32/32, attempt 1 of 3.
                Player: alive, connected, overworld.
                Nearby: a chest 8 blocks away. Several oak logs within 12 blocks.""", options);

        PolicyDecision decision = liveClient().decide(request);

        assertTrue(options.containsKey(decision.action()),
                "Jev must only ever return an offered action, got " + decision.action());
        assertTrue(decision.confidence() >= 0 && decision.confidence() <= 1);
        assertTrue(decision.replanUrgency() >= 0 && decision.replanUrgency() <= 1);
        assertFalse(decision.probabilities().isEmpty(), "Expected a probability per option");

        assertNotEquals(AgentAction.GATHER, decision.action());

        System.out.printf("live Jev: %s confidence=%.2f replan=%.2f %s%n",
                decision.action(), decision.confidence(), decision.replanUrgency(),
                decision.probabilities());
    }

    @Test
    void theGateAcceptsOrEscalatesALiveDecisionWithoutThrowing() {
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.GATHER, "Keep gathering the current target item");
        options.put(AgentAction.RECOVER, "Execution stalled; attempt local recovery");
        options.put(AgentAction.COMPLETE_TASK, "The task goal is met; finish the task");

        PolicyGate gate = new PolicyGate(liveClient(), PolicyGateConfig.defaults(), AgentAction.WAIT);
        AgentAction chosen = gate.next(new PolicyRequest("""
                Minecraft agent state.
                Goal: collect 32 oak logs.
                Current task: gather minecraft:oak_log, target 32.
                Inventory: 31 oak logs, 1 diamond axe.
                Executor: Baritone mine process inactive.
                Last result: RUNNING, 31/32, attempt 1 of 3.
                Nearby: several oak logs within 12 blocks.""", options));

        assertTrue(chosen.isExecutable(), "The gate must only ever return a dispatchable action");
        System.out.println("live gate: " + chosen + " because " + gate.lastReason());
    }
}
