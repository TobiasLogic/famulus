package dev.famulus.fabric;

import dev.famulus.core.AgentAction;
import dev.famulus.core.GatherConfig;
import dev.famulus.core.BuildController;
import dev.famulus.core.DepositController;
import dev.famulus.core.DepositSnapshot;
import dev.famulus.core.TravelController;
import dev.famulus.core.TravelSnapshot;
import dev.famulus.core.BuildSnapshot;
import dev.famulus.core.GatherController;
import dev.famulus.core.GatherTask;
import dev.famulus.core.PlanRunner;
import dev.famulus.core.PlanStep;
import dev.famulus.core.PlannedTask;
import dev.famulus.core.PolicyClient;
import dev.famulus.core.PolicyException;
import dev.famulus.core.PolicyGate;
import dev.famulus.core.PolicyGateConfig;
import dev.famulus.core.PolicyRequest;
import dev.famulus.core.TaskPlan;
import dev.famulus.core.TaskResult;
import dev.famulus.core.TaskStatus;
import dev.famulus.core.WorldSnapshot;
import dev.famulus.jev.CredentialStore;
import dev.famulus.jev.JevClient;
import dev.famulus.jev.JevConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

public final class FamulusAgent {
    private static final int POLICY_TIMEOUT_TICKS = 300;
    private static final int REPLAN_TIMEOUT_TICKS = 600;
    private static final int MAX_REPLANS = 2;
    private static final int MAX_REPLAN_REQUESTS = 20;

    public interface Replanner {
        boolean request(String goal, String situation, Minecraft client);

        Optional<TaskPlan> take();
    }

    private final GatherController gather;
    private final BuildController builder;
    private final BaritoneBuildExecutor buildExecutor = new BaritoneBuildExecutor();
    private final TravelController traveller;
    private final BaritoneTravelExecutor travelExecutor = new BaritoneTravelExecutor();
    private final DepositController transfers;
    private final MinecraftContainerExecutor containerExecutor = new MinecraftContainerExecutor();
    private final MinecraftCraftExecutor craftExecutor = new MinecraftCraftExecutor();
    private final MinecraftSmeltExecutor smeltExecutor = new MinecraftSmeltExecutor();
    private final MinecraftPlaceExecutor placeExecutor = new MinecraftPlaceExecutor();
    private final MinecraftInteractExecutor interactExecutor = new MinecraftInteractExecutor();
    private final BuildController interacting;
    private final DepositController placing;
    private final DepositController crafting;
    private final DepositController smelting;
    private final BaritoneExplorer explorer = new BaritoneExplorer();
    private final long exploreTimeoutMillis;
    private final PolicyGate gate;
    private final CredentialStore credentials;
    private final ExecutorService policyThread;

    private final AtomicReference<PolicyClient> policyClient = new AtomicReference<>();
    private volatile String policyState = "not configured";
    private final Deque<String> log = new ArrayDeque<>();

    private record Answer(long generation, AgentAction action) {}

    private final AtomicReference<Answer> pendingDecision = new AtomicReference<>();
    private long policyGeneration;
    private PlanRunner runner;
    private boolean taskStarted;
    private boolean policyDispatched;
    private int policyWaitTicks;
    private boolean exploring;
    private long exploreStartedAt;
    private Replanner replanner;
    private int replansUsed;
    private int replanRequests;
    private int replanWaitTicks;
    private boolean replanRequested;
    private boolean replanExhausted;

