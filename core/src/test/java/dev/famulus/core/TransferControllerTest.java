package dev.famulus.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransferControllerTest {
    private static final GatherConfig CONFIG = new GatherConfig(5_000, 20_000, 500, 3);
    private static final PlannedTask.Deposit STORE = new PlannedTask.Deposit("d1", "minecraft:oak_log", 32);
    private static final PlannedTask.Withdraw TAKE = new PlannedTask.Withdraw("w1", "minecraft:oak_log", 32);
    private static final PlannedTask.Travel GO = new PlannedTask.Travel("t1", 100, 64, -40);

    private static DepositSnapshot holding(int count) {
        return new DepositSnapshot(true, true, "session:overworld", count, true);
    }

    private static TravelSnapshot away(double distance) {
        return new TravelSnapshot(true, true, "session:overworld", distance);
    }

    private static final class FakeContainer implements ContainerExecutor {
        boolean active;
        int starts;
        int cancels;
        RuntimeException startFailure;

        @Override
        public void start(PlannedTask task) {
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

        @Override
        public String lastStep() {
            return "moving items";
        }
    }

    private static final class FakeTravel implements TravelExecutor {
        boolean active;
        int starts;
        int cancels;

        @Override
        public void start(PlannedTask.Travel task) {
            starts++;
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
    void depositSucceedsWhenTheItemsHaveActuallyLeftTheInventory() {
        FakeContainer container = new FakeContainer();
        DepositController controller = new DepositController(container, CONFIG);
        controller.start(STORE, holding(64), 0);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(holding(50), 1_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(holding(32), 2_000);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(32, controller.result().currentCount());
        assertEquals(1, container.cancels);
    }

    @Test
    void withdrawSucceedsWhenTheItemsHaveArrived() {
        FakeContainer container = new FakeContainer();
        DepositController controller = new DepositController(container, CONFIG);
        controller.start(TAKE, holding(0), 0);
        controller.tick(holding(10), 1_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(holding(32), 2_000);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
    }

    @Test
    void storingMoreThanIsHeldIsRefusedRatherThanAttempted() {
        FakeContainer container = new FakeContainer();
        DepositController controller = new DepositController(container, CONFIG);
        controller.start(STORE, holding(10), 0);
        assertEquals(TaskStatus.RESOURCE_MISSING, controller.result().status());
        assertEquals(0, container.starts);
        assertTrue(controller.result().message().contains("Holding 10"));
    }

    @Test
    void noStorageInReachIsReportedBeforeAnythingIsOpened() {
        FakeContainer container = new FakeContainer();
        DepositController controller = new DepositController(container, CONFIG);
        controller.start(STORE, new DepositSnapshot(true, true, "w", 64, false), 0);
        assertEquals(TaskStatus.RESOURCE_MISSING, controller.result().status());
        assertEquals(0, container.starts);
    }

    @Test
    void countsAreAmountsToMoveNotTotalsToEndUpWith() {
        FakeContainer container = new FakeContainer();
        DepositController takeFour = new DepositController(container, CONFIG);
        takeFour.start(new PlannedTask.Withdraw("w", "minecraft:dirt", 4), holding(99), 0);
        assertEquals(TaskStatus.RUNNING, takeFour.result().status());
        takeFour.tick(holding(103), 500);
        assertEquals(TaskStatus.SUCCESS, takeFour.result().status());

        DepositController storeAll = new DepositController(new FakeContainer(), CONFIG);
        storeAll.start(new PlannedTask.Deposit("d", "minecraft:dirt", 20), holding(20), 0);
        assertEquals(TaskStatus.RUNNING, storeAll.result().status());
        storeAll.tick(holding(0), 500);
        assertEquals(TaskStatus.SUCCESS, storeAll.result().status());
    }

    @Test
    void theHandlerGoingIdleIsNotSuccess() {
        FakeContainer container = new FakeContainer();
        DepositController controller = new DepositController(container, CONFIG);
        controller.start(STORE, holding(64), 0);
        container.active = false;
        controller.tick(holding(60), 1_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(holding(60), 4_000);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertTrue(controller.result().message().contains("after moving 4"));
    }

    @Test
    void aStalledTransferExhaustsAttemptsAndRequestsAReplan() {
        FakeContainer container = new FakeContainer();
        DepositController controller = new DepositController(container, new GatherConfig(5_000, 120_000, 500, 3));
        controller.start(STORE, holding(64), 0);
        long now = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            now += 6_000;
            controller.tick(holding(64), now);
            now += 1_000;
            controller.tick(holding(64), now);
        }
        assertEquals(TaskStatus.REPLAN_REQUIRED, controller.result().status());
    }

    @Test
    void transfersStopOnDisconnectDeathAndWorldChange() {
        DepositController disconnected = new DepositController(new FakeContainer(), CONFIG);
        disconnected.start(STORE, holding(64), 0);
        disconnected.tick(new DepositSnapshot(false, true, "w", 64, true), 1);
        assertEquals(TaskStatus.BLOCKED, disconnected.result().status());

        DepositController died = new DepositController(new FakeContainer(), CONFIG);
        died.start(STORE, holding(64), 0);
        died.tick(new DepositSnapshot(true, false, "session:overworld", 64, true), 1);
        assertEquals(TaskStatus.FAILED, died.result().status());

        DepositController moved = new DepositController(new FakeContainer(), CONFIG);
        moved.start(STORE, holding(64), 0);
        moved.tick(new DepositSnapshot(true, true, "session:nether", 64, true), 1);
        assertEquals(TaskStatus.WORLD_CHANGED, moved.result().status());
    }

    @Test
    void aFailedTransferStartIsCleanedUpAndRetried() {
        FakeContainer container = new FakeContainer();
        container.startFailure = new IllegalStateException("screen already open");
        DepositController controller = new DepositController(container, CONFIG);
        controller.start(STORE, holding(64), 0);
        assertEquals(TaskStatus.RECOVERING, controller.result().status());
        assertEquals(1, container.cancels);
    }

    @Test
    void onlyTransferTasksAreAccepted() {
        DepositController controller = new DepositController(new FakeContainer(), CONFIG);
        assertThrows(IllegalArgumentException.class,
                () -> controller.start(new PlannedTask.Gather("g", "minecraft:dirt", 1), holding(1), 0));
    }

    @Test
    void travelSucceedsOnceCloseEnough() {
        FakeTravel travel = new FakeTravel();
        TravelController controller = new TravelController(travel, CONFIG);
        controller.start(GO, away(120), 0);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(away(40), 1_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(away(2), 2_000);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(1, travel.cancels);
    }

    @Test
    void travelToWhereYouAlreadyStandDoesNotPath() {
        FakeTravel travel = new FakeTravel();
        TravelController controller = new TravelController(travel, CONFIG);
        controller.start(GO, away(1), 0);
        assertEquals(TaskStatus.SUCCESS, controller.result().status());
        assertEquals(0, travel.starts);
    }

    @Test
    void travelThatStopsShortReportsPathNotFoundAfterItsAttempts() {
        FakeTravel travel = new FakeTravel();
        TravelController controller = new TravelController(travel, new GatherConfig(5_000, 120_000, 500, 3));
        controller.start(GO, away(200), 0);
        long now = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            now += 6_000;
            controller.tick(away(200), now);
            now += 1_000;
            controller.tick(away(200), now);
        }
        assertEquals(TaskStatus.PATH_NOT_FOUND, controller.result().status());
    }

    @Test
    void onlyRealProgressExtendsTheTravelDeadline() {
        FakeTravel travel = new FakeTravel();
        TravelController controller = new TravelController(travel, CONFIG);
        controller.start(GO, away(200), 0);
        controller.tick(away(199.5), 4_000);
        assertEquals(TaskStatus.RUNNING, controller.result().status());
        controller.tick(away(199.5), 9_500);
        assertEquals(TaskStatus.RECOVERING, controller.result().status(),
                "Drifting half a block is not progress");
    }

    @Test
    void snapshotsValidateTheirInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TravelSnapshot(true, true, "w", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DepositSnapshot(true, true, "w", -1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedTask.Travel("t", 0, 9000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedTask.Withdraw("w", "oak_log", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedTask.Deposit("d", "minecraft:dirt", 0));
    }
}
