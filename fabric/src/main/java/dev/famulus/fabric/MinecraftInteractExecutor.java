package dev.famulus.fabric;

import dev.famulus.core.BuildExecutor;
import dev.famulus.core.PlannedTask;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftInteractExecutor implements BuildExecutor {
    public static final int SEARCH_RADIUS = 5;
    public static final int ENTITY_RADIUS = 8;
    private static final double REACH = 3.0;
    private static final int STEP_TIMEOUT_TICKS = 120;

    public enum Step { IDLE, LOCATING, USING, DONE, FAILED }

    private Step step = Step.IDLE;
    private String note = "idle";
    private String targetId;
    private BlockPos target;
    private BlockState before;
    private Entity entity;
    private boolean entityTarget;
    private int ticksInStep;
    private int ticksChanged;

    @Override
    public void start(PlannedTask task) {
        if (!(task instanceof PlannedTask.Interact interact)) {
            throw new IllegalArgumentException("Not an interaction task: " + task.describe());
        }
        targetId = interact.target();
        target = null;
        before = null;
        entity = null;
        entityTarget = false;
        ticksInStep = 0;
        ticksChanged = 0;
        step = Step.LOCATING;
        note = "looking for " + targetId;
    }

    @Override
    public boolean isActive() {
        return step != Step.IDLE && step != Step.DONE && step != Step.FAILED;
    }

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

    public BlockPos target() {
        return target;
    }

    public BlockState before() {
        return before;
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
            case LOCATING -> locate(client);
            case USING -> use(client);
            default -> { }
        }
    }

    public boolean isEntityTarget() {
        return entityTarget;
    }

    public Entity entity() {
        return entity;
    }

    private void locate(Minecraft client) {
        Identifier id = Identifier.parse(targetId);
        EntityType<?> wantedEntity = BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                ? BuiltInRegistries.ENTITY_TYPE.getValue(id) : null;
        if (wantedEntity != null && !BuiltInRegistries.BLOCK.containsKey(id)) {
            locateEntity(client, wantedEntity);
            return;
        }
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            fail("unknown block or entity: " + targetId);
            return;
        }
        Block wanted = BuiltInRegistries.BLOCK.getValue(id);
        BlockPos origin = client.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!client.level.getBlockState(candidate).is(wanted)) {
                        continue;
                    }
                    double distance = candidate.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            fail("no " + targetId + " within " + SEARCH_RADIUS + " blocks");
            return;
        }
        target = best;
        before = client.level.getBlockState(best);
        advance(Step.USING, "using " + targetId);
    }

    private void locateEntity(Minecraft client, EntityType<?> wanted) {
        Vec3 origin = client.player.position();
        Entity best = client.level.getEntities(client.player,
                        client.player.getBoundingBox().inflate(ENTITY_RADIUS),
                        candidate -> candidate.getType() == wanted && candidate.isAlive())
                .stream()
                .min(java.util.Comparator.comparingDouble(
                        candidate -> candidate.position().distanceToSqr(origin)))
                .orElse(null);
        if (best == null) {
            fail("no " + targetId + " within " + ENTITY_RADIUS + " blocks");
            return;
        }
        entity = best;
        entityTarget = true;
        advance(Step.USING, "using " + targetId);
    }

    private void useEntity(Minecraft client) {
        if (entity == null || !entity.isAlive()) {
            fail(targetId + " is gone");
            return;
        }
        if (client.player.position().distanceToSqr(entity.position()) > REACH * REACH) {
            fail(targetId + " is out of reach; travel closer first");
            return;
        }
        if (client.player.getVehicle() != null
                || client.player.containerMenu != client.player.inventoryMenu) {
            advance(Step.DONE, targetId + " responded");
            return;
        }
        client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                entity.getEyePosition());
        if (ticksInStep < 5) {
            return;
        }
        if (ticksInStep % 10 != 0) {
            return;
        }
        client.gameMode.interact(client.player, entity,
                new net.minecraft.world.phys.EntityHitResult(entity), InteractionHand.MAIN_HAND);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private void use(Minecraft client) {
        if (entityTarget) {
            useEntity(client);
            return;
        }
        BlockState now = client.level.getBlockState(target);
        if (!now.equals(before)) {
            if (++ticksChanged >= 5) {
                advance(Step.DONE, targetId + " changed state");
            }
            return;
        }
        ticksChanged = 0;
        client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(target));
        if (ticksInStep < 5) {
            return;
        }
        if (ticksInStep % 10 != 0) {
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target),
                faceToward(client), target, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private Direction faceToward(Minecraft client) {
        Vec3 fromBlock = client.player.position().subtract(Vec3.atCenterOf(target));
        return Direction.getApproximateNearest(fromBlock.x, fromBlock.y, fromBlock.z);
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[interact] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[interact] {} -> FAILED ({})", step, message);
        step = Step.FAILED;
        note = message;
    }
}
