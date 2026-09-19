package dev.famulus.fabric;

import dev.famulus.core.PlanRequest;
import dev.famulus.core.PlannerException;
import dev.famulus.core.TaskPlan;
import dev.famulus.jev.CredentialStore;
import dev.famulus.planner.ChatPlanner;
import dev.famulus.planner.PlannerConfig;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

public final class PlannerService {
    private final CredentialStore credentials;
    private final ExecutorService worker;
    private final AtomicReference<TaskPlan> ready = new AtomicReference<>();
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

    public boolean request(String goal, Minecraft client) {
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
        String context = describeWorld(client);
        busy = true;
        state = "asking " + model + "...";
        worker.execute(() -> {
            try {
                TaskPlan plan = new ChatPlanner(config).plan(
                        new PlanRequest(goal, context, GatherCatalog.items()));
                ready.set(plan);
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
        return "Dimension: " + client.level.dimension().identifier()
                + "\nPosition: " + client.player.blockPosition().toShortString()
                + "\nHealth: " + Math.round(client.player.getHealth())
                + "\nGame mode: " + client.player.gameMode();
    }
}