    public FamulusAgent(GatherConfig gatherConfig, PolicyGateConfig policyConfig,
                        CredentialStore credentials, long exploreTimeoutMillis) {
        this.gather = new GatherController(new BaritoneGatherExecutor(), gatherConfig);
        this.builder = new BuildController(buildExecutor, gatherConfig);
        this.traveller = new TravelController(travelExecutor, gatherConfig);
        this.transfers = new DepositController(containerExecutor, gatherConfig);
        this.crafting = new DepositController(craftExecutor, gatherConfig);
        this.smelting = new DepositController(smeltExecutor, gatherConfig);
        this.placing = new DepositController(placeExecutor, gatherConfig);
        this.interacting = new BuildController(interactExecutor, gatherConfig);
        this.exploreTimeoutMillis = exploreTimeoutMillis;
        this.credentials = credentials;
        reloadPolicy();

        this.gate = new PolicyGate(request -> policyClient.get().decide(request),
                policyConfig, AgentAction.RECOVER);

        this.policyThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Famulus-policy");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void reloadPolicy() {
        String key = credentials.resolve().orElse(null);
        if (key == null || key.isBlank()) {
            policyState = "offline, no API key";
            policyClient.set(request -> {
                throw new PolicyException("No API key. Set one in the Settings tab or "
                        + JevConfig.API_KEY_VARIABLE + ".");
            });
            return;
        }
        policyClient.set(new JevClient(JevConfig.withKey(key)));
        policyState = "ready (" + CredentialStore.mask(key)
                + (credentials.isOverriddenByEnvironment() ? ", from environment)" : ")");
    }

    public boolean isPolicyConfigured() {
        return credentials.resolve().isPresent();
    }

    public String policyState() {
        return policyState;
    }

    public boolean isRunning() {
        return runner != null && !runner.step().isTerminal() && runner.step() != PlanStep.NOT_STARTED;
    }

    public void setReplanner(Replanner replanner) {
        this.replanner = replanner;
    }

    public boolean needsReplan() {
        return !replanExhausted && runner != null && runner.step() == PlanStep.REPLAN_REQUIRED;
    }

    public void start(TaskPlan plan) {
        if (isRunning()) {
            throw new IllegalStateException("A plan is already running. Use /famulus stop first.");
        }
        replansUsed = 0;
        replanRequests = 0;
        replanExhausted = false;
        log.clear();
        begin(plan);
    }

    private void begin(TaskPlan plan) {
        runner = new PlanRunner(plan, 3);
        taskStarted = false;
        exploring = false;
        policyDispatched = false;
        replanRequested = false;
        policyGeneration++;
        pendingDecision.set(null);
        gate.reset();
        note("plan started: " + plan.goal() + " (" + plan.tasks().size() + " tasks)");
        runner.start();
    }

    public void tickEveryFrame(Minecraft client) {
        if (!isRunning() || !taskStarted || runner.step() != PlanStep.RUN_CURRENT) {
            return;
        }
        PlannedTask current = runner.current();
        if (current instanceof PlannedTask.Deposit || current instanceof PlannedTask.Withdraw) {
            containerExecutor.tick(client);
        } else if (current instanceof PlannedTask.Craft) {
            craftExecutor.tick(client);
        } else if (current instanceof PlannedTask.Smelt) {
            smeltExecutor.tick(client);
        } else if (current instanceof PlannedTask.PlaceBlock) {
            placeExecutor.tick(client);
        } else if (current instanceof PlannedTask.Interact) {
            interactExecutor.tick(client);
        }
    }

    public void tick(Minecraft client, long nowMillis) {
        if (runner == null) {
            return;
        }
        switch (runner.step()) {
            case RUN_CURRENT -> runCurrent(client, nowMillis);
            case CONSULT_POLICY -> consultPolicy(client, nowMillis);
            case EXPLORE -> explore(client, nowMillis);
            case REPLAN_REQUIRED -> replan(client);
            default -> { }
        }
    }

