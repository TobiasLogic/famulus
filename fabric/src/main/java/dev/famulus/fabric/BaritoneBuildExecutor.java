package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import dev.famulus.core.BuildExecutor;
import dev.famulus.core.PlannedTask;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

public final class BaritoneBuildExecutor implements BuildExecutor {
    private boolean ownsBuilding;
    private IStaticSchematic schematic;
    private BlockPos origin;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public boolean isBusy() {
        IBaritone api = baritone();
        return api.getBuilderProcess().isActive() || api.getMineProcess().isActive()
                || api.getPathingBehavior().isPathing();
    }

    public IStaticSchematic schematic() {
        return schematic;
    }

    public BlockPos origin() {
        return origin;
    }

    public void load(PlannedTask.Build task) throws IOException {
        Path file = SchematicAnalyzer.schematicDirectory().resolve(task.blueprint());
        if (!Files.isRegularFile(file)) {
            throw new IOException("No such blueprint: " + task.blueprint());
        }
        Optional<ISchematicFormat> format =
                BaritoneAPI.getProvider().getSchematicSystem().getByFile(file.toFile());
        if (format.isEmpty()) {
            throw new IOException("Baritone has no parser for " + task.blueprint()
                    + ". Supported here: " + String.join(", ", SchematicAnalyzer.supportedExtensions()));
        }
        try (InputStream in = Files.newInputStream(file)) {
            schematic = format.get().parse(in);
        } catch (RuntimeException malformed) {
            throw new IOException("Could not parse " + task.blueprint() + ": " + malformed, malformed);
        }
        if (schematic == null) {
            throw new IOException("Baritone returned no schematic for " + task.blueprint());
        }
        origin = new BlockPos(task.originX(), task.originY(), task.originZ());
    }

    @Override
    public void start(PlannedTask.Build task) {
        if (schematic == null || origin == null) {
            throw new IllegalStateException("Blueprint was not loaded before building");
        }
        if (isBusy()) {
            throw new IllegalStateException("Baritone is busy; stop its current task before building.");
        }
        ownsBuilding = true;
        baritone().getBuilderProcess().build(task.blueprint(), schematic,
                new Vec3i(origin.getX(), origin.getY(), origin.getZ()));
    }

    @Override
    public boolean isActive() {
        return ownsBuilding && baritone().getBuilderProcess().isActive();
    }

    @Override
    public void cancel() {
        if (!ownsBuilding) {
            return;
        }
        IBaritone api = baritone();
        boolean anotherProcessStarted = api.getMineProcess().isActive()
                || api.getExploreProcess().isActive();
        api.getBuilderProcess().onLostControl();
        if (!anotherProcessStarted) {
            api.getPathingBehavior().cancelEverything();
        }
        ownsBuilding = false;
    }
}
