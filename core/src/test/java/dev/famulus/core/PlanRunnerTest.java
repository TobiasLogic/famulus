package dev.famulus.core;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanRunnerTest {
    private static final PlannedTask LOGS = new PlannedTask.Gather("t1", "minecraft:oak_log", 32);
    private static final PlannedTask DIRT = new PlannedTask.Gather("t2", "minecraft:dirt", 16);
    private static final PlannedTask SAND = new PlannedTask.Gather("t3", "minecraft:sand", 8);

    private static TaskPlan plan(PlannedTask... tasks) {
        return new TaskPlan("test goal", List.of(tasks));
    }

    private static TaskResult result(TaskStatus status) {
        return new TaskResult(status, status.name(), 0, 1, 1);
    }

    private static PlanRunner runner(TaskPlan plan) {
        return new PlanRunner(plan, 3);
    }

    @Test
    void walksEveryTaskInOrderThenCompletes() {
        PlanRunner runner = runner(plan(LOGS, DIRT, SAND));
        assertEquals(PlanStep.RUN_CURRENT, runner.start());
        assertEquals(LOGS, runner.current());
        assertEquals(PlanStep.RUN_CURRENT, runner.onTaskResult(result(TaskStatus.SUCCESS)));
        assertEquals(DIRT, runner.current());
        assertEquals(PlanStep.RUN_CURRENT, runner.onTaskResult(result(TaskStatus.SUCCESS)));
        assertEquals(SAND, runner.current());
        assertEquals(PlanStep.PLAN_COMPLETE, runner.onTaskResult(result(TaskStatus.SUCCESS)));
        assertTrue(runner.step().isSuccess());
        assertEquals(3, runner.completedCount());
        assertEquals(3, runner.results().size());
    }

    @Test
    void aFailedTaskAsksThePolicyRatherThanGuessing() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        assertEquals(PlanStep.CONSULT_POLICY, runner.onTaskResult(result(TaskStatus.PATH_NOT_FOUND)));
        assertEquals(LOGS, runner.current(), "The failed task must stay current until resolved");
        assertTrue(runner.policyOptions().keySet().containsAll(List.of(
                AgentAction.RECOVER, AgentAction.COMPLETE_TASK,
                AgentAction.REQUEST_REPLAN, AgentAction.ABORT_TASK)));
    }

    @Test
    void recoverRetriesTheSameTaskAndCountsTheAttempt() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        assertEquals(1, runner.attempts());
        runner.onTaskResult(result(TaskStatus.TIMEOUT));
        assertEquals(PlanStep.RUN_CURRENT, runner.onPolicyDecision(AgentAction.RECOVER));
        assertEquals(LOGS, runner.current());
        assertEquals(2, runner.attempts());
    }

    @Test
    void exploringIsOfferedBecauseARetryInTheSameSpotFindsTheSameNothing() {
        PlanRunner runner = runner(plan(LOGS));
        runner.start();
        runner.onTaskResult(result(TaskStatus.PATH_NOT_FOUND));
        assertTrue(runner.policyOptions().containsKey(AgentAction.EXPLORE));
        assertTrue(AgentAction.EXPLORE.isExecutable());
    }

    @Test
    void exploringThenRetriesTheSameTask() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        runner.onTaskResult(result(TaskStatus.PATH_NOT_FOUND));
        assertEquals(PlanStep.EXPLORE, runner.onPolicyDecision(AgentAction.EXPLORE));
        assertEquals(LOGS, runner.current());
        assertEquals(PlanStep.RUN_CURRENT, runner.onExploreComplete("explored for 90s"));
        assertEquals(LOGS, runner.current());
        assertTrue(runner.reason().contains("explored for 90s"));
    }

    @Test
    void exploringCountsAsAnAttemptSoItCannotWanderForever() {
        PlanRunner runner = new PlanRunner(plan(LOGS), 2);
        runner.start();
        assertEquals(1, runner.attempts());
        runner.onTaskResult(result(TaskStatus.PATH_NOT_FOUND));
        runner.onPolicyDecision(AgentAction.EXPLORE);
        assertEquals(2, runner.attempts());
        runner.onExploreComplete("explored");
        assertEquals(PlanStep.PLAN_FAILED, runner.onTaskResult(result(TaskStatus.PATH_NOT_FOUND)),
                "The attempt budget must still run out even when exploring is chosen every time");
    }

    @Test
    void exploreCompletionOutsideAnExplorationIsRejected() {
        PlanRunner runner = runner(plan(LOGS));
        runner.start();
        assertThrows(IllegalStateException.class, () -> runner.onExploreComplete("nope"));
    }

    @Test
    void completeTaskAcceptsAFailureAndMovesOn() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        runner.onTaskResult(result(TaskStatus.BLOCKED));
        assertEquals(PlanStep.RUN_CURRENT, runner.onPolicyDecision(AgentAction.COMPLETE_TASK));
        assertEquals(DIRT, runner.current());
        assertEquals(1, runner.attempts(), "A new task starts with a fresh attempt budget");
    }

    @Test
    void completeTaskOnTheLastTaskFinishesThePlan() {
        PlanRunner runner = runner(plan(LOGS));
        runner.start();
        runner.onTaskResult(result(TaskStatus.BLOCKED));
        assertEquals(PlanStep.PLAN_COMPLETE, runner.onPolicyDecision(AgentAction.COMPLETE_TASK));
    }

    @Test
    void escalationEndsThePlanAsNeedingAReplan() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        runner.onTaskResult(result(TaskStatus.REPLAN_REQUIRED));
        assertEquals(PlanStep.REPLAN_REQUIRED, runner.onPolicyDecision(AgentAction.REQUEST_REPLAN));
        assertTrue(runner.step().isTerminal());
        assertFalse(runner.step().isSuccess());
    }

    @Test
    void abortEndsThePlan() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        runner.onTaskResult(result(TaskStatus.FAILED));
        assertEquals(PlanStep.PLAN_FAILED, runner.onPolicyDecision(AgentAction.ABORT_TASK));
    }

    @Test
    void anUnofferedPolicyAnswerAbortsRatherThanBeingInterpreted() {
        PlanRunner runner = runner(plan(LOGS));
        runner.start();
        runner.onTaskResult(result(TaskStatus.FAILED));
        assertEquals(PlanStep.PLAN_FAILED, runner.onPolicyDecision(AgentAction.CRAFT));
        assertTrue(runner.reason().contains("CRAFT"));
    }

    @Test
    void repeatedFailureExhaustsTheAttemptBudgetInsteadOfLoopingForever() {
        PlanRunner runner = new PlanRunner(plan(LOGS, DIRT), 2);
        runner.start();
        assertEquals(PlanStep.CONSULT_POLICY, runner.onTaskResult(result(TaskStatus.TIMEOUT)));
        assertEquals(PlanStep.RUN_CURRENT, runner.onPolicyDecision(AgentAction.RECOVER));
        assertEquals(PlanStep.PLAN_FAILED, runner.onTaskResult(result(TaskStatus.TIMEOUT)),
                "The second failure is the last allowed attempt, so no further decision is sought");
        assertTrue(runner.reason().contains("failed 2 times"));
    }

    @Test
    void aUserCancellationIsObeyedWithoutConsultingAnyone() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        assertEquals(PlanStep.PLAN_CANCELLED, runner.onTaskResult(result(TaskStatus.CANCELLED)));
        assertTrue(runner.step().isTerminal());
    }

    @Test
    void attemptBudgetIsPerTaskNotPerPlan() {
        PlanRunner runner = new PlanRunner(plan(LOGS, DIRT), 2);
        runner.start();
        runner.onTaskResult(result(TaskStatus.TIMEOUT));
        runner.onPolicyDecision(AgentAction.RECOVER);
        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertEquals(DIRT, runner.current());
        assertEquals(1, runner.attempts());
        assertEquals(PlanStep.CONSULT_POLICY, runner.onTaskResult(result(TaskStatus.TIMEOUT)));
    }

    @Test
    void everyTaskTypeCanBePlannedNowThatEachHasAnExecutor() {
        TaskPlan everything = new TaskPlan("do everything", List.of(
                LOGS,
                new PlannedTask.Mine("m", "minecraft:stone", "minecraft:cobblestone", 8),
                new PlannedTask.Craft("c", "minecraft:oak_planks", 4),
                new PlannedTask.Travel("t", 10, 64, 10),
                new PlannedTask.Deposit("d", "minecraft:dirt", 1),
                new PlannedTask.Withdraw("w", "minecraft:dirt", 1),
                new PlannedTask.PlaceBlock("p", "minecraft:cobblestone", 1, 2, 3),
                new PlannedTask.Interact("i", "minecraft:lever"),
                new PlannedTask.Build("b", "hut.schem", 0, 0, 0)));
        assertTrue(everything.isExecutable());
        assertTrue(everything.unexecutable().isEmpty());
        assertDoesNotThrow(() -> new PlanRunner(everything, 3));
    }

    @Test
    void callingOutOfOrderIsRejectedRatherThanSilentlyMisbehaving() {
        PlanRunner runner = runner(plan(LOGS));
        assertThrows(IllegalStateException.class, () -> runner.onTaskResult(result(TaskStatus.SUCCESS)));
        assertThrows(IllegalStateException.class, runner::policyOptions);
        runner.start();
        assertThrows(IllegalStateException.class, runner::start);
        assertThrows(IllegalStateException.class, () -> runner.onPolicyDecision(AgentAction.RECOVER));
        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertThrows(IllegalStateException.class, () -> runner.onTaskResult(result(TaskStatus.SUCCESS)));
    }

    @Test
    void plansValidateGoalTasksAndUniqueIds() {
        assertThrows(IllegalArgumentException.class, () -> new TaskPlan(" ", List.of(LOGS)));
        assertThrows(IllegalArgumentException.class, () -> new TaskPlan("goal", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskPlan("goal",
                List.of(LOGS, new PlannedTask.Gather("t1", "minecraft:dirt", 4))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedTask.Gather("t1", "oak_log", 4));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedTask.Gather("t1", "minecraft:oak_log", 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanRunner(plan(LOGS), 0));
    }

    @Test
    void progressIsReadableForTheScreen() {
        PlanRunner runner = runner(plan(LOGS, DIRT));
        runner.start();
        assertTrue(runner.progress().startsWith("[0/2]"));
        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertTrue(runner.progress().startsWith("[1/2]"));
        assertTrue(runner.progress().contains("gather 16 minecraft:dirt"));
    }

    @Test
    void anInsertedTaskRunsBeforeTheOneThatNeededIt() {
        PlanRunner runner = new PlanRunner(new TaskPlan("dig", List.of(
                new PlannedTask.Mine("m1", "minecraft:iron_ore", "minecraft:raw_iron", 4),
                new PlannedTask.Craft("c1", "minecraft:bucket", 1))), 3);
        runner.start();
        assertEquals("m1", runner.current().id());
        assertEquals(2, runner.taskCount());

        runner.insertBeforeCurrent(new PlannedTask.Craft("t1", "minecraft:stone_pickaxe", 1),
                "no suitable tool");
        assertEquals(PlanStep.RUN_CURRENT, runner.step());
        assertEquals("t1", runner.current().id(), "The new task should be next");
        assertEquals(3, runner.taskCount());
        assertEquals(1, runner.insertedCount());

        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertEquals("m1", runner.current().id(), "The original task should resume");
        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertEquals("c1", runner.current().id());
        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertEquals(PlanStep.PLAN_COMPLETE, runner.step());
    }

    @Test
    void insertingResetsTheAttemptCountForTheNewTask() {
        PlanRunner runner = new PlanRunner(new TaskPlan("dig", List.of(
                new PlannedTask.Mine("m1", "minecraft:iron_ore", "minecraft:raw_iron", 4))), 3);
        runner.start();
        runner.onTaskResult(result(TaskStatus.RESOURCE_MISSING));
        assertEquals(PlanStep.CONSULT_POLICY, runner.step());
        runner.onPolicyDecision(AgentAction.RECOVER);
        assertEquals(2, runner.attempts());

        runner.insertBeforeCurrent(new PlannedTask.Craft("t1", "minecraft:stone_pickaxe", 1), "why");
        assertEquals(1, runner.attempts(), "A fresh task starts on attempt one");
    }

    @Test
    void insertionIsBoundedSoAFailingToolLoopCannotRunForever() {
        PlanRunner runner = new PlanRunner(new TaskPlan("dig", List.of(
                new PlannedTask.Mine("m1", "minecraft:iron_ore", "minecraft:raw_iron", 4))), 3);
        runner.start();
        for (int i = 0; i < PlanRunner.MAX_INSERTED_TASKS; i++) {
            runner.insertBeforeCurrent(new PlannedTask.Craft("t" + i, "minecraft:stone_pickaxe", 1),
                    "why");
        }
        PlanStep step = runner.insertBeforeCurrent(
                new PlannedTask.Craft("tx", "minecraft:stone_pickaxe", 1), "why");
        assertEquals(PlanStep.PLAN_FAILED, step);
        assertTrue(runner.reason().contains("Gave up after inserting"));
    }

    @Test
    void anInsertedTaskMustBeRunnableAndUniquelyNamed() {
        PlanRunner runner = new PlanRunner(new TaskPlan("dig", List.of(
                new PlannedTask.Mine("m1", "minecraft:iron_ore", "minecraft:raw_iron", 4))), 3);
        runner.start();
        assertThrows(IllegalArgumentException.class, () -> runner.insertBeforeCurrent(
                new PlannedTask.Craft("m1", "minecraft:stone_pickaxe", 1), "clash"));
        assertThrows(NullPointerException.class, () -> runner.insertBeforeCurrent(null, "none"));
    }

    @Test
    void nothingCanBeInsertedOnceThePlanIsOver() {
        PlanRunner runner = new PlanRunner(new TaskPlan("dig", List.of(
                new PlannedTask.Mine("m1", "minecraft:iron_ore", "minecraft:raw_iron", 4))), 3);
        runner.start();
        runner.onTaskResult(result(TaskStatus.SUCCESS));
        assertEquals(PlanStep.PLAN_COMPLETE, runner.step());
        assertThrows(IllegalStateException.class, () -> runner.insertBeforeCurrent(
                new PlannedTask.Craft("t1", "minecraft:stone_pickaxe", 1), "too late"));
    }
}
