package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import dev.famulus.core.BuildExecutor;
import dev.famulus.core.PlannedTask;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MinecraftAttackExecutor implements BuildExecutor {
    public static final int SEARCH_RADIUS = 24;
    private static final double REACH = 3.0;
    private static final int APPROACH_TIMEOUT_TICKS = 400;
    private static final int STRIKE_TIMEOUT_TICKS = 600;

    public enum Step { IDLE, LOCATING, APPROACHING, STRIKING, DONE, FAILED }

    private Step step = Step.IDLE;
    private String note = "idle";
    private String targetId;
    private int wanted;
    private int killed;
    private Entity target;
    private int ticksInStep;
    private boolean pathing;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Override
    public void start(PlannedTask task) {
        if (!(task instanceof PlannedTask.Attack attack)) {
            throw new IllegalArgumentException("Not an attack task: " + task.describe());
        }
        targetId = attack.target();
        wanted = attack.count();
        killed = 0;
        target = null;
        ticksInStep = 0;
        pathing = false;
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
        stopPathing();
        step = Step.IDLE;
        note = "cancelled";
    }

    public Step step() {
        return step;
    }

    public int killed() {
        return killed;
    }

    public void tick(Minecraft client) {
        if (!isActive() || client.player == null || client.level == null) {
            return;
        }
        int budget = step == Step.APPROACHING ? APPROACH_TIMEOUT_TICKS : STRIKE_TIMEOUT_TICKS;
        if (++ticksInStep > budget) {
            stopPathing();
            fail(step + " took too long after " + killed + " of " + wanted);
            return;
        }
        switch (step) {
            case LOCATING -> locate(client);
            case APPROACHING -> approach(client);
            case STRIKING -> strike(client);
            default -> { }
        }
    }

    private void locate(Minecraft client) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(targetId));
        if (type == null) {
            fail("unknown entity: " + targetId);
            return;
        }
        Vec3 origin = client.player.position();
        AABB box = client.player.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Entity> found = client.level.getEntities(client.player, box,
                entity -> entity.getType() == type && entity.isAlive());
        target = found.stream()
                .min(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(origin)))
                .orElse(null);
        if (target == null) {
            fail(killed > 0
                    ? "killed " + killed + " but no more " + targetId + " within " + SEARCH_RADIUS
                    : "no " + targetId + " within " + SEARCH_RADIUS + " blocks");
            return;
        }
        equipWeapon(client);
        advance(Step.APPROACHING, "approaching " + targetId);
    }

    private void approach(Minecraft client) {
        if (target == null || !target.isAlive()) {
            stopPathing();
            advance(Step.LOCATING, "target gone, looking again");
            return;
        }
        if (client.player.position().distanceToSqr(target.position()) <= REACH * REACH) {
            stopPathing();
            advance(Step.STRIKING, "striking " + targetId);
            return;
        }
        if (!pathing) {
            pathing = true;
            baritone().getCustomGoalProcess().setGoalAndPath(
                    new GoalNear(target.blockPosition(), 2));
        }
    }

    private void strike(Minecraft client) {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            killed++;
            target = null;
            if (killed >= wanted) {
                advance(Step.DONE, "killed " + killed + " " + targetId);
                return;
            }
            advance(Step.LOCATING, "killed " + killed + " of " + wanted);
            return;
        }
        if (client.player.position().distanceToSqr(target.position()) > REACH * REACH) {
            advance(Step.APPROACHING, "it moved away");
            return;
        }
        client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        if (client.player.getAttackStrengthScale(0) < 0.95f) {
            return;
        }
        client.gameMode.attack(client.player, target);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private void equipWeapon(Minecraft client) {
        Hotbar.select(client, stack -> !stack.isEmpty() && damage(stack) > 1.0);
    }

    private static double damage(ItemStack stack) {
        var weapon = stack.get(net.minecraft.core.component.DataComponents.WEAPON);
        return weapon == null ? 0 : 2.0;
    }

    private void stopPathing() {
        if (!pathing) {
            return;
        }
        pathing = false;
        IBaritone api = baritone();
        api.getCustomGoalProcess().onLostControl();
        api.getPathingBehavior().cancelEverything();
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[attack] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[attack] {} -> FAILED ({})", step, message);
        stopPathing();
        step = Step.FAILED;
        note = message;
    }
}
