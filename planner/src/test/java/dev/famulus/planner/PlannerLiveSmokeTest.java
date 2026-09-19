package dev.famulus.planner;

import dev.famulus.core.PlanRequest;
import dev.famulus.core.PlannedTask;
import dev.famulus.core.PlannerException;
import dev.famulus.core.TaskPlan;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlannerLiveSmokeTest {
    private static final Set<String> GATHERABLE = Set.of(
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:dirt", "minecraft:sand", "minecraft:red_sand");

    private static ChatPlanner planner() {
        String key = System.getenv("OPENROUTER_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "OPENROUTER_API_KEY not set; skipping the live planner call");
        return new ChatPlanner(PlannerConfig.openRouter(key, PlannerConfig.DEFAULT_MODEL));
    }

    @Test
    void producesAPlanThisAgentCanActuallyRun() throws Exception {
        TaskPlan plan = planner().plan(new PlanRequest(
                "I want to build a small wooden shelter. Get me the wood and some dirt for the floor.",
                "Inventory: empty except a diamond axe and a shovel. Overworld, daytime, on grass.",
                GATHERABLE));

        assertFalse(plan.tasks().isEmpty());
        assertTrue(plan.isExecutable(), "Every task must have an executor");
        for (PlannedTask task : plan.tasks()) {
            if (task instanceof PlannedTask.Gather gather) {
                assertTrue(GATHERABLE.contains(gather.itemId()),
                        "The parser must never let an unobtainable item through: " + gather.itemId());
                assertTrue(gather.count() >= 1 && gather.count() <= PlanParser.MAX_COUNT);
            }
        }
        assertTrue(plan.tasks().stream().anyMatch(task -> task instanceof PlannedTask.Gather),
                "A shelter needs materials, so the plan should gather something");
        System.out.println("live plan: " + plan.goal());
        plan.tasks().forEach(task -> System.out.println("  " + task.describe()));
    }

    @Test
    void refusesToInventItemsItWasNotOffered() {
        try {
            TaskPlan plan = planner().plan(new PlanRequest(
                    "Get me 5 diamonds and 3 iron ingots.",
                    "Inventory: empty. Overworld.",
                    GATHERABLE));
            for (PlannedTask task : plan.tasks()) {
                if (task instanceof PlannedTask.Gather gather) {
                    assertTrue(GATHERABLE.contains(gather.itemId()),
                            "Accepted an unobtainable item: " + gather.itemId());
                }
                if (task instanceof PlannedTask.Mine mine) {
                    assertFalse(mine.blockId().isBlank(),
                            "A mine outside the catalogue must say which block to break");
                }
            }
            System.out.println("live refusal: model answered within its limits, goal=" + plan.goal());
        } catch (PlannerException refused) {
            assertNotNull(refused.getMessage());
            System.out.println("live refusal: " + refused.getMessage());
        }
    }
}
