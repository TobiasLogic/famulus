package dev.famulus.fabric;

import dev.famulus.core.ContainerExecutor;
import dev.famulus.core.PlannedTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftContainerExecutor implements ContainerExecutor {
    public static final int SEARCH_RADIUS = 4;
    private static final int STEP_TIMEOUT_TICKS = 100;
    private static final int RECLAIM_TIMEOUT_TICKS = 300;

    public enum Step {
        IDLE, LOCATING, PLACING, OPENING, TRANSFERRING, CLOSING, RECLAIMING, DONE, FAILED
    }

    private Step step = Step.IDLE;
    private String note = "idle";
    private String itemId;
    private int remaining;
    private boolean withdrawing;
    private BlockPos container;
    private boolean placedShulker;
    private int ticksInStep;
    private int ticksSinceBroken;

    @Override
    public void start(PlannedTask task) {
        if (task instanceof PlannedTask.Deposit deposit) {
            withdrawing = false;
            itemId = deposit.itemId();
            remaining = deposit.count();
        } else if (task instanceof PlannedTask.Withdraw withdraw) {
            withdrawing = true;
            itemId = withdraw.itemId();
            remaining = withdraw.count();
        } else {
            throw new IllegalArgumentException("Not a transfer task: " + task.describe());
        }
        container = null;
        placedShulker = false;
        ticksInStep = 0;
        ticksSinceBroken = 0;
        step = Step.LOCATING;
        note = "looking for a container";
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
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
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
        int budget = step == Step.RECLAIMING ? RECLAIM_TIMEOUT_TICKS : STEP_TIMEOUT_TICKS;
        if (++ticksInStep > budget) {
            fail(step + " took too long");
            return;
        }
        switch (step) {
            case LOCATING -> locate(client);
            case PLACING -> place(client);
            case OPENING -> open(client);
            case TRANSFERRING -> transfer(client);
            case CLOSING -> close(client);
            case RECLAIMING -> reclaim(client);
            default -> { }
        }
    }

    private void locate(Minecraft client) {
        BlockPos found = nearestContainer(client);
        if (found != null) {
            container = found;
            advance(Step.OPENING, "opening the container");
            return;
        }
        if (withdrawing) {
            fail("no container within " + SEARCH_RADIUS + " blocks");
            return;
        }
        if (selectShulker(client)) {
            advance(Step.PLACING, "placing a shulker box");
            return;
        }
        fail("no container nearby and no shulker box to place");
    }

    private BlockPos nearestContainer(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    BlockEntity entity = client.level.getBlockEntity(candidate);
                    if (entity instanceof Container) {
                        found.add(candidate);
                    }
                }
            }
        }
        return found.stream()
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)))
                .orElse(null);
    }

    private boolean selectShulker(Minecraft client) {
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && net.minecraft.world.level.block.Block.byItem(stack.getItem())
                    instanceof ShulkerBoxBlock) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    private void place(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockPos target = origin.relative(facing);
            if (!client.level.getBlockState(target).isAir()) {
                continue;
            }
            if (client.level.getBlockState(target.below()).isAir()) {
                continue;
            }
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(target.below()).add(0, 0.5, 0), Direction.UP, target.below(), false);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            container = target;
            placedShulker = true;
            advance(Step.OPENING, "opening the shulker box");
            return;
        }
        fail("no free space beside the player to place a shulker box");
    }

    private void open(Minecraft client) {
        if (client.player.containerMenu != client.player.inventoryMenu) {
            advance(Step.TRANSFERRING, "moving items");
            return;
        }
        if (client.level.getBlockEntity(container) == null && !placedShulker) {
            fail("the container disappeared");
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(container), Direction.UP, container, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private void transfer(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu) {
            advance(placedShulker ? Step.RECLAIMING : Step.DONE, "container closed early");
            return;
        }
        int containerSlots = menu.slots.size() - 36;
        if (containerSlots <= 0) {
            fail("the open screen is not a storage container");
            return;
        }
        for (int index = 0; index < menu.slots.size() && remaining > 0; index++) {
            boolean playerSide = index >= containerSlots;
            if (playerSide == withdrawing) {
                continue;
            }
            ItemStack stack = menu.getSlot(index).getItem();
            if (stack.isEmpty() || !matches(stack)) {
                continue;
            }
            if (stack.getCount() <= remaining) {
                click(client, menu, index, 0, ContainerInput.QUICK_MOVE);
                remaining -= stack.getCount();
            } else {
                movePartOfStack(client, menu, index, containerSlots);
            }
        }
        if (remaining <= 0) {
            advance(Step.CLOSING, "closing the container");
        }
    }

    private void movePartOfStack(Minecraft client, AbstractContainerMenu menu,
                                 int sourceIndex, int containerSlots) {
        int destination = destinationSlot(menu, containerSlots);
        if (destination < 0) {
            fail("no room left for a partial stack");
            return;
        }
        click(client, menu, sourceIndex, 0, ContainerInput.PICKUP);
        for (int placed = 0; placed < remaining; placed++) {
            click(client, menu, destination, 1, ContainerInput.PICKUP);
        }
        click(client, menu, sourceIndex, 0, ContainerInput.PICKUP);
        remaining = 0;
    }

    private int destinationSlot(AbstractContainerMenu menu, int containerSlots) {
        int from = withdrawing ? containerSlots : 0;
        int to = withdrawing ? menu.slots.size() : containerSlots;
        for (int index = from; index < to; index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (stack.isEmpty()) {
                return index;
            }
            if (matches(stack) && stack.getCount() < stack.getMaxStackSize()) {
                return index;
            }
        }
        return -1;
    }

    private void click(Minecraft client, AbstractContainerMenu menu, int slot, int button,
                       ContainerInput input) {
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, client.player);
    }

    private boolean matches(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }

    private void close(Minecraft client) {
        if (client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
            return;
        }
        advance(placedShulker ? Step.RECLAIMING : Step.DONE,
                placedShulker ? "collecting the shulker box" : "done");
    }

    private void reclaim(Minecraft client) {
        if (!client.level.getBlockState(container).isAir()) {
            client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(container));
            client.gameMode.continueDestroyBlock(container, faceToward(client));
            client.player.swing(InteractionHand.MAIN_HAND);
            ticksSinceBroken = 0;
            return;
        }
        client.gameMode.stopDestroyBlock();
        if (ticksSinceBroken < 60) {
            ticksSinceBroken++;
            return;
        }
        advance(Step.DONE, "done");
    }

    private Direction faceToward(Minecraft client) {
        Vec3 fromBlock = client.player.position().subtract(Vec3.atCenterOf(container));
        return Direction.getApproximateNearest(fromBlock.x, fromBlock.y, fromBlock.z);
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[container] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[container] {} -> FAILED ({})", step, message);
        step = Step.FAILED;
        note = message;
    }
}
