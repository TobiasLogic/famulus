package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.ISchematic;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

@SuppressWarnings("UnstableApiUsage")
public final class ScaffoldingProbeGameTest implements FabricClientGameTest {
    private static final int GROUND_Y = -61;
    private static final int BUILD_TIMEOUT_TICKS = 2400;
    private static final int SIZE = 3;

    private record Platform(BlockState block) implements ISchematic {
        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
            return block;
        }

        @Override
        public int widthX() {
            return SIZE;
        }

        @Override
        public int heightY() {
            return 1;
        }

        @Override
        public int lengthZ() {
            return SIZE;
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!"1".equals(System.getenv("FAMULUS_PROBE_SCAFFOLDING"))) {
            return;
        }
        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL))
                .create()) {
            world.getConnection().waitForChunksDownload();
            world.getServer().runCommand("tp @a 0.5 " + (GROUND_Y + 1) + " 0.5 0 0");
            world.getServer().runCommand("clear @a");
            world.getServer().runCommand("give @a minecraft:cobblestone 128");
            world.getServer().runCommand("gamerule randomTickSpeed 0");
            context.waitTick();
            world.getConnection().waitForChunksRender();

            BlockPos grounded = new BlockPos(6, GROUND_Y + 1, 6);
            int groundedPlaced = attempt(context, "grounded", grounded);

            BlockPos floating = new BlockPos(-6, GROUND_Y + 6, -6);
            int floatingPlaced = attempt(context, "floating", floating);

            int total = SIZE * SIZE;
            System.out.println("[FamulusProbe] RESULT grounded=" + groundedPlaced + "/" + total
                    + " floating=" + floatingPlaced + "/" + total);
            System.out.println("[FamulusProbe] scaffoldBlocksLeftBehind="
                    + countSupportColumn(context, floating));

            if (groundedPlaced < total) {
                throw new AssertionError("Control build failed (" + groundedPlaced + "/" + total
                        + "); the probe cannot conclude anything about scaffolding");
            }
        }
    }

    private static int attempt(ClientGameTestContext context, String label, BlockPos origin) {
        context.runOnClient(client -> BaritoneAPI.getProvider().getPrimaryBaritone()
                .getBuilderProcess()
                .build(label, new Platform(Blocks.COBBLESTONE.defaultBlockState()),
                        new Vec3i(origin.getX(), origin.getY(), origin.getZ())));
        context.waitTick();

        for (int tick = 0; tick < BUILD_TIMEOUT_TICKS; tick++) {
            boolean done = context.computeOnClient(client ->
                    placed(client, origin) >= SIZE * SIZE
                            || !BaritoneAPI.getProvider().getPrimaryBaritone()
                                    .getBuilderProcess().isActive());
            if (done) {
                break;
            }
            context.waitTick();
        }
        context.runOnClient(client -> BaritoneAPI.getProvider().getPrimaryBaritone()
                .getPathingBehavior().cancelEverything());
        context.waitTick();
        context.takeScreenshot("famulus-scaffolding-" + label);
        return context.computeOnClient(client -> placed(client, origin));
    }

    private static int placed(Minecraft client, BlockPos origin) {
        int found = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (client.level.getBlockState(origin.offset(x, 0, z)).is(Blocks.COBBLESTONE)) {
                    found++;
                }
            }
        }
        return found;
    }

    private static int countSupportColumn(ClientGameTestContext context, BlockPos origin) {
        return context.computeOnClient(client -> {
            int found = 0;
            for (int y = GROUND_Y + 1; y < origin.getY(); y++) {
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        if (!client.level.getBlockState(
                                new BlockPos(origin.getX() + x, y, origin.getZ() + z)).isAir()) {
                            found++;
                        }
                    }
                }
            }
            return found;
        });
    }
}
