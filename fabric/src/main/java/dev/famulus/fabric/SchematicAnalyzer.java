package dev.famulus.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;

public final class SchematicAnalyzer {
    public static final long MAX_VOLUME = 8_000_000L;

    private SchematicAnalyzer() {}

    public static Path schematicDirectory() throws IOException {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("famulus").resolve("schematics");
        Files.createDirectories(directory);
        return directory;
    }

    public static List<String> supportedExtensions() {
        try {
            return List.copyOf(BaritoneAPI.getProvider().getSchematicSystem().getFileExtensions());
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    public static List<Path> listSchematics() throws IOException {
        List<String> extensions = supportedExtensions();
        try (var entries = Files.list(schematicDirectory())) {
            List<Path> found = new ArrayList<>(entries.filter(Files::isRegularFile)
                    .filter(path -> matches(path, extensions))
                    .toList());
            found.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return found;
        }
    }

    private static boolean matches(Path path, List<String> extensions) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return extensions.stream().anyMatch(extension -> name.endsWith("." + extension.toLowerCase(java.util.Locale.ROOT)));
    }

    public static SchematicSummary analyze(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No such schematic file: " + file);
        }
        Optional<ISchematicFormat> format;
        try {
            format = BaritoneAPI.getProvider().getSchematicSystem().getByFile(file.toFile());
        } catch (RuntimeException failure) {
            throw new IOException("Baritone could not inspect " + file.getFileName() + ": " + failure, failure);
        }
        if (format.isEmpty()) {
            throw new IOException("Baritone has no parser for " + file.getFileName()
                    + ". Supported here: " + String.join(", ", supportedExtensions()));
        }

        IStaticSchematic schematic;
        try (InputStream in = Files.newInputStream(file)) {
            schematic = format.get().parse(in);
        } catch (RuntimeException malformed) {
            throw new IOException("Could not parse " + file.getFileName() + ": " + malformed, malformed);
        }
        if (schematic == null) {
            throw new IOException("Baritone returned no schematic for " + file.getFileName());
        }
        return summarise(file.getFileName().toString(), schematic);
    }

    public record Progress(int remaining, int total, boolean hasMaterials) {}

    public static Progress measure(Minecraft client, IStaticSchematic schematic, BlockPos origin) {
        int remaining = 0;
        int total = 0;
        Map<String, Integer> stillNeeded = new LinkedHashMap<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int x = 0; x < schematic.widthX(); x++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired;
                    try {
                        desired = schematic.getDirect(x, y, z);
                    } catch (RuntimeException outOfRange) {
                        continue;
                    }
                    if (desired == null || desired.isAir()) {
                        continue;
                    }
                    Item item = desired.getBlock().asItem();
                    if (item == Items.AIR) {
                        continue;
                    }
                    total++;
                    BlockState present = client.level.getBlockState(origin.offset(x, y, z));
                    if (!present.is(desired.getBlock())) {
                        remaining++;
                        stillNeeded.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
                    }
                }
            }
        }
        boolean hasMaterials = stillNeeded.isEmpty();
        if (!hasMaterials && client.player != null) {
            Map<String, Integer> held = MinecraftObserver.countAll(client, stillNeeded.keySet());
            hasMaterials = held.values().stream().anyMatch(count -> count > 0);
        }
        return new Progress(remaining, total, hasMaterials);
    }

    public static SchematicSummary summarise(String name, IStaticSchematic schematic) throws IOException {
        int widthX = schematic.widthX();
        int heightY = schematic.heightY();
        int lengthZ = schematic.lengthZ();
        long volume = (long) widthX * heightY * lengthZ;
        if (volume > MAX_VOLUME) {
            throw new IOException(name + " is " + widthX + "x" + heightY + "x" + lengthZ
                    + " (" + volume + " positions), beyond the " + MAX_VOLUME + " limit");
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (int y = 0; y < heightY; y++) {
            for (int x = 0; x < widthX; x++) {
                for (int z = 0; z < lengthZ; z++) {
                    BlockState state;
                    try {
                        state = schematic.getDirect(x, y, z);
                    } catch (RuntimeException outOfRange) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    Item item = state.getBlock().asItem();
                    if (item == Items.AIR) {
                        continue;
                    }
                    counts.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
                }
            }
        }
        return new SchematicSummary(name, widthX, heightY, lengthZ, new LinkedHashMap<>(counts));
    }
}
