package dev.famulus.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.item.Items;

@SuppressWarnings("UnstableApiUsage")
public final class ExploreProbeGameTest implements FabricClientGameTest {
    private static final int GROUND_Y = -61;

    private static final int SAND_DISTANCE = 260;
    private static final int CONTROL_TICKS = 600;
    private static final int EXPLORE_TICKS = 4800;
    private static final int GATHER_TICKS = 1200;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!"1".equals(System.getenv("FAMULUS_PROBE_EXPLORE"))) {
            return;
        }
        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL))
                .create()) {
            world.getConnection().waitForChunksDownload();
            world.getServer().runCommand("tp @a 0.5 " + (GROUND_Y + 1) + " 0.5 0 0");
            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("give @a minecraft:diamond_shovel 1");
            world.getServer().runCommand("gamerule randomTickSpeed 0");

            world.getServer().runCommand("fill " + SAND_DISTANCE + " " + (GROUND_Y + 1) + " "
                    + SAND_DISTANCE + " " + (SAND_DISTANCE + 15) + " " + (GROUND_Y + 1) + " "
                    + (SAND_DISTANCE + 15) + " minecraft:sand");
            context.waitTick();
            world.getConnection().waitForChunksRender();

            world.getServer().runCommand("fill 3 " + (GROUND_Y + 1) + " 3 6 "
                    + (GROUND_Y + 1) + " 6 minecraft:sand");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            command(context, "/famulus queue minecraft:sand=8");
            int nearby = waitForSand(context, CONTROL_TICKS);
            command(context, "/famulus stop");
            context.waitTick();
            System.out.println("[FamulusExplore] positive control, sand within reach: " + nearby);
            if (nearby < 8) {
                throw new AssertionError("Sand 5 blocks away was not gathered (" + nearby
                        + "/8), so nothing this probe measures about exploring can be trusted");
            }
            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("give @a minecraft:diamond_shovel 1");
            world.getServer().runCommand("fill 3 " + (GROUND_Y + 1) + " 3 6 "
                    + (GROUND_Y + 1) + " 6 minecraft:air");

            world.getServer().runCommand("kill @e[type=minecraft:item]");
            context.waitTick();
            world.getConnection().waitForClientboundPackets();

            command(context, "/famulus queue minecraft:sand=8");
            int controlCount = waitForSand(context, CONTROL_TICKS);
            command(context, "/famulus stop");
            context.waitTick();
            System.out.println("[FamulusExplore] control sand after "
                    + CONTROL_TICKS + " ticks: " + controlCount);
            context.takeScreenshot("famulus-explore-control");
            if (controlCount >= 8) {
                throw new AssertionError("The sand was reachable without exploring at "
                        + SAND_DISTANCE + " blocks, so this probe proves nothing. Move it further.");
            }

            context.runOnClient(client -> EXPLORER.start(client));
            context.waitTicks(20);
            boolean engaged = context.computeOnClient(client -> EXPLORER.isActive());
            System.out.println("[FamulusExplore] explore process active after 1s: " + engaged);
            double travelled = 0;
            for (int elapsed = 0; elapsed < EXPLORE_TICKS; elapsed += 100) {
                context.waitTicks(100);
                travelled = context.computeOnClient(ExploreProbeGameTest::distanceToSand);
                System.out.println("[FamulusExplore] after " + (elapsed + 100) / 20 + "s: "
                        + Math.round(travelled) + " blocks from the sand, active="
                        + context.computeOnClient(client -> EXPLORER.isActive()));
            }
            context.runOnClient(client -> EXPLORER.cancel());
            System.out.println("[FamulusExplore] closed to " + Math.round(travelled)
                    + " blocks from the sand");
            context.waitTick();
            context.takeScreenshot("famulus-explore-after-exploring");

            command(context, "/famulus queue minecraft:sand=8");
            int afterExploring = waitForSand(context, GATHER_TICKS);
            command(context, "/famulus stop");
            context.waitTick();
            context.takeScreenshot("famulus-explore-result");
            System.out.println("[FamulusExplore] RESULT control=" + controlCount
                    + " afterExploring=" + afterExploring + " distance=" + SAND_DISTANCE);
        }
    }

    private static final BaritoneExplorer EXPLORER = new BaritoneExplorer();

    private static int waitForSand(ClientGameTestContext context, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            if (context.computeOnClient(ExploreProbeGameTest::sandCount) >= 8) {
                break;
            }
            context.waitTick();
        }
        return context.computeOnClient(ExploreProbeGameTest::sandCount);
    }

    private static double distanceToSand(Minecraft client) {
        if (client.player == null) {
            return Double.MAX_VALUE;
        }
        double dx = client.player.getX() - (SAND_DISTANCE + 7.5);
        double dz = client.player.getZ() - (SAND_DISTANCE + 7.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int sandCount(Minecraft client) {
        return client.player == null ? 0 : client.player.getInventory().countItem(Items.SAND);
    }

    private static void command(ClientGameTestContext context, String command) {
        context.getInput().pressKey(options -> options.keyChat);
        context.getInput().typeChars(command);
        context.getInput().holdKeyFor(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0);
        context.waitTick();
    }
}
