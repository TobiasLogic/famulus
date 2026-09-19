package dev.famulus.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatherControllerTest {
    private static final GatherTask LOGS = new GatherTask("logs", "minecraft:oak_log", "minecraft:oak_log", 32);
    private static final GatherConfig CONFIG = new GatherConfig(5_000, 20_000, 500, 3);

    @Test
    void idleControllerDoesNotTouchExecutor() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        assertEquals(TaskStatus.IDLE, controller.result().status());
        assertFalse(controller.isRunning());
        assertNull(controller.task());
        controller.stop("Stop");
        controller.tick(world(0), 10);
        assertEquals(0, executor.calls());
    }

    @Test
    void alreadySatisfiedTargetDoesNotStartOrCancelOtherWork() {
        FakeExecutor executor = new FakeExecutor();
        executor.active = true;
        GatherController controller = controller(executor);
        controller.start(LOGS, new WorldSnapshot(true, true, "session:overworld", 40, false), 0);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(0, controller.result().attempts());
        assertEquals(0, executor.calls());
        assertTrue(executor.active);
    }

    @Test
    void countMeansFinalInventoryTotalAndSuccessCancelsExecution() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(20), 100);
        assertEquals(LOGS, executor.lastTask);
        assertEquals(1, executor.starts);
        controller.tick(world(31), 200);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(32), 300);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(32, controller.result().currentCount());
        assertEquals(32, controller.result().targetCount());
        assertEquals(1, controller.result().attempts());
        assertEquals(1, executor.cancels);
        assertFalse(controller.isRunning());
    }

    @Test
    void terminalResultIsRetainedUntilAnotherTaskStarts() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(33), 1);
        TaskResult success = controller.result();
        controller.tick(world(0), 2);
        controller.stop("Later stop");
        assertSame(success, controller.result());
        assertEquals(1, executor.cancels);
        controller.start(LOGS, world(0), 3);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        assertEquals(1, controller.result().attempts());
        assertEquals(2, executor.starts);
    }

    @Test
    void cannotReplaceRunningOrRecoveringTask() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        GatherTask other = new GatherTask("stone", "minecraft:cobblestone", "minecraft:stone", 5);
        assertThrows(IllegalStateException.class, () -> controller.start(other, world(0), 1));
        controller.tick(world(0), 5_000);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertThrows(IllegalStateException.class, () -> controller.start(other, world(0), 5_001));
        assertEquals(LOGS, controller.task());
        assertEquals(1, executor.starts);
    }

    @Test
    void disconnectedStartIsBlockedWithoutCancellingOtherWork() {
        assertStartSafety(new WorldSnapshot(false, false, null, 0, false), TaskStatus.BLOCKED);
    }

    @Test
    void deadStartFailsWithoutExecution() {
        assertStartSafety(new WorldSnapshot(true, false, "session:overworld", 0, true), TaskStatus.FAILED);
    }

    @Test
    void unavailableWorldIdentityIsBlockedWithoutExecution() {
        assertStartSafety(new WorldSnapshot(true, true, null, 0, true), TaskStatus.BLOCKED);
        assertStartSafety(new WorldSnapshot(true, true, " ", 0, true), TaskStatus.BLOCKED);
    }

    @Test
    void inventoryWithoutCapacityIsBlockedWithoutExecution() {
        assertStartSafety(new WorldSnapshot(true, true, "session:overworld", 0, false), TaskStatus.BLOCKED);
    }

    @Test
    void disconnectCancelsBeforeConsideringInventorySuccess() {
        assertRunningSafety(new WorldSnapshot(false, false, null, 32, false), TaskStatus.BLOCKED);
    }

    @Test
    void deathCancelsBeforeConsideringInventorySuccess() {
        assertRunningSafety(new WorldSnapshot(true, false, "session:overworld", 32, false), TaskStatus.FAILED);
    }

    @Test
    void dimensionChangeCancelsBeforeConsideringInventorySuccess() {
        assertRunningSafety(new WorldSnapshot(true, true, "session:nether", 32, true), TaskStatus.WORLD_CHANGED);
    }

    @Test
    void reconnectToSameDimensionStillChangesWorldIdentity() {
        assertRunningSafety(new WorldSnapshot(true, true, "new-session:overworld", 0, true), TaskStatus.WORLD_CHANGED);
    }

    @Test
    void becomingFullOnlyBlocksAnUnmetTarget() {
        assertRunningSafety(new WorldSnapshot(true, true, "session:overworld", 31, false), TaskStatus.BLOCKED);
        assertRunningSafety(new WorldSnapshot(true, true, "session:overworld", 32, false), TaskStatus.SUCCESS);
    }

    @Test
    void userStopIsIdempotentAndReasonIsRetained() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(2), 0);
        controller.stop("Requested stop");
        controller.stop("Duplicate stop");
        assertEquals(TaskStatus.CANCELLED, controller.result().status());
        assertEquals("Requested stop", controller.result().message());
        assertEquals(1, executor.cancels);
        assertEquals(2, controller.result().currentCount());
    }

    @Test
    void integrationFailurePreservesReasonAndCancelsOwnedExecution() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(2), 0);
        controller.fail("Minecraft observation failed: missing inventory");
        assertEquals(TaskStatus.FAILED, controller.result().status());
        assertEquals("Minecraft observation failed: missing inventory", controller.result().message());
        assertEquals(2, controller.result().currentCount());
        assertEquals(1, executor.cancels);
        assertFalse(executor.active);
        assertFalse(controller.isRunning());
    }

    @Test
    void integrationFailureDoesNotReplaceAnExistingTerminalResult() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        TaskResult idle = controller.result();
        controller.fail("Before a task");
        assertSame(idle, controller.result());
        controller.start(LOGS, world(0), 0);
        controller.tick(world(32), 1);
        TaskResult success = controller.result();
        controller.fail("After a task");
        assertSame(success, controller.result());
        assertEquals(1, executor.cancels);
    }

    @Test
    void integrationFailureReportsCleanupFailureAndRequiresExplicitCleanup() {
        FakeExecutor executor = new FakeExecutor();
        executor.cancelFailures = 1;
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.fail("Observation failed");
        assertEquals(TaskStatus.FAILED, controller.result().status());
        assertTrue(controller.result().message().contains("Observation failed"));
        assertTrue(controller.result().message().contains("cancellation failed"));
        TaskResult failure = controller.result();
        controller.fail("Duplicate failure");
        assertSame(failure, controller.result());
        assertEquals(1, executor.cancels);
        assertThrows(IllegalStateException.class, () -> controller.start(LOGS, world(0), 1));
        controller.stop("Retry cleanup");
        assertFalse(executor.active);
        controller.start(LOGS, world(0), 2);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
    }

    @Test
    void stoppingDuringRecoveryDoesNotCancelTwiceOrRetry() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 5_000);
        controller.stop(null);
        controller.tick(world(0), 6_000);
        assertEquals(TaskStatus.CANCELLED, controller.result().status());
        assertEquals("Stopped by user", controller.result().message());
        assertEquals(1, executor.starts);
        assertEquals(1, executor.cancels);
    }

    @Test
    void activePathWithoutInventoryProgressStillStalls() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 4_999);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(0), 5_000);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertEquals(1, executor.cancels);
    }

    @Test
    void onlyNewInventoryHighWaterMarkExtendsStallDeadline() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = new GatherController(executor, new GatherConfig(100, 1_000, 10, 2));
        controller.start(LOGS, world(2), 0);
        controller.tick(world(3), 99);
        controller.tick(world(1), 150);
        controller.tick(world(3), 198);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(3), 199);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
    }

    @Test
    void retryWaitsUntilDelayAndGetsItsOwnStallBudget() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 5_000);
        controller.tick(world(0), 5_499);
        assertEquals(1, executor.starts);
        controller.tick(world(0), 5_500);
        assertEquals(2, executor.starts);
        controller.tick(world(0), 10_499);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(0), 10_500);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
    }

    @Test
    void repeatedStallsExhaustTotalAttemptLimitAndRequestReplan() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 5_000);
        controller.tick(world(0), 5_500);
        controller.tick(world(0), 10_500);
        controller.tick(world(0), 11_000);
        controller.tick(world(0), 16_000);
        controller.tick(world(0), 19_000);
        assertEquals(TaskStatus.REPLAN_REQUIRED, controller.result().status());
        assertEquals(3, controller.result().attempts());
        assertEquals(3, executor.starts);
        assertEquals(3, executor.cancels);
        assertTrue(controller.result().message().contains("exhausted 3 attempts"));
    }

    @Test
    void absoluteTaskDeadlineIncludesRetryTimeAndDoesNotResetOnProgress() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = new GatherController(executor, new GatherConfig(100, 250, 50, 10));
        controller.start(LOGS, world(0), 1_000);
        controller.tick(world(0), 1_100);
        controller.tick(world(0), 1_150);
        controller.tick(world(1), 1_249);
        controller.tick(world(2), 1_250);
        assertEquals(TaskStatus.TIMEOUT, controller.result().status());
        assertEquals(2, executor.starts);
        assertEquals(2, executor.cancels);
    }

    @Test
    void absoluteTaskDeadlineExpiresEvenDuringRecoveryDelay() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = new GatherController(executor, new GatherConfig(100, 200, 500, 3));
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 100);
        controller.tick(world(0), 200);
        assertEquals(TaskStatus.TIMEOUT, controller.result().status());
        assertEquals(1, executor.starts);
        assertEquals(1, executor.cancels);
    }

    @Test
    void deadlineTakesPrecedenceOverLateObservedSuccess() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(32), 20_000);
        assertEquals(TaskStatus.TIMEOUT, controller.result().status());
    }

    @Test
    void inactiveExecutorNeverMeansSuccessAndGetsPickupGrace() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(30), 0);
        executor.active = false;
        controller.tick(world(30), 100);
        controller.tick(world(31), 1_099);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        assertEquals(0, executor.cancels);
        controller.tick(world(31), 1_100);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertEquals(1, executor.cancels);
    }

    @Test
    void delayedPickupCanSatisfyTargetDuringInactiveGrace() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(31), 0);
        executor.active = false;
        controller.tick(world(31), 100);
        controller.tick(world(32), 200);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(1, executor.starts);
    }

    @Test
    void becomingActiveAgainResetsConsecutiveInactivityGrace() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        executor.active = false;
        controller.tick(world(0), 100);
        executor.active = true;
        controller.tick(world(0), 1_000);
        executor.active = false;
        controller.tick(world(0), 1_100);
        controller.tick(world(0), 2_099);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(0), 2_100);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
    }

    @Test
    void pickupDuringRecoveryAvoidsStartingAnotherAttempt() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(31), 0);
        controller.tick(world(31), 5_000);
        controller.tick(world(32), 5_500);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(1, executor.starts);
        assertEquals(1, executor.cancels);
    }

    @Test
    void partialStartFailureIsCancelledAndRetriedWithinBudget() {
        FakeExecutor executor = new FakeExecutor();
        executor.startFailures = 1;
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertFalse(executor.active);
        assertEquals(1, executor.cancels);
        assertTrue(controller.result().message().contains("synthetic start failure"));
        controller.tick(world(0), 500);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        assertEquals(2, executor.starts);
    }

    @Test
    void zeroDelayStartFailuresDoNotCreateSynchronousInfiniteRetryLoop() {
        FakeExecutor executor = new FakeExecutor();
        executor.startFailures = 100;
        GatherController controller = new GatherController(executor, new GatherConfig(100, 1_000, 0, 3));
        controller.start(LOGS, world(0), 0);
        assertEquals(1, executor.starts);
        controller.tick(world(0), 0);
        assertEquals(2, executor.starts);
        controller.tick(world(0), 0);
        assertEquals(3, executor.starts);
        assertEquals(TaskStatus.REPLAN_REQUIRED, controller.result().status());
        controller.tick(world(0), 0);
        assertEquals(3, executor.starts);
        assertEquals(3, executor.cancels);
    }

    @Test
    void executorInspectionFailureIsActionableAndRecoverable() {
        FakeExecutor executor = new FakeExecutor();
        executor.inspectionFailures = 1;
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 1);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertTrue(controller.result().message().contains("synthetic inspection failure"));
        controller.tick(world(0), 501);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        assertEquals(2, executor.starts);
    }

    @Test
    void cancellationFailureCannotReportSuccessAndBlocksReplacementUntilCleanup() {
        FakeExecutor executor = new FakeExecutor();
        executor.cancelFailures = 1;
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(32), 1);
        assertEquals(TaskStatus.FAILED, controller.result().status());
        assertTrue(controller.result().message().contains("cancellation failed"));
        assertTrue(executor.active);
        assertFalse(controller.isRunning());
        assertThrows(IllegalStateException.class, () -> controller.start(LOGS, world(0), 2));
        TaskResult failure = controller.result();
        controller.stop("Retry cleanup");
        assertSame(failure, controller.result());
        assertFalse(executor.active);
        controller.start(LOGS, world(0), 3);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
    }

    @Test
    void cancellationFailureDuringRecoveryNeverStartsAnOverlappingAttempt() {
        FakeExecutor executor = new FakeExecutor();
        executor.cancelFailures = 3;
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(world(0), 5_000);
        controller.tick(world(0), 5_500);
        controller.stop("Retry cleanup");
        assertEquals(TaskStatus.FAILED, controller.result().status());
        assertTrue(controller.result().message().contains("Cancellation retry failed"));
        assertEquals(1, executor.starts);
        assertEquals(2, executor.cancels);
    }

    @Test
    void startAndCancellationErrorsAreBothReported() {
        FakeExecutor executor = new FakeExecutor();
        executor.startFailures = 1;
        executor.cancelFailures = 1;
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        assertEquals(TaskStatus.FAILED, controller.result().status());
        assertTrue(controller.result().message().contains("synthetic start failure"));
        assertTrue(controller.result().message().contains("synthetic cancellation failure"));
    }

    @Test
    void backwardsClockStopsOwnedExecution() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 100);
        controller.tick(world(1), 150);
        controller.tick(world(2), 149);
        assertEquals(TaskStatus.FAILED, controller.result().status());
        assertTrue(controller.result().message().contains("clock moved backwards"));
        assertEquals(1, executor.cancels);
    }

    @Test
    void negativeMonotonicOriginAndDuplicateTicksAreSupported() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), -10_000);
        controller.tick(world(0), -10_000);
        controller.tick(world(1), -5_001);
        controller.tick(world(2), -5_001);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        assertEquals(1, executor.starts);
        assertEquals(0, executor.cancels);
    }

    @Test
    void extremeForwardClockJumpExpiresWithoutArithmeticOverflow() {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), Long.MIN_VALUE);
        controller.tick(world(0), Long.MAX_VALUE);
        assertEquals(TaskStatus.TIMEOUT, controller.result().status());
        assertEquals(1, executor.cancels);
    }

    @Test
    void invalidApiArgumentsDoNotReplaceTask() {
        FakeExecutor executor = new FakeExecutor();
        assertThrows(NullPointerException.class, () -> new GatherController(null, CONFIG));
        assertThrows(NullPointerException.class, () -> new GatherController(executor, null));
        GatherController controller = controller(executor);
        assertThrows(NullPointerException.class, () -> controller.start(null, world(0), 0));
        assertThrows(NullPointerException.class, () -> controller.start(LOGS, null, 0));
        assertEquals(TaskStatus.IDLE, controller.result().status());
        assertEquals(0, executor.calls());
        controller.start(LOGS, world(0), 0);
        assertThrows(NullPointerException.class, () -> controller.tick(null, 1));
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.stop("Cleanup");
    }

    @Test
    void taskAndSnapshotValidateStructuredInputs() {
        assertThrows(NullPointerException.class, () -> new GatherTask(null, "minecraft:oak_log", "minecraft:oak_log", 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherTask(" ", "minecraft:oak_log", "minecraft:oak_log", 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherTask("logs", "oak_log", "minecraft:oak_log", 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherTask("logs", "minecraft:oak_log", "minecraft:Oak_Log", 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherTask("logs", "minecraft:oak_log", "minecraft:oak_log", 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldSnapshot(true, true, "world", -1, true));
        assertDoesNotThrow(() -> new GatherTask("modded", "my_mod:item/path", "my_mod:rock", 64));
    }

    @Test
    void configurationRejectsUnboundedOrInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new GatherConfig(0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherConfig(1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherConfig(1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GatherConfig(1, 1, 0, 0));
        assertEquals(new GatherConfig(60_000, 600_000, 2_000, 3), GatherConfig.defaults());
    }

    private static GatherController controller(FakeExecutor executor) {
        return new GatherController(executor, CONFIG);
    }

    private static WorldSnapshot world(int count) {
        return new WorldSnapshot(true, true, "session:overworld", count, true);
    }

    private static void assertStartSafety(WorldSnapshot snapshot, TaskStatus expected) {
        FakeExecutor executor = new FakeExecutor();
        executor.active = true;
        GatherController controller = controller(executor);
        controller.start(LOGS, snapshot, 0);
        assertEquals(expected, controller.result().status());
        assertEquals(0, executor.calls());
        assertTrue(executor.active);
    }

    private static void assertRunningSafety(WorldSnapshot snapshot, TaskStatus expected) {
        FakeExecutor executor = new FakeExecutor();
        GatherController controller = controller(executor);
        controller.start(LOGS, world(0), 0);
        controller.tick(snapshot, 1);
        assertEquals(expected, controller.result().status());
        assertEquals(1, executor.cancels);
        assertFalse(executor.active);
        assertFalse(controller.isRunning());
    }

    private static final class FakeExecutor implements GatherExecutor {
        private boolean active;
        private int starts;
        private int cancels;
        private int inspections;
        private int startFailures;
        private int cancelFailures;
        private int inspectionFailures;
        private GatherTask lastTask;

        @Override
        public void start(GatherTask task) {
            starts++;
            lastTask = task;
            active = true;
            if (startFailures > 0) {
                startFailures--;
                throw new IllegalStateException("synthetic start failure");
            }
        }

        @Override
        public void cancel() {
            cancels++;
            if (cancelFailures > 0) {
                cancelFailures--;
                throw new IllegalStateException("synthetic cancellation failure");
            }
            active = false;
        }

        @Override
        public boolean isActive() {
            inspections++;
            if (inspectionFailures > 0) {
                inspectionFailures--;
                throw new IllegalStateException("synthetic inspection failure");
            }
            return active;
        }

        private int calls() {
            return starts + cancels + inspections;
        }
    }
}
