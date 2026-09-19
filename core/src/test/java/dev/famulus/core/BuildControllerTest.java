package dev.famulus.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildControllerTest {
    private static final PlannedTask.Build HUT = new PlannedTask.Build("b1", "hut.schem", 0, 64, 0);
    private static final GatherConfig CONFIG = new GatherConfig(5_000, 20_000, 500, 3);

    private static BuildSnapshot world(int remaining, int total) {
        return new BuildSnapshot(true, true, "session:overworld", remaining, total, true);
    }

    private static final class FakeBuilder implements BuildExecutor {
        boolean active;
        int starts;
        int cancels;
        RuntimeException startFailure;

        @Override
        public void start(PlannedTask.Build task) {
            starts++;
            if (startFailure != null) {
                throw startFailure;
            }
            active = true;
        }

        @Override
        public void cancel() {
            cancels++;
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    @Test
    void completionIsAnObservedWorldState() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(40, 40), 0);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(10, 40), 1_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        assertEquals(30, controller.result().currentCount());
        controller.tick(world(0, 40), 2_000);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(1, builder.cancels);
    }

    @Test
    void anAlreadyFinishedStructureIsNotRebuilt() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(0, 40), 0);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(0, builder.starts);
    }

    @Test
    void anEmptyBlueprintIsRejectedRatherThanReportedFinished() {
        BuildController controller = new BuildController(new FakeBuilder(), CONFIG);
        controller.start(HUT, world(0, 0), 0);
        assertEquals(TaskStatus.INVALID_TARGET, controller.result().status());
    }

    @Test
    void missingMaterialsAreReportedBeforeStarting() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, new BuildSnapshot(true, true, "w", 40, 40, false), 0);
        assertEquals(TaskStatus.RESOURCE_MISSING, controller.result().status());
        assertEquals(0, builder.starts);
    }

    @Test
    void theBuilderGoingIdleIsNotSuccess() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(40, 40), 0);
        builder.active = false;
        controller.tick(world(30, 40), 1_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status(),
                "A brief idle moment is a settling grace, not a verdict");
        controller.tick(world(30, 40), 4_000);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertTrue(controller.result().message().contains("30 blocks left"));
    }

    @Test
    void runningOutOfMaterialsPartWayThroughSaysSo() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(40, 40), 0);
        builder.active = false;
        controller.tick(new BuildSnapshot(true, true, "session:overworld", 12, 40, false), 1_000);
        controller.tick(new BuildSnapshot(true, true, "session:overworld", 12, 40, false), 4_000);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertTrue(controller.result().message().contains("Ran out of materials"));
    }

    @Test
    void onlyRealProgressExtendsTheStallDeadline() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(40, 40), 0);
        controller.tick(world(39, 40), 4_000);
        controller.tick(world(39, 40), 8_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(world(39, 40), 9_500);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertTrue(controller.result().message().contains("stall timeout"));
    }

    @Test
    void repeatedStallsExhaustAttemptsAndRequestAReplan() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, new GatherConfig(5_000, 120_000, 500, 3));
        controller.start(HUT, world(40, 40), 0);
        long now = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            now += 6_000;
            controller.tick(world(40, 40), now);
            now += 1_000;
            controller.tick(world(40, 40), now);
        }
        assertEquals(TaskStatus.REPLAN_REQUIRED, controller.result().status());
        assertEquals(3, controller.result().attempts());
    }

    @Test
    void theAbsoluteDeadlineStillApplies() {
        BuildController controller = new BuildController(new FakeBuilder(), CONFIG);
        controller.start(HUT, world(40, 40), 0);
        controller.tick(world(5, 40), 20_000);
        assertEquals(TaskStatus.TIMEOUT, controller.result().status());
        assertTrue(controller.result().message().contains("5 blocks left"));
    }

    @Test
    void disconnectDeathAndWorldChangeAllStopTheBuild() {
        BuildController disconnected = new BuildController(new FakeBuilder(), CONFIG);
        disconnected.start(HUT, world(40, 40), 0);
        disconnected.tick(new BuildSnapshot(false, true, "w", 0, 40, true), 1);
        assertEquals(TaskStatus.BLOCKED, disconnected.result().status());

        BuildController died = new BuildController(new FakeBuilder(), CONFIG);
        died.start(HUT, world(40, 40), 0);
        died.tick(new BuildSnapshot(true, false, "session:overworld", 0, 40, true), 1);
        assertEquals(TaskStatus.FAILED, died.result().status());

        BuildController moved = new BuildController(new FakeBuilder(), CONFIG);
        moved.start(HUT, world(40, 40), 0);
        moved.tick(new BuildSnapshot(true, true, "session:nether", 0, 40, true), 1);
        assertEquals(TaskStatus.WORLD_CHANGED, moved.result().status());
    }

    @Test
    void aFailedStartIsCleanedUpAndRetried() {
        FakeBuilder builder = new FakeBuilder();
        builder.startFailure = new IllegalStateException("Baritone busy");
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(40, 40), 0);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertEquals(1, builder.cancels, "A partially started build must still be cancelled");
        assertTrue(controller.result().message().contains("Baritone busy"));
    }

    @Test
    void stoppingIsIdempotentAndCancelsOwnedWork() {
        FakeBuilder builder = new FakeBuilder();
        BuildController controller = new BuildController(builder, CONFIG);
        controller.start(HUT, world(40, 40), 0);
        controller.stop("Stopped by user");
        assertEquals(TaskStatus.CANCELLED, controller.result().status());
        assertEquals(1, builder.cancels);
        controller.stop("again");
        assertEquals(TaskStatus.CANCELLED, controller.result().status());
        assertEquals(1, builder.cancels);
    }

    @Test
    void snapshotsValidateTheirCounts() {
        assertThrows(IllegalArgumentException.class, () -> new BuildSnapshot(true, true, "w", -1, 4, true));
        assertThrows(IllegalArgumentException.class, () -> new BuildSnapshot(true, true, "w", 5, 4, true));
        assertEquals(3, new BuildSnapshot(true, true, "w", 1, 4, true).placedBlocks());
    }
}
