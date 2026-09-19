package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import dev.famulus.core.PlannedTask;
import dev.famulus.core.TravelExecutor;

public final class BaritoneTravelExecutor implements TravelExecutor {
    private boolean ownsTravel;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Override
    public void start(PlannedTask.Travel task) {
        IBaritone api = baritone();
        if (api.getMineProcess().isActive() || api.getBuilderProcess().isActive()) {
            throw new IllegalStateException("Baritone is busy; stop its current task before travelling.");
        }
        ownsTravel = true;
        api.getCustomGoalProcess().setGoalAndPath(new GoalBlock(task.x(), task.y(), task.z()));
    }

    @Override
    public boolean isActive() {
        return ownsTravel && baritone().getCustomGoalProcess().isActive();
    }

    @Override
    public void cancel() {
        if (!ownsTravel) {
            return;
        }
        IBaritone api = baritone();
        boolean anotherProcessStarted = api.getMineProcess().isActive()
                || api.getBuilderProcess().isActive() || api.getExploreProcess().isActive();
        api.getCustomGoalProcess().onLostControl();
        if (!anotherProcessStarted) {
            api.getPathingBehavior().cancelEverything();
        }
        ownsTravel = false;
    }
}
