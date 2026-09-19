package dev.famulus.fabric;

import dev.famulus.core.BuildExecutor;
import dev.famulus.core.PlannedTask;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

public final class MinecraftEatExecutor implements BuildExecutor {
    private static final int STEP_TIMEOUT_TICKS = 200;

    public enum Step { IDLE, SELECTING, EATING, DONE, FAILED }

    private Step step = Step.IDLE;
    private String note = "idle";
    private int target;
    private int ticksInStep;
    private int lastFood;

    @Override
    public void start(PlannedTask task) {
        if (!(task instanceof PlannedTask.Eat eat)) {
            throw new IllegalArgumentException("Not an eating task: " + task.describe());
        }
        target = eat.targetFood();
        ticksInStep = 0;
        lastFood = -1;
        step = Step.SELECTING;
        note = "looking for something to eat";
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
        release();
        step = Step.IDLE;
        note = "cancelled";
    }

    public Step step() {
        return step;
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }

    public static int nutritionOf(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    public static boolean hasFood(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (isFood(stack)) {
                return true;
            }
        }
        return false;
    }

    public void tick(Minecraft client) {
        if (!isActive() || client.player == null || client.level == null) {
            return;
        }
        if (++ticksInStep > STEP_TIMEOUT_TICKS) {
            release();
            fail(step + " took too long");
            return;
        }
        int food = client.player.getFoodData().getFoodLevel();
        if (food >= target) {
            release();
            advance(Step.DONE, "food is " + food + "/20");
            return;
        }
        switch (step) {
            case SELECTING -> select(client);
            case EATING -> chew(client, food);
            default -> { }
        }
    }

    private void select(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        ItemStack best = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isFood(stack) && nutritionOf(stack) > nutritionOf(best)) {
                best = stack;
            }
        }
        if (best.isEmpty()) {
            fail("nothing edible in the inventory");
            return;
        }
        ItemStack chosen = best;
        if (Hotbar.select(client, stack -> stack == chosen) == Hotbar.Result.ABSENT) {
            fail("could not bring " + BuiltInRegistries.ITEM.getKey(best.getItem()) + " to hand");
            return;
        }
        advance(Step.EATING, "eating " + BuiltInRegistries.ITEM.getKey(best.getItem()));
    }

    private void chew(Minecraft client, int food) {
        if (food != lastFood) {
            lastFood = food;
            ticksInStep = 0;
        }
        if (!isFood(client.player.getMainHandItem())) {
            release();
            advance(Step.SELECTING, "reaching for more");
            return;
        }
        client.options.keyUse.setDown(true);
    }

    private void release() {
        Minecraft client = Minecraft.getInstance();
        if (client.options != null) {
            client.options.keyUse.setDown(false);
        }
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[eat] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[eat] {} -> FAILED ({})", step, message);
        release();
        step = Step.FAILED;
        note = message;
    }
}