    private void replan(Minecraft client) {
        if (replanExhausted) {
            return;
        }
        if (replanner == null) {
            abandonReplan("no planner is wired up");
            return;
        }
        if (replansUsed >= MAX_REPLANS) {
            abandonReplan("already replanned " + replansUsed + " times");
            return;
        }
        Optional<TaskPlan> fresh = replanner.take();
        if (fresh.isPresent()) {
            TaskPlan plan = fresh.get();
            try {
                begin(plan);
            } catch (RuntimeException refused) {
                abandonReplan("the new plan was refused: " + refused.getMessage());
                return;
            }
            replansUsed++;
            note("replan " + replansUsed + " of " + MAX_REPLANS + ": " + plan.tasks().size()
                    + " tasks for " + plan.goal());
            return;
        }
        if (!replanRequested) {
            if (++replanRequests > MAX_REPLAN_REQUESTS) {
                abandonReplan("the planner stayed unavailable");
                return;
            }
            if (replanner.request(runner.plan().goal(), situation(client), client)) {
                replanRequested = true;
                replanWaitTicks = 0;
                note("asking the planner for a new plan");
            }
            return;
        }
        if (++replanWaitTicks > REPLAN_TIMEOUT_TICKS) {
            abandonReplan("the planner did not answer in time");
        }
    }

    private void abandonReplan(String why) {
        replanExhausted = true;
        replanRequested = false;
        note("giving up on replanning: " + why);
    }

    private void runCurrent(Minecraft client, long nowMillis) {
        PlannedTask task = runner.current();
        if (task instanceof PlannedTask.Build buildTask) {
            runBuild(client, nowMillis, buildTask);
            return;
        }
        if (task instanceof PlannedTask.Travel travelTask) {
            runTravel(client, nowMillis, travelTask);
            return;
        }
        if (task instanceof PlannedTask.Deposit || task instanceof PlannedTask.Withdraw) {
            runTransfer(client, nowMillis, task, transfers);
            return;
        }
        if (task instanceof PlannedTask.Craft) {
            runTransfer(client, nowMillis, task, crafting);
            return;
        }
        if (task instanceof PlannedTask.Smelt) {
            runTransfer(client, nowMillis, task, smelting);
            return;
        }
        if (task instanceof PlannedTask.PlaceBlock) {
            runTransfer(client, nowMillis, task, placing);
            return;
        }
        if (task instanceof PlannedTask.Interact interactTask) {
            runInteract(client, nowMillis, interactTask);
            return;
        }
        if (task instanceof PlannedTask.Mine mineTask) {
            runGather(client, nowMillis, mineTask.blockId(), mineTask.itemId(), mineTask.count());
            return;
        }
        if (task instanceof PlannedTask.Gather gatherTask) {
            String blocks = GatherCatalog.supports(gatherTask.itemId())
                    ? GatherCatalog.blocksFor(gatherTask.itemId())
                    : gatherTask.itemId();
            runGather(client, nowMillis, blocks, gatherTask.itemId(), gatherTask.count());
            return;
        }
        finishTask(new TaskResult(TaskStatus.INVALID_TARGET,
                "No executor for " + task.describe(), 0, 0, 0));
    }

    private void runInteract(Minecraft client, long nowMillis, PlannedTask.Interact interactTask) {
        if (!taskStarted) {
            taskStarted = true;
            note("running " + interactTask.describe());
            try {
                interacting.start(interactTask, observeInteract(client), nowMillis);
            } catch (RuntimeException failure) {
                finishTask(new TaskResult(TaskStatus.FAILED,
                        "Could not start: " + failure.getMessage(), 0, 0, 0));
                return;
            }
        } else {
            interacting.tick(observeInteract(client), nowMillis);
        }
        if (!interacting.isRunning()) {
            finishTask(interacting.result());
        }
    }

    private BuildSnapshot observeInteract(Minecraft client) {
        if (client.level == null || client.player == null) {
            return new BuildSnapshot(false, false, "disconnected", 0, 0, false);
        }
        int remaining = 1;
        if (interactExecutor.target() != null && interactExecutor.before() != null
                && !client.level.getBlockState(interactExecutor.target()).equals(interactExecutor.before())) {
            remaining = 0;
        }
        if (interactExecutor.step() == MinecraftInteractExecutor.Step.FAILED) {
            return new BuildSnapshot(true, client.player.isAlive(),
                    FamulusClient.OBSERVER.worldKey(client), remaining, 1, false);
        }
        return new BuildSnapshot(true, client.player.isAlive() && !client.player.isRemoved(),
                FamulusClient.OBSERVER.worldKey(client), remaining, 1, true);
    }

