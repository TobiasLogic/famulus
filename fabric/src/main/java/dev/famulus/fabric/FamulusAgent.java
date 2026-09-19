package dev.famulus.fabric;

import dev.famulus.core.AgentAction;
import dev.famulus.core.GatherConfig;
import dev.famulus.core.BuildController;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

public final class FamulusAgent {
    private static final int POLICY_TIMEOUT_TICKS = 300;

    private final GatherController gather;
    private final BuildController builder;
    private final BaritoneBuildExecutor buildExecutor = new BaritoneBuildExecutor();
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

    public FamulusAgent(GatherConfig gatherConfig, PolicyGateConfig policyConfig,
                        CredentialStore credentials, long exploreTimeoutMillis) {
        this.gather = new GatherController(new BaritoneGatherExecutor(), gatherConfig);
        this.builder = new BuildController(buildExecutor, gatherConfig);
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

    public void start(TaskPlan plan) {
        if (isRunning()) {
            throw new IllegalStateException("A plan is already running. Use /famulus stop first.");
        }
        runner = new PlanRunner(plan, 3);
        taskStarted = false;
        exploring = false;
        policyDispatched = false;
        policyGeneration++;
        pendingDecision.set(null);
        gate.reset();
        log.clear();
        note("plan started: " + plan.goal() + " (" + plan.tasks().size() + " tasks)");
        runner.start();
    }

    public void tick(Minecraft client, long nowMillis) {
        if (!isRunning()) {
            return;
        }
        switch (runner.step()) {
            case RUN_CURRENT -> runCurrent(client, nowMillis);
            case CONSULT_POLICY -> consultPolicy(client, nowMillis);
            case EXPLORE -> explore(client, nowMillis);
            default -> { }
        }
    }

    private void runCurrent(Minecraft client, long nowMillis) {
        PlannedTask task = runner.current();
        if (task instanceof PlannedTask.Build buildTask) {
            runBuild(client, nowMillis, buildTask);
            return;
        }
        if (!(task instanceof PlannedTask.Gather gatherTask)) {
            finishTask(new TaskResult(TaskStatus.INVALID_TARGET,
                    "No executor for " + task.describe(), 0, 0, 0));
            return;
        }
        WorldSnapshot snapshot = FamulusClient.OBSERVER.observe(client, gatherTask.itemId());
        if (!taskStarted) {
            taskStarted = true;
            note("running " + task.describe());
            try {
                gather.start(new GatherTask(UUID.randomUUID().toString(),
                        gatherTask.itemId(), gatherTask.itemId(), gatherTask.count()),
                        snapshot, nowMillis);
            } catch (RuntimeException failure) {
                finishTask(new TaskResult(TaskStatus.FAILED,
                        "Could not start: " + failure.getMessage(), 0, gatherTask.count(), 0));
                return;
            }
        } else {
            gather.tick(snapshot, nowMillis);
        }
        if (!gather.isRunning()) {
            finishTask(gather.result());
        }
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
        if (exploring) {
            explorer.cancel();
            exploring = false;
        }
        if (isRunning()) {
            runner.onTaskResult(new TaskResult(TaskStatus.CANCELLED, reason, 0, 0, 0));
            note("stopped: " + reason);
        }
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
