package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;

public final class BaritoneExplorer {
    private boolean ownsExploration;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public void start(Minecraft client) {
        if (client.player == null) {
            throw new IllegalStateException("Cannot explore without a player");
        }
        int centerX = client.player.blockPosition().getX();
        int centerZ = client.player.blockPosition().getZ();
        ownsExploration = true;
        baritone().getExploreProcess().explore(centerX, centerZ);
    }

    public boolean isActive() {
        return ownsExploration && baritone().getExploreProcess().isActive();
    }

    public void cancel() {
        if (!ownsExploration) {
            return;
        }
        IBaritone api = baritone();
        api.getExploreProcess().onLostControl();

        if (!api.getMineProcess().isActive() && !api.getBuilderProcess().isActive()
                && !api.getCustomGoalProcess().isActive()) {
            api.getPathingBehavior().cancelEverything();
        }
        ownsExploration = false;
    }
}
