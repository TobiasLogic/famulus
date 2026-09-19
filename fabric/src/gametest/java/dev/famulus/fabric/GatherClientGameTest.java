package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public final class GatherClientGameTest implements FabricClientGameTest {
    private static final int TARGET = 32;
    private static final int GATHER_TIMEOUT_TICKS = 6000;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL))
                .create()) {
            world.getConnection().waitForChunksDownload();
            world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("give @a minecraft:diamond_axe 1");
            world.getServer().runCommand("fill 2 -60 2 9 -60 5 minecraft:oak_log");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> require(oakCount(client) == 0, "Fixture must start with no oak logs"));

            command(context, "/famulus gather minecraft:oak_log 32");
            context.waitFor(client -> isMining(), 200);
            context.takeScreenshot("famulus-gather-running");
            context.waitFor(client -> oakCount(client) >= TARGET && !isMining(), GATHER_TIMEOUT_TICKS);
            context.runOnClient(client -> require(oakCount(client) == TARGET,
                    "Gather must collect the 32 fixture logs and release Baritone"));
            command(context, "/famulus status");
            context.takeScreenshot("famulus-gather-completed");
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitTick();
            context.takeScreenshot("famulus-inventory-32-oak-logs");
            context.setScreen(() -> null);

            BlockPos sentinel = new BlockPos(2, -60, 0);
            world.getServer().runCommand("setblock 2 -60 0 minecraft:oak_log");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            command(context, "/famulus gather minecraft:oak_log 32");
            for (int tick = 0; tick < 40; tick++) {
                context.runOnClient(client -> {
                    require(!isMining(), "An already satisfied request must not start mining");
                    require(oakCount(client) == TARGET, "An already satisfied request must preserve inventory");
                    require(client.level.getBlockState(sentinel).is(Blocks.OAK_LOG),
                            "An already satisfied request must leave the sentinel log untouched");
                });
                context.waitTick();
            }
            context.takeScreenshot("famulus-already-satisfied");

            world.getServer().runCommand("fill 2 -60 2 9 -60 9 minecraft:oak_log");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            command(context, "/famulus gather minecraft:oak_log 96");
            context.waitFor(client -> isMining(), 200);
            command(context, "/famulus status");
            command(context, "/famulus stop");
            context.waitFor(client -> !isMining(), 200);
            for (int tick = 0; tick < 20; tick++) {
                context.runOnClient(client -> require(!isMining(), "Stop must leave Baritone inactive"));
                context.waitTick();
            }
            command(context, "/famulus status");
            context.takeScreenshot("famulus-stopped");

            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("give @a minecraft:diamond_axe 1");
            world.getServer().runCommand("give @a minecraft:diamond_shovel 1");
            world.getServer().runCommand("fill 2 -60 2 9 -60 3 minecraft:oak_log");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> require(oakCount(client) == 0 && dirtCount(client) == 0,
                    "The plan fixture must start empty"));

            command(context, "/famulus queue minecraft:oak_log=8, minecraft:dirt=8");
            context.waitFor(client -> oakCount(client) >= 8, GATHER_TIMEOUT_TICKS);
            context.takeScreenshot("famulus-plan-first-task");

            context.waitFor(client -> dirtCount(client) >= 8, GATHER_TIMEOUT_TICKS);
            context.waitFor(client -> !isMining(), 400);
            context.runOnClient(client -> {
                require(oakCount(client) >= 8, "The plan must keep the first task's items");
                require(dirtCount(client) >= 8, "The plan must complete its second task");
            });
            command(context, "/famulus status");
            context.takeScreenshot("famulus-plan-complete");

            if (System.getenv("OPENROUTER_API_KEY") != null) {
                world.getServer().runCommand("clear @a");
                world.getServer().runCommand("give @a minecraft:diamond_axe 1");
                world.getServer().runCommand("fill 2 -60 2 9 -60 9 minecraft:air");
                context.waitTick();
                world.getConnection().waitForClientboundPackets();
                command(context, "/famulus queue minecraft:oak_log=64");
                context.waitFor(client -> consulted(), 6000);
                context.runOnClient(client -> {
                    require(consulted(), "The policy must be consulted when a task cannot succeed");
                    require(decision().isPresent(), "The decision must be recorded in the log");
                });
                context.waitFor(client -> FamulusClient.agent() != null
                        && !FamulusClient.agent().isRunning(), 12000);
                context.takeScreenshot("famulus-policy-escalation");
                System.out.println("[FamulusPolicy] " + decision().orElse("no decision"));
            }

            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
            world.getServer().runCommand("give @a minecraft:oak_log 64");
            world.getServer().runCommand("setblock 1 -60 0 minecraft:chest");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> require(oakCount(client) == 64, "Deposit fixture needs 64 logs"));

            command(context, "/famulus deposit minecraft:oak_log 32");
            context.waitFor(client -> oakCount(client) <= 32, 1200);
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 600);
            context.runOnClient(client -> require(oakCount(client) == 32,
                    "Depositing 32 of 64 logs must leave exactly 32, found " + oakCount(client)));
            context.takeScreenshot("famulus-deposit-done");

            world.getServer().runCommand("tp @a 40.5 -60 40.5 0 0");
            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:oak_log 64");
            world.getServer().runCommand("item replace entity @a hotbar.1 with minecraft:shulker_box 1");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> shulkerCount(client) >= 1 && oakCount(client) == 64, 400);
            int logsBefore = context.computeOnClient(GatherClientGameTest::oakCount);
            System.out.println("[FamulusFixture] logs=" + logsBefore + " shulker="
                    + context.computeOnClient(GatherClientGameTest::shulkerCount));
            require(logsBefore >= 16, "Need at least 16 logs for the shulker phase");

            command(context, "/famulus deposit minecraft:oak_log 16");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 2400);
            context.takeScreenshot("famulus-shulker-deposit");
            int logsAfter = context.computeOnClient(GatherClientGameTest::oakCount);
            int shulkersAfter = context.computeOnClient(GatherClientGameTest::shulkerCount);
            System.out.println("[FamulusShulker] logsAfter=" + logsAfter
                    + " shulkers=" + shulkersAfter);
            require(logsAfter == logsBefore - 16,
                    "With no chest in reach the agent should have stored exactly 16 logs in a shulker "
                    + "box, went from " + logsBefore + " to " + logsAfter);
            require(shulkersAfter == 1, "The shulker box must be picked back up, found " + shulkersAfter);

            world.getServer().runCommand("item replace entity @a hotbar.2 with minecraft:oak_log 8");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> oakCount(client) >= 8, 400);
            int planksBefore = context.computeOnClient(GatherClientGameTest::plankCount);

            command(context, "/famulus craft minecraft:oak_planks 16");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 1200);
            int planksAfter = context.computeOnClient(GatherClientGameTest::plankCount);
            System.out.println("[FamulusCraft] planks " + planksBefore + " -> " + planksAfter);
            context.takeScreenshot("famulus-craft-done");
            require(planksAfter >= planksBefore + 16,
                    "Crafting 16 planks should have produced at least 16, went from "
                    + planksBefore + " to " + planksAfter);

            world.getServer().runCommand("tp @a 60.5 -60 60.5 0 0");
            world.getServer().runCommand("fill 62 -60 60 66 -60 64 minecraft:stone");
            world.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:diamond_pickaxe 1");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> cobbleCount(client) == 0, 200);

            command(context, "/famulus mine minecraft:stone minecraft:cobblestone 8");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 3600);
            int cobble = context.computeOnClient(GatherClientGameTest::cobbleCount);
            System.out.println("[FamulusMine] cobblestone=" + cobble);
            context.takeScreenshot("famulus-mine-done");
            require(cobble >= 8, "Mining stone should have yielded cobblestone, found " + cobble);

            command(context, "/famulus place minecraft:cobblestone 60 -60 62");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 600);
            boolean placed = context.computeOnClient(client -> client.level
                    .getBlockState(new net.minecraft.core.BlockPos(60, -60, 62))
                    .is(net.minecraft.world.level.block.Blocks.COBBLESTONE));
            System.out.println("[FamulusPlace] placed=" + placed);
            context.takeScreenshot("famulus-place-done");
            require(placed, "The cobblestone should have been placed at 60,-60,62");

            world.getServer().runCommand("setblock 60 -60 61 minecraft:furnace[facing=north]");
            world.getServer().runCommand("item replace entity @a hotbar.4 with minecraft:raw_iron 5");
            world.getServer().runCommand("item replace entity @a inventory.10 with minecraft:coal 3");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            int ingotsBefore = context.computeOnClient(GatherClientGameTest::ingotCount);

            command(context, "/famulus smelt minecraft:iron_ingot 3");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 3600);
            int ingotsAfter = context.computeOnClient(GatherClientGameTest::ingotCount);
            int rawLeft = context.computeOnClient(GatherClientGameTest::rawIronCount);
            System.out.println("[FamulusSmelt] iron ingots " + ingotsBefore + " -> " + ingotsAfter
                    + ", raw iron left " + rawLeft);
            context.takeScreenshot("famulus-smelt-done");
            require(ingotsAfter == ingotsBefore + 3,
                    "Smelting should have produced exactly 3 ingots, went from "
                    + ingotsBefore + " to " + ingotsAfter);
            require(rawLeft == 2,
                    "Only the 3 raw iron asked for should have been consumed, 2 should remain, found "
                    + rawLeft);

            world.getServer().runCommand("item replace entity @a hotbar.4 with minecraft:air");
            world.getServer().runCommand("item replace entity @a inventory.11 with minecraft:dirt 1");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();

            command(context, "/famulus place minecraft:dirt 59 -60 62");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 600);
            boolean fromBackpack = context.computeOnClient(client -> client.level
                    .getBlockState(new net.minecraft.core.BlockPos(59, -60, 62))
                    .is(net.minecraft.world.level.block.Blocks.DIRT));
            System.out.println("[FamulusHotbar] placed from main inventory=" + fromBackpack);
            context.takeScreenshot("famulus-hotbar-place-done");
            require(fromBackpack,
                    "A block held outside the hotbar should be brought to hand and placed");

            world.getServer().runCommand("setblock 61 -60 60 minecraft:lever[face=floor,facing=north]");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            boolean leverBefore = context.computeOnClient(client -> client.level
                    .getBlockState(new net.minecraft.core.BlockPos(61, -60, 60))
                    .getValue(net.minecraft.world.level.block.LeverBlock.POWERED));

            command(context, "/famulus interact minecraft:lever");
            context.waitFor(client -> FamulusClient.agent() != null
                    && !FamulusClient.agent().isRunning(), 600);
            boolean leverAfter = context.computeOnClient(client -> client.level
                    .getBlockState(new net.minecraft.core.BlockPos(61, -60, 60))
                    .getValue(net.minecraft.world.level.block.LeverBlock.POWERED));
            System.out.println("[FamulusInteract] lever " + leverBefore + " -> " + leverAfter);
            context.takeScreenshot("famulus-interact-done");
            require(leverAfter != leverBefore, "Flipping the lever should have changed its state");

            context.setScreen(FamulusClient::createScreen);
            context.waitTick();
            context.takeScreenshot("famulus-screen-agent");
            context.setScreen(() -> FamulusClient.createScreen(1));
            context.waitTick();
            context.takeScreenshot("famulus-screen-chat");
            context.setScreen(() -> FamulusClient.createScreen(2));
            context.waitTick();
            context.takeScreenshot("famulus-screen-build");
            context.setScreen(() -> FamulusClient.createScreen(3));
            context.waitTick();
            context.takeScreenshot("famulus-screen-settings");
            context.setScreen(() -> null);
            context.waitTick();
        }
    }

    private static void command(ClientGameTestContext context, String command) {
        context.getInput().pressKey(options -> options.keyChat);
        context.getInput().typeChars(command);
        context.getInput().holdKeyFor(InputConstants.KEY_RETURN, 0);
        context.waitTick();
    }

    private static int oakCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.OAK_LOG);
    }

    private static int cobbleCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.COBBLESTONE);
    }

    private static int plankCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.OAK_PLANKS);
    }

    private static int shulkerCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.SHULKER_BOX);
    }

    private static int rawIronCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.RAW_IRON);
    }

    private static int ingotCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.IRON_INGOT);
    }

    private static int dirtCount(Minecraft client) {
        return client.player.getInventory().countItem(Items.DIRT);
    }

    private static boolean consulted() {
        return decision().isPresent();
    }

    private static java.util.Optional<String> decision() {
        FamulusAgent agent = FamulusClient.agent();
        if (agent == null) {
            return java.util.Optional.empty();
        }
        return agent.recentLog().stream().filter(line -> line.startsWith("policy chose")).findFirst();
    }

    private static boolean isMining() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