    private void runGather(Minecraft client, long nowMillis, String blockId, String itemId, int count) {
        WorldSnapshot snapshot = FamulusClient.OBSERVER.observe(client, itemId);
        if (!taskStarted) {
            ToolCheck.Verdict tools = ToolCheck.assess(client, blockId);
            if (!tools.usable()) {
                if (insertToolCraft(client, tools)) {
                    return;
                }
                taskStarted = true;
                finishTask(new TaskResult(TaskStatus.RESOURCE_MISSING, tools.message(),
                        snapshot.itemCount(), count, 0));
                return;
            }
            ToolCheck.equipFor(client, blockId);
            taskStarted = true;
            note("running " + runner.current().describe());
            try {
                gather.start(new GatherTask(UUID.randomUUID().toString(), itemId, blockId, count),
                        snapshot, nowMillis);
            } catch (RuntimeException failure) {
                finishTask(new TaskResult(TaskStatus.FAILED,
                        "Could not start: " + failure.getMessage(), 0, count, 0));
                return;
            }
        } else {
            gather.tick(snapshot, nowMillis);
        }
        if (!gather.isRunning()) {
            finishTask(gather.result());
        }
    }

    private boolean insertToolCraft(Minecraft client, ToolCheck.Verdict tools) {
        String tool = tools.suggestedTool();
        if (tool == null || runner.insertedCount() >= PlanRunner.MAX_INSERTED_TASKS) {
            return false;
        }
        if (!MinecraftCraftExecutor.hasRecipeFor(client, tool)) {
            note("no recipe known for " + tool + ", cannot make one");
            return false;
        }
        PlannedTask craft = new PlannedTask.Craft(
                "tool-" + runner.insertedCount() + "-" + tool.replace(':', '-'), tool, 1);
        try {
            runner.insertBeforeCurrent(craft, tools.message());
        } catch (RuntimeException refused) {
            note("could not add a craft step: " + refused.getMessage());
            return false;
        }
        note("missing a tool, crafting " + tool + " first");
        return true;
    }

    private void explore(Minecraft client, long nowMillis) {
        if (!exploring) {
            exploring = true;
            exploreStartedAt = nowMillis;
            try {
                explorer.start(client);
                note("exploring for " + runner.current().describe());
            } catch (RuntimeException failure) {
                exploring = false;
                note("could not explore: " + failure.getMessage());
                runner.onExploreComplete("exploration could not start");
            }
            return;
        }
        long elapsed = nowMillis - exploreStartedAt;
        if (elapsed >= exploreTimeoutMillis || !explorer.isActive()) {
            explorer.cancel();
            exploring = false;
            String summary = elapsed >= exploreTimeoutMillis
                    ? "explored for " + (elapsed / 1000) + "s"
                    : "exploration ended after " + (elapsed / 1000) + "s";
            note(summary);
            runner.onExploreComplete(summary);
        }
    }

    private void runBuild(Minecraft client, long nowMillis, PlannedTask.Build buildTask) {
        if (!taskStarted) {
            taskStarted = true;
            note("running " + buildTask.describe());
            try {
                buildExecutor.load(buildTask);
            } catch (Exception unreadable) {
                finishTask(new TaskResult(TaskStatus.INVALID_TARGET, unreadable.getMessage(), 0, 0, 0));
                return;
            }
            try {
                builder.start(buildTask, observeBuild(client), nowMillis);
            } catch (RuntimeException failure) {
                finishTask(new TaskResult(TaskStatus.FAILED,
                        "Could not start: " + failure.getMessage(), 0, 0, 0));
                return;
            }
        } else {
            builder.tick(observeBuild(client), nowMillis);
        }
        if (!builder.isRunning()) {
            finishTask(builder.result());
        }
    }

