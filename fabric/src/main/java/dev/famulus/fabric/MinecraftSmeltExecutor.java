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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftSmeltExecutor implements ContainerExecutor {
    public static final int SEARCH_RADIUS = 4;
    private static final int STEP_TIMEOUT_TICKS = 100;
    private static final int SMELT_TIMEOUT_TICKS = 2400;
    private static final int IDLE_FURNACE_TICKS = 200;

    public enum Step { IDLE, LOCATING, OPENING, LOADING, SMELTING, COLLECTING, CLOSING, DONE, FAILED }

    private Step step = Step.IDLE;
    private String note = "idle";
    private String itemId;
    private int wanted;
    private List<String> inputIds = List.of();
    private BlockPos furnace;
    private int ticksInStep;
    private int ticksWithoutProgress;
    private int collected;
    private int lastResultCount;

    @Override
    public void start(PlannedTask task) {
        if (!(task instanceof PlannedTask.Smelt smelt)) {
            throw new IllegalArgumentException("Not a smelting task: " + task.describe());
        }
        itemId = smelt.itemId();
        wanted = smelt.count();
        inputIds = acceptableInputs(smelt);
        furnace = null;
        ticksInStep = 0;
        ticksWithoutProgress = 0;
        collected = 0;
        lastResultCount = 0;
        step = Step.LOCATING;
        note = "looking for a furnace";
    }

    private static List<String> acceptableInputs(PlannedTask.Smelt smelt) {
        List<String> inputs = new ArrayList<>();
        inputs.add(smelt.inputId());
        if (SmeltCatalog.supports(smelt.itemId())) {
            for (String known : SmeltCatalog.inputsFor(smelt.itemId())) {
                if (!inputs.contains(known)) {
                    inputs.add(known);
                }
            }
        }
        return List.copyOf(inputs);
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

    public BlockPos furnace() {
        return furnace;
    }

    public void tick(Minecraft client) {
        if (!isActive() || client.player == null || client.level == null) {
            return;
        }
        int budget = step == Step.SMELTING ? SMELT_TIMEOUT_TICKS : STEP_TIMEOUT_TICKS;
        if (++ticksInStep > budget) {
            fail(step + " took too long");
            return;
        }
        switch (step) {
            case LOCATING -> locate(client);
            case OPENING -> open(client);
            case LOADING -> load(client);
            case SMELTING -> smelt(client);
            case COLLECTING -> collect(client);
            case CLOSING -> close(client);
            default -> { }
        }
    }

    private void locate(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (client.level.getBlockEntity(candidate) instanceof AbstractFurnaceBlockEntity) {
                        found.add(candidate.immutable());
                    }
                }
            }
        }
        furnace = found.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(origin))).orElse(null);
        if (furnace == null) {
            fail("no furnace within " + SEARCH_RADIUS + " blocks; place one first");
            return;
        }
        advance(Step.OPENING, "opening the furnace");
    }

    private void open(Minecraft client) {
        if (client.player.containerMenu instanceof AbstractFurnaceMenu) {
            advance(Step.LOADING, "loading the furnace");
            return;
        }
        if (client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
            return;
        }
        if (!(client.level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity)) {
            fail("the furnace disappeared");
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(furnace), Direction.UP, furnace, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private void load(Minecraft client) {
        if (!(client.player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            advance(Step.OPENING, "the furnace closed, reopening");
            return;
        }
        if (menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()) {
            int source = findInPlayerSide(menu, this::isInput);
            if (source < 0) {
                if (menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty() && collected == 0) {
                    fail("nothing to smelt into " + itemId + " in the inventory");
                    return;
                }
                advance(Step.SMELTING, "waiting for the furnace");
                return;
            }
            loadInput(client, menu, source);
            return;
        }
        if (!menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty() || menu.isLit()) {
            lastResultCount = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount();
            ticksWithoutProgress = 0;
            advance(Step.SMELTING, "smelting");
            return;
        }
        int fuel = findInPlayerSide(menu, stack -> client.level.fuelValues().isFuel(stack)
                && !isInput(stack));
        if (fuel < 0) {
            fail("no fuel in the inventory; coal, charcoal or planks would do");
            return;
        }
        click(client, menu, fuel, ContainerInput.PICKUP);
        click(client, menu, AbstractFurnaceMenu.FUEL_SLOT, ContainerInput.PICKUP);
    }

    private void loadInput(Minecraft client, AbstractFurnaceMenu menu, int source) {
        int outstanding = Math.max(0, wanted - collected);
        int available = menu.getSlot(source).getItem().getCount();
        click(client, menu, source, ContainerInput.PICKUP);
        if (available <= outstanding) {
            click(client, menu, AbstractFurnaceMenu.INGREDIENT_SLOT, ContainerInput.PICKUP);
            return;
        }
        for (int placed = 0; placed < outstanding; placed++) {
            clickButton(client, menu, AbstractFurnaceMenu.INGREDIENT_SLOT, 1, ContainerInput.PICKUP);
        }
        click(client, menu, source, ContainerInput.PICKUP);
    }

    private void smelt(Minecraft client) {
        if (!(client.player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            advance(Step.OPENING, "the furnace closed, reopening");
            return;
        }
        ItemStack result = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (result.getCount() != lastResultCount) {
            lastResultCount = result.getCount();
            ticksWithoutProgress = 0;
        } else {
            ticksWithoutProgress++;
        }
        if (!result.isEmpty() && (ticksWithoutProgress > IDLE_FURNACE_TICKS
                || menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty())) {
            advance(Step.COLLECTING, "taking " + result.getCount() + " out");
            return;
        }
        if (ticksWithoutProgress > IDLE_FURNACE_TICKS) {
            if (result.isEmpty() && !menu.isLit()
                    && menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
                advance(Step.LOADING, "the furnace went out, reloading");
                return;
            }
            fail("the furnace made no progress for " + (IDLE_FURNACE_TICKS / 20) + "s");
        }
    }

    private void collect(Minecraft client) {
        if (!(client.player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            advance(Step.OPENING, "the furnace closed, reopening");
            return;
        }
        ItemStack result = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (result.isEmpty()) {
            advance(menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()
                    ? Step.LOADING : Step.SMELTING, "collected " + collected);
            return;
        }
        collected += result.getCount();
        click(client, menu, AbstractFurnaceMenu.RESULT_SLOT, ContainerInput.QUICK_MOVE);
    }

    private void close(Minecraft client) {
        if (client.player.containerMenu == client.player.inventoryMenu) {
            advance(Step.DONE, "smelted " + collected + " " + itemId);
            return;
        }
        client.player.closeContainer();
    }

    public void finish() {
        if (isActive()) {
            advance(Step.CLOSING, "target reached");
        }
    }

    private boolean isInput(ItemStack stack) {
        return !stack.isEmpty()
                && inputIds.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static int findInPlayerSide(AbstractFurnaceMenu menu,
                                        java.util.function.Predicate<ItemStack> wanted) {
        for (int index = AbstractFurnaceMenu.SLOT_COUNT; index < menu.slots.size(); index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (!stack.isEmpty() && wanted.test(stack)) {
                return index;
            }
        }
        return -1;
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot,
                              ContainerInput input) {
        clickButton(client, menu, slot, 0, input);
    }

    private static void clickButton(Minecraft client, AbstractContainerMenu menu, int slot,
                                    int button, ContainerInput input) {
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, client.player);
    }

    private void advance(Step next, String message) {
        FamulusClient.LOGGER.info("[smelt] {} -> {} ({})", step, next, message);
        step = next;
        note = message;
        ticksInStep = 0;
    }

    private void fail(String message) {
        FamulusClient.LOGGER.info("[smelt] {} -> FAILED ({})", step, message);
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
        step = Step.FAILED;
        note = message;
    }
}
