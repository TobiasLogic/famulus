package dev.famulus.fabric;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.famulus.core.GatherController;
import dev.famulus.jev.CredentialStore;
import dev.famulus.core.MaterialList;
import dev.famulus.core.MaterialRequirement;
import dev.famulus.core.PlannedTask;
import dev.famulus.core.PolicyGateConfig;
import dev.famulus.core.TaskPlan;
import dev.famulus.core.GatherTask;
import dev.famulus.core.TaskResult;
import dev.famulus.core.TaskStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class FamulusClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Famulus");
    private static final Gson JSON = new Gson();
    public static final MinecraftObserver OBSERVER = new MinecraftObserver();
    private static FamulusClient instance;
    private final BaritoneGatherExecutor executor = new BaritoneGatherExecutor();
    private GatherController controller;
    private FamulusAgent agent;
    private CredentialStore credentials;
    private PlannerService planner;
    private KeyMapping openScreen;
    private int agentTicks;
    private FamulusConfig config;
    private String configurationError;
    private TaskStatus lastStatus;
    private int ticks;

    @Override
    public void onInitializeClient() {
        instance = this;
        try {
            config = FamulusConfig.load(FabricLoader.getInstance().getConfigDir().resolve("famulus.properties"));
            controller = new GatherController(executor, config.gather());
            credentials = new CredentialStore(
                    FabricLoader.getInstance().getConfigDir().resolve("famulus"));
            agent = new FamulusAgent(config.gather(), PolicyGateConfig.defaults(), credentials,
                    config.exploreTimeoutMillis());
            planner = new PlannerService(credentials, config.plannerEndpoint(), config.plannerModel());
        } catch (Exception e) {
            configurationError = "Fix config/famulus.properties and restart: " + e.getMessage();
            LOGGER.error("Famulus configuration is invalid. {}", configurationError, e);
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(literal("famulus")
                        .requires(FabricClientCommandSource::attended)
                        .executes(context -> status(context.getSource()))
                        .then(literal("status").executes(context -> status(context.getSource())))
                        .then(literal("materials")
                                .then(argument("schematic", StringArgumentType.string())
                                        .suggests(FamulusClient::suggestSchematics)
                                        .executes(context -> materials(context.getSource(),
                                                StringArgumentType.getString(context, "schematic")))))
                        .then(literal("queue")
                                .then(argument("spec", StringArgumentType.greedyString())
                                        .executes(context -> queue(context.getSource(),
                                                StringArgumentType.getString(context, "spec")))))
                        .then(literal("travel")
                                .then(argument("x", IntegerArgumentType.integer(-30000000, 30000000))
                                        .then(argument("y", IntegerArgumentType.integer(-256, 512))
                                                .then(argument("z", IntegerArgumentType.integer(-30000000, 30000000))
                                                        .executes(context -> single(context.getSource(),
                                                                new PlannedTask.Travel("t1",
                                                                        IntegerArgumentType.getInteger(context, "x"),
                                                                        IntegerArgumentType.getInteger(context, "y"),
                                                                        IntegerArgumentType.getInteger(context, "z"))))))))
                        .then(literal("deposit")
                                .then(argument("item", IdentifierArgument.id())
                                        .then(argument("count", IntegerArgumentType.integer(1, 2304))
                                                .executes(context -> single(context.getSource(),
                                                        new PlannedTask.Deposit("d1",
                                                                context.getArgument("item", Identifier.class).toString(),
                                                                IntegerArgumentType.getInteger(context, "count")))))))
                        .then(literal("withdraw")
                                .then(argument("item", IdentifierArgument.id())
                                        .then(argument("count", IntegerArgumentType.integer(1, 2304))
                                                .executes(context -> single(context.getSource(),
                                                        new PlannedTask.Withdraw("w1",
                                                                context.getArgument("item", Identifier.class).toString(),
                                                                IntegerArgumentType.getInteger(context, "count")))))))
                        .then(literal("build")
                                .then(argument("schematic", StringArgumentType.string())
                                        .suggests(FamulusClient::suggestSchematics)
                                        .executes(context -> build(context.getSource(),
                                                StringArgumentType.getString(context, "schematic")))))
                        .then(literal("collect")
                                .then(argument("schematic", StringArgumentType.string())
                                        .suggests(FamulusClient::suggestSchematics)
                                        .executes(context -> collect(context.getSource(),
                                                StringArgumentType.getString(context, "schematic")))))
                        .then(literal("stop").executes(context -> stop(context.getSource())))
                        .then(literal("gather")
                                .then(argument("item", IdentifierArgument.id())
                                        .suggests((context, builder) -> {
                                            GatherCatalog.items().stream()
                                                    .filter(id -> id.startsWith(builder.getRemainingLowerCase())
                                                            || id.substring("minecraft:".length()).startsWith(builder.getRemainingLowerCase()))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .then(argument("count", IntegerArgumentType.integer(1, 2304))
                                                .executes(context -> gather(context.getSource(),
                                                        context.getArgument("item", Identifier.class),
                                                        IntegerArgumentType.getInteger(context, "count"))))))));
        openScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.famulus.open", InputConstants.Type.KEYSYM,
                InputConstants.KEY_G, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOGGER.info("Famulus 0.1.0 initialized for Minecraft 26.2. Policy {}. Schematic formats: {}",
                agent == null ? "unavailable" : agent.policyState(),
                String.join(", ", SchematicAnalyzer.supportedExtensions()));
    }

    public static FamulusAgent agent() {
        return instance == null ? null : instance.agent;
    }

    public static FamulusScreen createScreen() {
        return createScreen(0);
    }

    public static FamulusScreen createScreen(int tab) {
        if (instance == null || instance.agent == null) {
            return null;
        }
        return new FamulusScreen(instance.agent, instance.credentials, instance.planner, tab);
    }

    private int gather(FabricClientCommandSource source, Identifier item, int count) {
        if (!ready(source)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (controller.isRunning()) return error(source, "A gather task is running. Use /famulus stop first.");
        if (client.player == null || client.level == null) return error(source, "Join a world first.");
        if (client.player.gameMode() != GameType.SURVIVAL) {
            return error(source, "Gather requires survival mode; creative mining does not drop items.");
        }
        if (!BuiltInRegistries.ITEM.containsKey(item)) return error(source, "Unknown item: " + item);
        if (!GatherCatalog.supports(item.toString())) {
            return error(source, "Unsupported gather item. Prototype supports logs, dirt, sand and red sand. Use tab completion.");
        }
        try {
            if (executor.isBusy()) return error(source, "Baritone is already busy. Stop its current operation first.");
            GatherTask task = new GatherTask(UUID.randomUUID().toString(), item.toString(), item.toString(), count);
            controller.start(task, OBSERVER.observe(client, task.itemId()), now());
            lastStatus = controller.result().status();
            report(source, controller.result());
            LOGGER.info("task={} item={} result={}", task.id(), task.itemId(), JSON.toJson(controller.result()));
            return controller.result().status() == TaskStatus.FAILED ? 0 : 1;
        } catch (RuntimeException e) {
            LOGGER.error("Cannot start gather", e);
            return error(source, "Cannot start gather: " + e.getMessage());
        }
    }

    private void tick(Minecraft client) {
        if (openScreen != null && agent != null) {
            while (openScreen.consumeClick()) {
                client.setScreenAndShow(new FamulusScreen(agent, credentials, planner, 0));
            }
        }
        if (agent != null && agent.isRunning() && ++agentTicks % config.observationIntervalTicks() == 0) {
            try {
                agent.tick(client, now());
            } catch (RuntimeException e) {
                agent.stop("Agent failure: " + e.getMessage());
                LOGGER.error("Plan stopped after an integration failure", e);
            }
        }
        if (controller == null || !controller.isRunning()) return;
        if (++ticks % config.observationIntervalTicks() != 0) return;
        try {
            controller.tick(OBSERVER.observe(client, controller.task().itemId()), now());
            TaskResult result = controller.result();
            if (result.status() != lastStatus) {
                lastStatus = result.status();
                LOGGER.info("task={} result={}", controller.task().id(), JSON.toJson(result));
                if (client.player != null) client.player.sendSystemMessage(Component.literal(format(result)));
            }
        } catch (RuntimeException e) {
            controller.stop("Minecraft observation failed: " + e.getMessage());
            LOGGER.error("Gather stopped after integration failure", e);
        }
    }

    private int status(FabricClientCommandSource source) {
        if (!ready(source)) return 0;
        report(source, controller.result());
        if (agent != null) {
            source.sendFeedback(Component.literal("[Famulus] " + agent.status()));
            List<String> recent = agent.recentLog();
            recent.subList(Math.max(0, recent.size() - 5), recent.size())
                    .forEach(line -> source.sendFeedback(Component.literal("  " + line)));
        }
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestSchematics(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
                              com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        try {
            SchematicAnalyzer.listSchematics().stream()
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase().startsWith(builder.getRemainingLowerCase().replace("\"", "")))
                    .forEach(name -> builder.suggest('"' + name + '"'));
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    }

    private int materials(FabricClientCommandSource source, String schematic) {
        if (!ready(source)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return error(source, "Join a world first.");
        MaterialList list;
        SchematicSummary summary;
        try {
            summary = SchematicAnalyzer.analyze(SchematicAnalyzer.schematicDirectory().resolve(schematic));
            list = MaterialList.of(summary.itemCounts(),
                    MinecraftObserver.countAll(client, summary.itemCounts().keySet()));
        } catch (Exception failure) {
            return error(source, failure.getMessage());
        }
        source.sendFeedback(Component.literal("[Famulus] " + summary.name() + " "
                + summary.dimensions() + ", " + summary.totalBlocks() + " blocks, "
                + list.distinctItems() + " item types"));
        for (MaterialRequirement requirement : list.requirements()) {
            source.sendFeedback(Component.literal(String.format("  %-34s need %5d  have %5d  short %5d",
                    requirement.itemId(), requirement.needed(), requirement.have(), requirement.shortfall())));
        }
        List<MaterialRequirement> blocked = list.unobtainable(GatherCatalog::supports);
        if (!blocked.isEmpty()) {
            source.sendFeedback(Component.literal("[Famulus] cannot gather yet: "
                    + blocked.stream().map(MaterialRequirement::itemId).toList()));
        }
        return 1;
    }

    private int queue(FabricClientCommandSource source, String spec) {
        if (!ready(source)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return error(source, "Join a world first.");
        if (client.player.gameMode() != GameType.SURVIVAL) {
            return error(source, "Gathering requires survival mode; creative mining does not drop items.");
        }
        if (agent.isRunning() || controller.isRunning()) {
            return error(source, "Something is already running. Use /famulus stop first.");
        }
        List<PlannedTask> tasks = new ArrayList<>();
        int number = 0;
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                return error(source, "Expected item=count entries, got: " + trimmed);
            }
            String itemId = parts[0].trim();
            if (!GatherCatalog.supports(itemId)) {
                return error(source, "Unsupported gather item: " + itemId
                        + ". Supported: " + GatherCatalog.items());
            }
            int count;
            try {
                count = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException notANumber) {
                return error(source, "Not a number: " + parts[1].trim());
            }
            if (count < 1 || count > 2304) {
                return error(source, "Count must be between 1 and 2304, got " + count);
            }
            tasks.add(new PlannedTask.Gather("t" + (++number), itemId, count));
        }
        if (tasks.isEmpty()) return error(source, "Nothing to gather.");
        try {
            agent.start(new TaskPlan("queued gather", tasks));
        } catch (RuntimeException failure) {
            return error(source, "Cannot start plan: " + failure.getMessage());
        }
        source.sendFeedback(Component.literal("[Famulus] plan started: " + tasks.size()
                + " tasks." + (agent.isPolicyConfigured() ? "" : " Policy offline; failures will just retry.")));
        return 1;
    }

    private int single(FabricClientCommandSource source, PlannedTask task) {
        if (!ready(source)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return error(source, "Join a world first.");
        if (agent.isRunning() || controller.isRunning()) {
            return error(source, "Something is already running. Use /famulus stop first.");
        }
        try {
            agent.start(new TaskPlan(task.describe(), List.of(task)));
        } catch (RuntimeException failure) {
            return error(source, failure.getMessage());
        }
        source.sendFeedback(Component.literal("[Famulus] " + task.describe()));
        return 1;
    }

    private int build(FabricClientCommandSource source, String schematic) {
        if (!ready(source)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return error(source, "Join a world first.");
        if (client.player.gameMode() != GameType.SURVIVAL) {
            return error(source, "Building requires survival mode.");
        }
        if (agent.isRunning() || controller.isRunning()) {
            return error(source, "Something is already running. Use /famulus stop first.");
        }
        var origin = client.player.blockPosition();
        try {
            SchematicSummary summary =
                    SchematicAnalyzer.analyze(SchematicAnalyzer.schematicDirectory().resolve(schematic));
            agent.start(new TaskPlan("build " + summary.name(), List.of(
                    new PlannedTask.Build("b1", schematic,
                            origin.getX(), origin.getY(), origin.getZ()))));
            source.sendFeedback(Component.literal("[Famulus] building " + summary.name() + " "
                    + summary.dimensions() + " at " + origin.toShortString()));
        } catch (Exception failure) {
            return error(source, failure.getMessage());
        }
        return 1;
    }

    private int collect(FabricClientCommandSource source, String schematic) {
        if (!ready(source)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return error(source, "Join a world first.");
        if (client.player.gameMode() != GameType.SURVIVAL) {
            return error(source, "Gathering requires survival mode; creative mining does not drop items.");
        }
        if (agent.isRunning() || controller.isRunning()) {
            return error(source, "Something is already running. Use /famulus stop first.");
        }
        SchematicSummary summary;
        MaterialList list;
        try {
            summary = SchematicAnalyzer.analyze(SchematicAnalyzer.schematicDirectory().resolve(schematic));
            list = MaterialList.of(summary.itemCounts(),
                    MinecraftObserver.countAll(client, summary.itemCounts().keySet()));
        } catch (Exception failure) {
            return error(source, failure.getMessage());
        }
        if (list.isSatisfied()) {
            source.sendFeedback(Component.literal("[Famulus] every material for "
                    + summary.name() + " is already held."));
            return 1;
        }
        List<MaterialRequirement> blocked = list.unobtainable(GatherCatalog::supports);
        if (!blocked.isEmpty()) {
            return error(source, "No gather path for " + blocked.stream()
                    .map(MaterialRequirement::itemId).toList()
                    + ". Only logs, dirt, sand and red sand are supported so far.");
        }
        List<PlannedTask> tasks = new ArrayList<>();
        int number = 0;
        for (MaterialRequirement requirement : list.gatherable(GatherCatalog::supports)) {
            if (requirement.needed() > 2304) {
                return error(source, requirement.itemId() + " needs " + requirement.needed()
                        + ", more than an inventory holds. Chest storage does not exist yet.");
            }
            tasks.add(new PlannedTask.Gather("t" + (++number), requirement.itemId(), requirement.needed()));
        }
        try {
            TaskPlan plan = new TaskPlan("collect materials for " + summary.name(), tasks);
            agent.start(plan);
        } catch (RuntimeException failure) {
            return error(source, "Cannot start plan: " + failure.getMessage());
        }
        source.sendFeedback(Component.literal("[Famulus] collecting for " + summary.name()
                + ": " + tasks.size() + " gather tasks, " + list.totalShortfall() + " items short."
                + (agent.isPolicyConfigured() ? "" : " Policy offline; failures will just retry.")));
        return 1;
    }

    private int stop(FabricClientCommandSource source) {
        if (!ready(source)) return 0;
        if (agent != null && agent.isRunning()) {
            agent.stop("Stopped by user");
            report(source, controller.result());
            source.sendFeedback(Component.literal("[Famulus] plan stopped: " + agent.status()));
            return 1;
        }
        controller.stop("Stopped by user");
        lastStatus = controller.result().status();
        report(source, controller.result());
        LOGGER.info("Stopped: {}", JSON.toJson(controller.result()));
        return 1;
    }

    private boolean ready(FabricClientCommandSource source) {
        if (controller != null) return true;
        error(source, configurationError);
        return false;
    }

    private void report(FabricClientCommandSource source, TaskResult result) {
        source.sendFeedback(Component.literal(format(result)));
    }

    private String format(TaskResult result) {
        return "[Famulus] " + result.status() + " | " + result.currentCount() + "/" + result.targetCount()
                + " | attempts " + result.attempts() + " | " + result.message();
    }

    private int error(FabricClientCommandSource source, String message) {
        source.sendError(Component.literal("[Famulus] " + message));
        return 0;
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }
}
