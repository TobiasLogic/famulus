package dev.famulus.fabric;

import dev.famulus.core.ContainerExecutor;
import dev.famulus.core.PlannedTask;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftPlaceExecutor implements ContainerExecutor {
    private static final int STEP_TIMEOUT_TICKS = 200;
    private static final double REACH = 4.5;

    public enum Step { IDLE, SELECTING, PLACING, DONE, FAILED }

    private Step step = Step.IDLE;
    private String note = "idle";
    private String itemId;
    private BlockPos target;
    private int ticksInStep;

    @Override
    public void start(PlannedTask task) {
        if (!(task instanceof PlannedTask.PlaceBlock place)) {
            throw new IllegalArgumentException("Not a placement task: " + task.describe());
        }
        itemId = place.itemId();
        target = new BlockPos(place.x(), place.y(), place.z());
        ticksInStep = 0;
        step = Step.SELECTING;
        note = "selecting " + itemId;
    }

    @Override
    public boolean isActive() {
        return step != Step.IDLE && step != Step.DONE && step != Step.FAILED;
    }

    @Override
    public String lastStep() {
        return note;
    }

    @Override
    public void cancel() {
        step = Step.IDLE;
        note = "cancelled";
    }

    public Step step() {
        return step;
    }

    public void tick(Minecraft client) {
        if (!isActive() || client.player == null || client.level == null) {
            return;
        }
        if (++ticksInStep > STEP_TIMEOUT_TICKS) {
            fail(step + " took too long");
            return;
        }
        switch (step) {
            case SELECTING -> select(client);
            case PLACING -> place(client);
            default -> { }
        }
    }

    private void select(Minecraft client) {
        if (!client.level.getBlockState(target).isAir()) {
            advance(Step.DONE, "something is already there");
            return;
        }
        if (client.player.position().distanceToSqr(Vec3.atCenterOf(target)) > REACH * REACH) {
            fail("the spot is out of reach; travel closer first");
            return;
        }
        switch (Hotbar.select(client, itemId)) {
            case SELECTED -> advance(Step.PLACING, "placing " + itemId);
            case MOVING -> note = "moving " + itemId + " to the hotbar";
            case ABSENT -> fail("no " + itemId + " in the inventory");
        }
    }

    private void place(Minecraft client) {
        if (!client.level.getBlockState(target).isAir()) {
            advance(Step.DONE, "placed");
            return;
        }
        Direction against = supportingFace(client);
        if (against == null) {
            fail("nothing solid to place against at " + target.toShortString());
            return;
        }
        BlockPos neighbour = target.relative(against);
        client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(target));
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(neighbour), against.getOpposite(), neighbour, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private Direction supportingFace(Minecraft client) {
        for (Direction direction : Direction.values()) {
            if (!client.level.getBlockState(target.relative(direction)).isAir()) {
                return direction;
            }
        }
        return null;
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[place] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[place] {} -> FAILED ({})", step, message);
        step = Step.FAILED;
        note = message;
    }
}
