package dev.famulus.fabric;

import dev.famulus.core.ContainerExecutor;
import dev.famulus.core.PlannedTask;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftCraftExecutor implements ContainerExecutor {
    public static final int SEARCH_RADIUS = 4;
    private static final int RESULT_SLOT = 0;
    private static final int STEP_TIMEOUT_TICKS = 100;

    public enum Step { IDLE, LOCATING, OPENING, PLACING, TAKING, CLOSING, DONE, FAILED }

    private Step step = Step.IDLE;
    private String note = "idle";
    private String itemId;
    private int remaining;
    private BlockPos table;
    private boolean usingTable;
    private int ticksInStep;

    @Override
    public void start(PlannedTask task) {
        if (!(task instanceof PlannedTask.Craft craft)) {
            throw new IllegalArgumentException("Not a craft task: " + task.describe());
        }
        itemId = craft.itemId();
        remaining = craft.count();
        table = null;
        usingTable = false;
        ticksInStep = 0;
        step = Step.LOCATING;
        note = "looking for somewhere to craft";
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
        if (client.player != null && usingTable
                && client.player.containerMenu != client.player.inventoryMenu) {
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
        if (++ticksInStep > STEP_TIMEOUT_TICKS) {
            fail(step + " took too long");
            return;
        }
        switch (step) {
            case LOCATING -> locate(client);
            case OPENING -> open(client);
            case PLACING -> place(client);
            case TAKING -> take(client);
            case CLOSING -> close(client);
            default -> { }
        }
    }

    private void locate(Minecraft client) {
        if (findRecipe(client).isEmpty()) {
            fail("no known recipe for " + itemId);
            return;
        }
        BlockPos found = nearestTable(client);
        if (found != null) {
            table = found;
            usingTable = true;
            advance(Step.OPENING, "opening the crafting table");
            return;
        }
        usingTable = false;
        advance(Step.PLACING, "crafting in the inventory grid");
    }

    private BlockPos nearestTable(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!client.level.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
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
        return best;
    }

    public static boolean hasRecipeFor(Minecraft client, String itemId) {
        if (client.player == null || client.level == null) {
            return false;
        }
        var context = SlotDisplayContext.fromLevel(client.level);
        for (var collection : client.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                for (ItemStack result : entry.resultItems(context)) {
                    if (!result.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(result.getItem()).toString().equals(itemId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Optional<RecipeDisplayEntry> findRecipe(Minecraft client) {
        var context = SlotDisplayContext.fromLevel(client.level);
        for (var collection : client.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                for (ItemStack result : entry.resultItems(context)) {
                    if (!result.isEmpty() && matches(result)) {
                        return Optional.of(entry);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void open(Minecraft client) {
        if (client.player.containerMenu != client.player.inventoryMenu) {
            advance(Step.PLACING, "arranging ingredients");
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(table), Direction.UP, table, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private void place(Minecraft client) {
        Optional<RecipeDisplayEntry> recipe = findRecipe(client);
        if (recipe.isEmpty()) {
            fail("no known recipe for " + itemId);
            return;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        client.gameMode.handlePlaceRecipe(menu.containerId, recipe.get().id(), false);
        advance(Step.TAKING, "taking the result");
    }

    private void take(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty()) {
            if (ticksInStep < 10) {
                return;
            }
            fail("the recipe produced nothing, ingredients are probably missing");
            return;
        }
        if (!matches(result)) {
            fail("the crafting grid produced " + result.getItem() + " instead");
            return;
        }
        remaining -= result.getCount();
        client.gameMode.handleContainerInput(menu.containerId, RESULT_SLOT, 0,
                ContainerInput.QUICK_MOVE, client.player);
        if (remaining > 0) {
            advance(Step.PLACING, "crafting more");
        } else {
            advance(Step.CLOSING, "finishing up");
        }
    }

    private boolean matches(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }

    private void close(Minecraft client) {
        if (usingTable && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
            return;
        }
        advance(Step.DONE, "done");
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[craft] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[craft] {} -> FAILED ({})", step, message);
        step = Step.FAILED;
        note = message;
    }
}
