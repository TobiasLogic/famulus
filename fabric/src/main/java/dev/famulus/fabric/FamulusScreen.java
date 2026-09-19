package dev.famulus.fabric;

import dev.famulus.core.MaterialList;
import dev.famulus.core.MaterialRequirement;
import dev.famulus.core.PlannedTask;
import dev.famulus.core.TaskPlan;
import dev.famulus.jev.CredentialStore;
import dev.famulus.jev.JevConfig;
import dev.famulus.planner.PlannerConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class FamulusScreen extends Screen {
    private static final int TAB_BAR_HEIGHT = 24;
    private static final int ROW_WIDTH = 380;
    private static final int ROW_HEIGHT = 11;
    private static final int LOG_ROWS = 8;
    private static final int MATERIAL_ROWS = 7;

    private final FamulusAgent agent;
    private final CredentialStore credentials;
    private final PlannerService planner;
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    private AgentTab agentTab;
    private ChatTab chatTab;
    private BuildTab buildTab;
    private SettingsTab settingsTab;
    private MenuTabBar tabBar;

    private final int initialTab;

    public FamulusScreen(FamulusAgent agent, CredentialStore credentials,
                         PlannerService planner, int initialTab) {
        super(Component.literal("Famulus"));
        this.agent = agent;
        this.credentials = credentials;
        this.planner = planner;
        this.initialTab = initialTab;
    }

    @Override
    protected void init() {
        agentTab = new AgentTab();
        chatTab = new ChatTab();
        buildTab = new BuildTab();
        settingsTab = new SettingsTab();
        tabBar = MenuTabBar.builder(tabManager, width)
                .addTabs(agentTab, chatTab, buildTab, settingsTab)
                .build();
        addRenderableWidget(tabBar);
        tabBar.selectTab(Math.max(0, Math.min(initialTab, 3)), false);
        repositionElements();
        refresh();
    }

    @Override
    public void repositionElements() {
        if (tabBar == null) {
            return;
        }
        tabBar.setWidth(width);
        tabBar.arrangeElements(width);
        tabManager.setTabArea(new ScreenRectangle(0, TAB_BAR_HEIGHT, width, height - TAB_BAR_HEIGHT));
    }

    @Override
    public void tick() {
        refresh();
    }

    private void refresh() {
        if (agentTab != null) {
            agentTab.refresh();
        }
        if (settingsTab != null) {
            settingsTab.refresh();
        }
        if (chatTab != null) {
            chatTab.refresh();
        }
    }

    private static Component row(String text) {
        String trimmed = text == null ? "" : text;
        return Component.literal(trimmed.length() <= 58 ? trimmed : trimmed.substring(0, 55) + "...");
    }

    private StringWidget line(GridLayout grid, int rowIndex, String text) {
        StringWidget widget = new StringWidget(ROW_WIDTH, ROW_HEIGHT, row(text), font);
        grid.addChild(widget, rowIndex, 0);
        return widget;
    }

    private final class AgentTab extends GridLayoutTab {
        private final StringWidget status;
        private final StringWidget policy;
        private final List<StringWidget> logLines = new ArrayList<>();

        AgentTab() {
            super(Component.literal("Agent"));
            layout.spacing(2);
            int nextRow = 0;
            status = line(layout, nextRow++, "no plan");
            policy = line(layout, nextRow++, "policy");
            line(layout, nextRow++, "-- recent --");
            for (int i = 0; i < LOG_ROWS; i++) {
                logLines.add(line(layout, nextRow++, ""));
            }
            layout.addChild(Button.builder(Component.literal("Stop"),
                    button -> agent.stop("Stopped from the screen")).width(80).build(), nextRow, 0);
        }

        void refresh() {
            status.setMessage(row(agent.status()));
            policy.setMessage(row("policy: " + agent.policyState()));
            List<String> recent = agent.recentLog();
            int from = Math.max(0, recent.size() - LOG_ROWS);
            for (int i = 0; i < LOG_ROWS; i++) {
                int index = from + i;
                logLines.get(i).setMessage(row(index < recent.size() ? recent.get(index) : ""));
            }
        }
    }

    private final class ChatTab extends GridLayoutTab {
        private final EditBox goalField;
        private final StringWidget status;
        private final List<StringWidget> planLines = new ArrayList<>();
        private final Button runButton;
        private TaskPlan pending;

        ChatTab() {
            super(Component.literal("Chat"));
            layout.spacing(3);
            int nextRow = 0;
            line(layout, nextRow++, "Ask for something. The planner turns it into tasks.");
            goalField = new EditBox(font, ROW_WIDTH, 18, Component.literal("goal"));
            goalField.setMaxLength(300);
            goalField.setHint(Component.literal("get me wood and dirt for a shelter"));
            layout.addChild(goalField, nextRow++, 0);

            GridLayout buttons = new GridLayout().spacing(4);
            buttons.addChild(Button.builder(Component.literal("Plan"), b -> requestPlan()).width(72).build(), 0, 0);
            runButton = Button.builder(Component.literal("Run plan"), b -> runPlan()).width(88).build();
            runButton.active = false;
            buttons.addChild(runButton, 0, 1);
            buttons.addChild(Button.builder(Component.literal("Stop"),
                    b -> agent.stop("Stopped from the screen")).width(72).build(), 0, 2);
            layout.addChild(buttons, nextRow++, 0);

            status = line(layout, nextRow++, "idle");
            for (int i = 0; i < 6; i++) {
                planLines.add(line(layout, nextRow++, ""));
            }
        }

        private void requestPlan() {
            String goal = goalField.getValue().trim();
            if (goal.isEmpty()) {
                status.setMessage(row("type what you want first"));
                return;
            }
            pending = null;
            runButton.active = false;
            planLines.forEach(widget -> widget.setMessage(Component.empty()));
            if (!planner.request(goal, minecraft)) {
                status.setMessage(row(planner.state()));
            }
        }

        private void runPlan() {
            if (pending == null) {
                return;
            }
            try {
                agent.start(pending);
                status.setMessage(row("running: " + pending.goal()));
                runButton.active = false;
                pending = null;
            } catch (RuntimeException refused) {
                status.setMessage(row(refused.getMessage()));
            }
        }

        void refresh() {
            planner.takePlan().ifPresent(plan -> {
                pending = plan;
                runButton.active = true;
                for (int i = 0; i < planLines.size(); i++) {
                    planLines.get(i).setMessage(row(i < plan.tasks().size()
                            ? (i + 1) + ". " + plan.tasks().get(i).describe() : ""));
                }
                if (plan.tasks().size() > planLines.size()) {
                    planLines.get(planLines.size() - 1).setMessage(
                            row("... and " + (plan.tasks().size() - planLines.size() + 1) + " more"));
                }
            });
            if (pending == null && !planner.isBusy()) {
                runButton.active = false;
            }
            status.setMessage(row(agent.isRunning() ? agent.status() : planner.state()));
        }
    }

    private final class BuildTab extends GridLayoutTab {
        private final StringWidget selection;
        private final StringWidget summary;
        private final List<StringWidget> materialLines = new ArrayList<>();
        private List<Path> schematics = List.of();
        private int index;

        BuildTab() {
            super(Component.literal("Build"));
            layout.spacing(2);
            int nextRow = 0;
            selection = line(layout, nextRow++, "no blueprints found");
            summary = line(layout, nextRow++, "");
            for (int i = 0; i < MATERIAL_ROWS; i++) {
                materialLines.add(line(layout, nextRow++, ""));
            }
            GridLayout buttons = new GridLayout().spacing(4);
            buttons.addChild(Button.builder(Component.literal("Rescan"), b -> rescan()).width(64).build(), 0, 0);
            buttons.addChild(Button.builder(Component.literal("Next"), b -> cycle()).width(64).build(), 0, 1);
            buttons.addChild(Button.builder(Component.literal("Materials"), b -> showMaterials()).width(80).build(), 0, 2);
            buttons.addChild(Button.builder(Component.literal("Collect"), b -> collect()).width(72).build(), 0, 3);
            layout.addChild(buttons, nextRow, 0);
            rescan();
        }

        private void rescan() {
            try {
                schematics = SchematicAnalyzer.listSchematics();
            } catch (Exception failure) {
                schematics = List.of();
                summary.setMessage(row("could not read the schematic folder: " + failure.getMessage()));
            }
            index = 0;
            updateSelection();
        }

        private void cycle() {
            if (!schematics.isEmpty()) {
                index = (index + 1) % schematics.size();
                updateSelection();
            }
        }

        private void updateSelection() {
            if (schematics.isEmpty()) {
                selection.setMessage(row("no blueprints in config/famulus/schematics"));
                summary.setMessage(row("formats: "
                        + String.join(", ", SchematicAnalyzer.supportedExtensions())));
                return;
            }
            selection.setMessage(row("[" + (index + 1) + "/" + schematics.size() + "] "
                    + schematics.get(index).getFileName()));
        }

        private MaterialList analyse() throws Exception {
            SchematicSummary parsed = SchematicAnalyzer.analyze(schematics.get(index));
            summary.setMessage(row(parsed.dimensions() + ", " + parsed.totalBlocks() + " blocks, "
                    + parsed.itemCounts().size() + " item types"));
            return MaterialList.of(parsed.itemCounts(),
                    MinecraftObserver.countAll(minecraft, parsed.itemCounts().keySet()));
        }

        private void showMaterials() {
            if (schematics.isEmpty()) {
                return;
            }
            clearMaterials();
            try {
                MaterialList list = analyse();
                List<MaterialRequirement> requirements = list.requirements();
                for (int i = 0; i < Math.min(MATERIAL_ROWS, requirements.size()); i++) {
                    MaterialRequirement requirement = requirements.get(i);
                    materialLines.get(i).setMessage(row(String.format("%s  need %d  have %d  short %d",
                            requirement.itemId(), requirement.needed(),
                            requirement.have(), requirement.shortfall())));
                }
                if (requirements.size() > MATERIAL_ROWS) {
                    materialLines.get(MATERIAL_ROWS - 1).setMessage(
                            row("... and " + (requirements.size() - MATERIAL_ROWS + 1) + " more"));
                }
            } catch (Exception failure) {
                summary.setMessage(row(failure.getMessage()));
            }
        }

        private void collect() {
            if (schematics.isEmpty()) {
                return;
            }
            clearMaterials();
            try {
                MaterialList list = analyse();
                if (list.isSatisfied()) {
                    summary.setMessage(row("everything needed is already held"));
                    return;
                }
                List<MaterialRequirement> blocked = list.unobtainable(GatherCatalog::supports);
                if (!blocked.isEmpty()) {
                    summary.setMessage(row("no gather path for "
                            + blocked.get(0).itemId() + (blocked.size() > 1
                            ? " and " + (blocked.size() - 1) + " more" : "")));
                    return;
                }
                List<PlannedTask> tasks = new ArrayList<>();
                int number = 0;
                for (MaterialRequirement requirement : list.gatherable(GatherCatalog::supports)) {
                    if (requirement.needed() > 2304) {
                        summary.setMessage(row(requirement.itemId() + " needs more than an inventory holds"));
                        return;
                    }
                    tasks.add(GatherCatalog.task("t" + (++number),
                            requirement.itemId(), requirement.needed()));
                }
                agent.start(new TaskPlan("collect materials for "
                        + schematics.get(index).getFileName(), tasks));
                summary.setMessage(row("collecting: " + tasks.size() + " tasks"));
            } catch (Exception failure) {
                summary.setMessage(row(failure.getMessage()));
            }
        }

        private void clearMaterials() {
            materialLines.forEach(widget -> widget.setMessage(Component.empty()));
        }
    }

    private final class SettingsTab extends GridLayoutTab {
        private final EditBox keyField;
        private final StringWidget keyStatus;
        private final StringWidget note;
        private final EditBox modelField;
        private final EditBox endpointField;
        private final StringWidget plannerNote;

        SettingsTab() {
            super(Component.literal("Settings"));
            layout.spacing(3);
            int nextRow = 0;
            line(layout, nextRow++, "OpenRouter API key (used for Jev and the planner)");
            keyField = new EditBox(font, ROW_WIDTH, 18, Component.literal("API key"));
            keyField.setMaxLength(200);
            keyField.setHint(Component.literal("paste a key, then Save"));
            layout.addChild(keyField, nextRow++, 0);
            keyStatus = line(layout, nextRow++, "");

            GridLayout buttons = new GridLayout().spacing(4);
            buttons.addChild(Button.builder(Component.literal("Save"), b -> saveKey()).width(72).build(), 0, 0);
            buttons.addChild(Button.builder(Component.literal("Clear"), b -> clearKey()).width(72).build(), 0, 1);
            layout.addChild(buttons, nextRow++, 0);

            note = line(layout, nextRow++, "");

            line(layout, nextRow++, "Planner model. Any OpenAI-compatible server works.");
            modelField = new EditBox(font, ROW_WIDTH, 18, Component.literal("model"));
            modelField.setMaxLength(120);
            modelField.setValue(planner.model());
            layout.addChild(modelField, nextRow++, 0);
            endpointField = new EditBox(font, ROW_WIDTH, 18, Component.literal("endpoint"));
            endpointField.setMaxLength(200);
            endpointField.setValue(planner.endpoint());
            layout.addChild(endpointField, nextRow++, 0);

            GridLayout presets = new GridLayout().spacing(4);
            presets.addChild(Button.builder(Component.literal("OpenRouter"),
                    b -> preset(PlannerConfig.OPENROUTER, PlannerConfig.DEFAULT_MODEL)).width(88).build(), 0, 0);
            presets.addChild(Button.builder(Component.literal("Ollama"),
                    b -> preset(PlannerConfig.OLLAMA, "llama3.2")).width(72).build(), 0, 1);
            presets.addChild(Button.builder(Component.literal("llama.cpp"),
                    b -> preset(PlannerConfig.LLAMA_CPP, "local-model")).width(80).build(), 0, 2);
            presets.addChild(Button.builder(Component.literal("Apply"),
                    b -> applyPlanner()).width(64).build(), 0, 3);
            layout.addChild(presets, nextRow++, 0);
            plannerNote = line(layout, nextRow++, "");

            line(layout, nextRow++, "Saved owner-only to config/famulus/" + CredentialStore.FILE_NAME);
            line(layout, nextRow++, "The " + JevConfig.API_KEY_VARIABLE + " variable overrides it.");
            line(layout, nextRow++, "Without a key the agent runs; failures just retry.");
        }

        private void saveKey() {
            String typed = keyField.getValue().trim();
            if (typed.isEmpty()) {
                note.setMessage(row("type a key first"));
                return;
            }
            try {
                credentials.save(typed);

                keyField.setValue("");
                agent.reloadPolicy();
                note.setMessage(row("saved; policy reloaded"));
            } catch (Exception failure) {
                note.setMessage(row("could not save: " + failure.getMessage()));
            }
        }

        private void clearKey() {
            try {
                credentials.clear();
                keyField.setValue("");
                agent.reloadPolicy();
                note.setMessage(row("cleared"));
            } catch (Exception failure) {
                note.setMessage(row("could not clear: " + failure.getMessage()));
            }
        }

        private void preset(String endpoint, String model) {
            endpointField.setValue(endpoint);
            modelField.setValue(model);
            plannerNote.setMessage(row("press Apply to use it"));
        }

        private void applyPlanner() {
            String endpoint = endpointField.getValue().trim();
            String model = modelField.getValue().trim();
            if (endpoint.isEmpty() || model.isEmpty()) {
                plannerNote.setMessage(row("both a model and an endpoint are needed"));
                return;
            }
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                plannerNote.setMessage(row("endpoint must start with http:// or https://"));
                return;
            }
            planner.configure(endpoint, model);
            boolean local = endpoint.contains("localhost") || endpoint.contains("127.0.0.1");
            plannerNote.setMessage(row(local
                    ? "using local " + model + "; no key needed"
                    : "using " + model));
        }

        void refresh() {
            String stored = credentials.stored().map(CredentialStore::mask).orElse("not set");
            keyStatus.setMessage(row("stored key: " + stored
                    + (credentials.isOverriddenByEnvironment()
                    ? "  (environment variable is overriding it)" : "")));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