    private void runTravel(Minecraft client, long nowMillis, PlannedTask.Travel travelTask) {
        if (!taskStarted) {
            taskStarted = true;
            note("running " + travelTask.describe());
            try {
                traveller.start(travelTask, observeTravel(client, travelTask), nowMillis);
            } catch (RuntimeException failure) {
                finishTask(new TaskResult(TaskStatus.FAILED,
                        "Could not start: " + failure.getMessage(), 0, 0, 0));
                return;
            }
        } else {
            traveller.tick(observeTravel(client, travelTask), nowMillis);
        }
        if (!traveller.isRunning()) {
            finishTask(traveller.result());
        }
    }

    private TravelSnapshot observeTravel(Minecraft client, PlannedTask.Travel travelTask) {
        if (client.level == null || client.player == null) {
            return new TravelSnapshot(false, false, "disconnected", 0);
        }
        double dx = client.player.getX() - (travelTask.x() + 0.5);
        double dy = client.player.getY() - travelTask.y();
        double dz = client.player.getZ() - (travelTask.z() + 0.5);
        return new TravelSnapshot(true, client.player.isAlive() && !client.player.isRemoved(),
                FamulusClient.OBSERVER.worldKey(client), Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private void runTransfer(Minecraft client, long nowMillis, PlannedTask transferTask,
                             DepositController controller) {
        if (!taskStarted) {
            taskStarted = true;
            note("running " + transferTask.describe());
            try {
                controller.start(transferTask, observeTransfer(client, transferTask), nowMillis);
            } catch (RuntimeException failure) {
                finishTask(new TaskResult(TaskStatus.FAILED,
                        "Could not start: " + failure.getMessage(), 0, 0, 0));
                return;
            }
        } else {
            controller.tick(observeTransfer(client, transferTask), nowMillis);
        }
        if (!controller.isRunning()) {
            finishTask(controller.result());
        }
    }

    private DepositSnapshot observeTransfer(Minecraft client, PlannedTask transferTask) {
        String itemId;
        if (transferTask instanceof PlannedTask.Deposit deposit) {
            itemId = deposit.itemId();
        } else if (transferTask instanceof PlannedTask.Withdraw withdraw) {
            itemId = withdraw.itemId();
        } else if (transferTask instanceof PlannedTask.Craft craft) {
            itemId = craft.itemId();
        } else if (transferTask instanceof PlannedTask.Smelt smelt) {
            itemId = smelt.itemId();
        } else {
            itemId = ((PlannedTask.PlaceBlock) transferTask).itemId();
        }
        if (client.level == null || client.player == null) {
            return new DepositSnapshot(false, false, "disconnected", 0, false);
        }
        int held = MinecraftObserver.countAll(client, java.util.List.of(itemId)).get(itemId);
        boolean storage = containerExecutor.step() != MinecraftContainerExecutor.Step.FAILED
                && craftExecutor.step() != MinecraftCraftExecutor.Step.FAILED
                && smeltExecutor.step() != MinecraftSmeltExecutor.Step.FAILED;
        return new DepositSnapshot(true, client.player.isAlive() && !client.player.isRemoved(),
                FamulusClient.OBSERVER.worldKey(client), held, storage);
    }

    private BuildSnapshot observeBuild(Minecraft client) {
        if (client.level == null || client.player == null) {
            return new BuildSnapshot(false, false, "disconnected", 0, 0, false);
        }
        SchematicAnalyzer.Progress progress =
                SchematicAnalyzer.measure(client, buildExecutor.schematic(), buildExecutor.origin());
        String worldKey = FamulusClient.OBSERVER.worldKey(client);
        return new BuildSnapshot(true, client.player.isAlive() && !client.player.isRemoved(),
                worldKey, progress.remaining(), progress.total(), progress.hasMaterials());
    }

    private void finishTask(TaskResult result) {
        taskStarted = false;
        note(result.status() + ": " + result.message());
        PlanStep next = runner.onTaskResult(result);
        if (next.isTerminal()) {
            note("plan " + next + ": " + runner.reason());
        }
    }

    private void consultPolicy(Minecraft client, long nowMillis) {
        Answer answer = pendingDecision.getAndSet(null);
        if (answer != null && answer.generation() == policyGeneration) {
            policyDispatched = false;
            note("policy chose " + answer.action() + " (" + gate.lastReason() + ")");
            PlanStep next = runner.onPolicyDecision(answer.action());
            if (next.isTerminal()) {
                note("plan " + next + ": " + runner.reason());
            }
            return;
        }
        if (!policyDispatched) {
            policyDispatched = true;
            policyWaitTicks = 0;
            long generation = ++policyGeneration;
            PolicyRequest request = new PolicyRequest(
                    describeSituation(client), runner.policyOptions());

            policyThread.execute(() -> pendingDecision.set(new Answer(generation, gate.next(request))));
            return;
        }
        if (++policyWaitTicks > POLICY_TIMEOUT_TICKS) {
            policyGeneration++;
            policyDispatched = false;
            note("policy did not answer in time; retrying the task");
            runner.onPolicyDecision(AgentAction.RECOVER);
        }
    }

    public String situation(Minecraft client) {
        if (runner == null || runner.current() == null) {
            return "No plan is running.";
        }
        List<String> recent = recentLog();
        return describeSituation(client)
                + "Why it stopped: " + runner.reason() + "\n"
                + "Recent events:\n"
                + String.join("\n", recent.subList(Math.max(0, recent.size() - 10), recent.size()));
    }

    private String describeSituation(Minecraft client) {
        TaskResult last = gather.result();
        StringBuilder text = new StringBuilder(256);
        text.append("Minecraft agent state.\n");
        text.append("Goal: ").append(runner.plan().goal()).append('\n');
        text.append("Plan: task ").append(runner.completedCount() + 1)
                .append(" of ").append(runner.taskCount()).append('\n');
        text.append("Current task: ").append(runner.current().describe()).append('\n');
        text.append("Attempt ").append(runner.attempts()).append(" of 3\n");
        text.append("Last result: ").append(last.status()).append(", ")
                .append(last.currentCount()).append('/').append(last.targetCount())
                .append(", ").append(last.message()).append('\n');
        if (client.player != null) {
            text.append("Player: alive=").append(client.player.isAlive())
                    .append(", health=").append(Math.round(client.player.getHealth())).append('\n');
        }
        List<String> remaining = runner.plan().tasks().stream()
                .skip(runner.completedCount() + 1L).map(PlannedTask::describe).toList();
        if (!remaining.isEmpty()) {
            text.append("Remaining after this: ").append(String.join("; ", remaining)).append('\n');
        }
        return text.toString();
    }

    public void stop(String reason) {
        gather.stop(reason);
        builder.stop(reason);
        traveller.stop(reason);
        transfers.stop(reason);
        crafting.stop(reason);
        smelting.stop(reason);
        placing.stop(reason);
        interacting.stop(reason);
        if (exploring) {
            explorer.cancel();
            exploring = false;
        }
        if (isRunning()) {
            runner.onTaskResult(new TaskResult(TaskStatus.CANCELLED, reason, 0, 0, 0));
            note("stopped: " + reason);
        }
        replanExhausted = true;
        replanRequested = false;
        pendingDecision.set(null);
        policyDispatched = false;
    }

    public String status() {
        if (runner == null) {
            return "No plan. Policy " + policyState + ".";
        }
        return runner.progress();
    }

    public PlanRunner runner() {
        return runner;
    }

    public List<String> recentLog() {
        return List.copyOf(log);
    }

    private void note(String line) {
        log.addLast(line);
        while (log.size() > 40) {
            log.removeFirst();
        }
        FamulusClient.LOGGER.info("[agent] {}", line);
    }
}
