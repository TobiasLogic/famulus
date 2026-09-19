package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IBaritoneProcess;
import dev.famulus.core.GatherExecutor;
import dev.famulus.core.GatherTask;

public final class BaritoneGatherExecutor implements GatherExecutor {
    private boolean ownsMining;

    private IBaritone baritone() { return BaritoneAPI.getProvider().getPrimaryBaritone(); }

    public boolean isBusy() {
        IBaritone api = baritone();
        return api.getMineProcess().isActive() || otherProcessActive(api)
                || api.getPathingBehavior().isPathing()
                || api.getPathingBehavior().getInProgress().isPresent();
    }

    private boolean otherProcessActive(IBaritone api) {
        IBaritoneProcess[] processes = {
                api.getCustomGoalProcess(), api.getBuilderProcess(), api.getFollowProcess(),
                api.getExploreProcess(), api.getFarmProcess(), api.getGetToBlockProcess(),
                api.getElytraProcess()
        };
        for (IBaritoneProcess process : processes) {
            if (process != null && process.isActive()) return true;
        }
        return false;
    }

    @Override
    public void start(GatherTask task) {
        if (isBusy()) throw new IllegalStateException("Baritone is busy; stop its current task before gathering.");

        ownsMining = true;
        baritone().getMineProcess().mineByName(task.targetCount(), task.blockId().split(","));
    }

    @Override
    public boolean isActive() { return ownsMining && baritone().getMineProcess().isActive(); }

    @Override
    public void cancel() {
        if (!ownsMining) return;
        IBaritone api = baritone();
        boolean anotherProcessStarted = otherProcessActive(api);
        api.getMineProcess().cancel();

        if (!anotherProcessStarted) api.getPathingBehavior().cancelEverything();
        ownsMining = false;
    }
}
