package dev.famulus.fabric;

import dev.famulus.core.PlanRequest;
import dev.famulus.core.PlannerException;
import dev.famulus.core.TaskPlan;
import dev.famulus.jev.CredentialStore;
import dev.famulus.planner.ChatPlanner;
import dev.famulus.planner.PlannerConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class PlannerService {
    private static final int CONTAINER_RADIUS = 8;
    private static final int RESOURCE_RADIUS = 12;
    private static final int LISTED_ITEMS = 12;

    private static final Set<String> NOTABLE = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:shulker_box", "minecraft:ender_chest", "minecraft:crafting_table",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:anvil", "minecraft:enchanting_table", "minecraft:brewing_stand",
            "minecraft:bed", "minecraft:lever", "minecraft:water", "minecraft:lava");

    private final CredentialStore credentials;
    private final ExecutorService worker;
    private final AtomicReference<TaskPlan> ready = new AtomicReference<>();
    private final AtomicReference<TaskPlan> replanned = new AtomicReference<>();
    private volatile String endpoint;
    private volatile String model;
    private volatile String state = "idle";
    private volatile boolean busy;

    public PlannerService(CredentialStore credentials, String endpoint, String model) {
        this.credentials = credentials;
        this.endpoint = endpoint;
        this.model = model;

        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Famulus-planner");
            thread.setDaemon(true);
            return thread;
        });
    }

    public String endpoint() {
        return endpoint;
    }

    public String model() {
        return model;
    }

    public boolean isBusy() {
        return busy;
    }

    public String state() {
        return state;
    }

    public void configure(String newEndpoint, String newModel) {
        this.endpoint = newEndpoint.trim();
        this.model = newModel.trim();
        this.state = "set to " + this.model;
    }

    public Optional<TaskPlan> takePlan() {
        return Optional.ofNullable(ready.getAndSet(null));
    }

    public Optional<TaskPlan> takeReplan() {
        return Optional.ofNullable(replanned.getAndSet(null));
    }

    public boolean request(String goal, Minecraft client) {
        return submit(goal, describeWorld(client), ready, "asking ");
    }

    public boolean requestReplan(String goal, String situation, Minecraft client) {
        String context = describeWorld(client)
                + "\n\nThe previous plan for this goal stopped before it finished. Plan again "
                + "from the state above, skipping what is already done.\n" + situation;
        replanned.set(null);
        return submit(goal, context, replanned, "replanning with ");
    }

    private boolean submit(String goal, String context, AtomicReference<TaskPlan> sink, String verb) {
        if (busy) {
            return false;
        }
        PlannerConfig config;
        try {
            config = buildConfig();
        } catch (RuntimeException invalid) {
            state = invalid.getMessage();
            return false;
        }
        busy = true;
        state = verb + model + "...";
        worker.execute(() -> {
            try {
                TaskPlan plan = new ChatPlanner(config).plan(new PlanRequest(
                        goal, context, GatherCatalog.items(), SmeltCatalog.recipes()));
                sink.set(plan);
                state = "plan ready: " + plan.tasks().size() + " tasks";
            } catch (PlannerException refused) {
                state = refused.getMessage();
            } catch (RuntimeException unexpected) {
                state = "planner failed: " + unexpected;
            } finally {
                busy = false;
            }
        });
        return true;
    }

    private PlannerConfig buildConfig() {
        boolean local = endpoint.contains("localhost") || endpoint.contains("127.0.0.1");
        String key = credentials.resolve().orElse("");
        if (key.isBlank() && !local) {
            throw new IllegalStateException("No API key. Set one in Settings, or point the planner "
                    + "at a local server.");
        }
        return new PlannerConfig(endpoint, model, key, Duration.ofSeconds(local ? 180 : 90));
    }

    private static String describeWorld(Minecraft client) {
        if (client.player == null || client.level == null) {
            return "Not in a world.";
        }
        BlockPos origin = client.player.blockPosition();
        StringBuilder text = new StringBuilder(512);
        text.append("Dimension: ").append(client.level.dimension().identifier())
                .append("\nPosition: ").append(origin.toShortString())
                .append("\nBiome: ").append(biome(client, origin))
                .append("\nGame mode: ").append(client.player.gameMode())
                .append("\nHealth: ").append(Math.round(client.player.getHealth())).append("/20")
                .append("\nFood: ").append(client.player.getFoodData().getFoodLevel()).append("/20")
                .append("\nTime: ").append(timeOfDay(client))
                .append("\nWeather: ").append(weather(client))
                .append("\nInventory: ").append(inventory(client))
                .append("\nFree slots: ").append(freeSlots(client))
                .append("\nNearby blocks: ").append(nearby(client, origin));
        return text.toString();
    }

    private static String biome(Minecraft client, BlockPos origin) {
        return client.level.getBiome(origin).unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("unknown");
    }

    private static String timeOfDay(Minecraft client) {
        long ticks = client.level.getOverworldClockTime() % 24000L;
        String phase;
        if (ticks < 12000L) {
            phase = "day";
        } else if (ticks < 13000L) {
            phase = "sunset";
        } else if (ticks < 23000L) {
            phase = "night, mobs are spawning";
        } else {
            phase = "sunrise";
        }
        return ticks + " (" + phase + ")";
    }

    private static String weather(Minecraft client) {
        if (client.level.isThundering()) {
            return "thunderstorm";
        }
        return client.level.isRaining() ? "raining" : "clear";
    }

    private static String inventory(Minecraft client) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) {
            return "empty";
        }
        List<String> listed = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(LISTED_ITEMS)
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .toList();
        String summary = String.join(", ", listed);
        return counts.size() > LISTED_ITEMS
                ? summary + ", and " + (counts.size() - LISTED_ITEMS) + " other kinds"
                : summary;
    }

    private static int freeSlots(Minecraft client) {
        int free = 0;
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static String nearby(Minecraft client, BlockPos origin) {
        Map<String, BlockPos> containers = new LinkedHashMap<>();
        Map<String, Integer> resources = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-RESOURCE_RADIUS, -RESOURCE_RADIUS, -RESOURCE_RADIUS),
                origin.offset(RESOURCE_RADIUS, RESOURCE_RADIUS, RESOURCE_RADIUS))) {
            BlockState state = client.level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (NOTABLE.contains(id)) {
                if (!containers.containsKey(id) && origin.distSqr(pos)
                        <= (double) CONTAINER_RADIUS * CONTAINER_RADIUS) {
                    containers.put(id, pos.immutable());
                }
            } else if (GatherCatalog.isKnownBlock(id)) {
                resources.merge(id, 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        containers.forEach((id, pos) -> parts.add(id + " at " + pos.toShortString()));
        resources.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(LISTED_ITEMS)
                .forEach(entry -> parts.add(entry.getKey() + " x" + entry.getValue()));
        return parts.isEmpty() ? "nothing notable within " + RESOURCE_RADIUS + " blocks"
                : String.join(", ", parts);
    }
}
