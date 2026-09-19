package dev.famulus.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.famulus.core.PlanRequest;
import dev.famulus.core.PlannerClient;
import dev.famulus.core.PlannerException;
import dev.famulus.core.TaskPlan;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ChatPlanner implements PlannerClient {
    private final PlannerConfig config;
    private final HttpClient http;

    public ChatPlanner(PlannerConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    ChatPlanner(PlannerConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public TaskPlan plan(PlanRequest request) throws PlannerException {
        Objects.requireNonNull(request, "request");
        HttpResponse<String> response;
        try {
            response = http.send(build(request), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            String hint = config.isLocal()
                    ? " Is the local server running at " + config.endpoint() + "?"
                    : "";
            throw new PlannerException("Could not reach the planner: " + failure.getMessage() + hint,
                    failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PlannerException("Planning was interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new PlannerException("The planner returned HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        return PlanParser.parse(content(response.body()), request.gatherableItems(),
                request.smeltRecipes());
    }

    private String systemPrompt(PlanRequest request) {
        return """
               You plan tasks for a Minecraft agent. Reply with one JSON object and nothing else.

               Shape:
               {"goal": "short description",
                "tasks": [{"id": "t1", "type": "gather", "item": "minecraft:oak_log", "count": 32}]}

               Task types, with the fields each one needs:
                 gather   - obtain an item by breaking whatever normally drops it.
                            "item", "count".
                 mine     - break named blocks for their drop. "item", "count", and "block",
                            which is one block id or a list of them. Use this only for items
                            outside the list below, and only when you know which block drops
                            the item; a wrong pairing just wastes the attempt.
                 craft    - craft an item using the recipe book. "item", "count". The agent
                            opens a crafting table if one is within reach, otherwise it uses
                            the 2x2 grid, so plan a table first for anything larger.
                 smelt    - cook something in a furnace. "item", "count", and optionally
                            "input" when the default below is not what you have. The agent
                            needs a furnace within a few blocks and fuel in the inventory;
                            coal, charcoal or planks all burn.
                 place    - put one held block into the world. "item", "x", "y", "z".
                 build    - place a saved blueprint. "blueprint", optionally "x", "y", "z".
                 travel   - walk somewhere. "x", "z", optionally "y".
                 deposit  - move items from the inventory into the nearest container.
                            "item", "count".
                 withdraw - take items out of the nearest container. "item", "count".
                 interact - right click the nearest block of a kind, for a lever, button,
                            door or bed. "target".
                 eat      - eat until full, from whatever food is carried. Optionally "food"
                            for the level to stop at, out of 20. The agent also eats on its
                            own when it drops to 6, so plan this only when you want it fed
                            before something specific.

               Rules:
                 - For gather and mine, "count" is the TOTAL to end up holding, so a gather
                   of 32 when 20 are already held collects 12.
                 - For craft and smelt, "count" is how many MORE to make.
                 - For deposit and withdraw, "count" is how many to move.
                 - Maximum %d tasks, and no count above %d.
                 - Coordinates are absolute world positions, not offsets. Only give ones you
                   can justify from the state below.
                 - deposit and withdraw need a container the agent can already reach. Add a
                   travel task first if the state below does not mention one nearby.
                 - These items can be gathered by name, and a gather naming anything else is
                   rejected outright:
                   %s
                 - These can be smelted, shown as the result from its usual input:
                   %s
                 - Order matters. Materials before the craft that consumes them, the crafting
                   table before the craft, the travel before the deposit.
                 - Breaking stone, ore and metal needs a pickaxe good enough for it, and a wooden
                   one will not drop iron or a stone one diamond. The state below lists the tools
                   being carried, so craft the pickaxe first when it is missing.
                 - Prefer few tasks. If the goal cannot be met, say so in "goal" and give the
                   tasks that get closest rather than inventing ones that will fail.
                 - No prose, no markdown fences, no comments. JSON only.

               Current state:
               %s
               """.formatted(PlanParser.MAX_TASKS, PlanParser.MAX_COUNT,
                String.join(", ", request.gatherableItems().stream().sorted().toList()),
                describeSmelting(request),
                request.context());
    }

    private static String describeSmelting(PlanRequest request) {
        if (request.smeltRecipes().isEmpty()) {
            return "nothing, no furnace recipes are configured";
        }
        return request.smeltRecipes().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(recipe -> recipe.getKey() + " from " + recipe.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("nothing");
    }

    private HttpRequest build(PlanRequest request) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt(request)));
        messages.add(message("user", request.goal()));

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("messages", messages);

        body.addProperty("temperature", 0.2);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (config.hasKey()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        return builder.build();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    static String content(String body) throws PlannerException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new PlannerException("The planner's response was not a JSON object.");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException malformed) {
            throw new PlannerException("The planner's response was not valid JSON: "
                    + abbreviate(body), malformed);
        }
        JsonElement choices = root.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
            JsonElement error = root.get("error");
            if (error != null) {
                throw new PlannerException("The planner reported an error: " + abbreviate(error.toString()));
            }
            throw new PlannerException("The planner returned no choices.");
        }
        JsonElement first = choices.getAsJsonArray().get(0);
        if (!first.isJsonObject()) {
            throw new PlannerException("The planner's first choice was not an object.");
        }
        JsonElement message = first.getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) {
            throw new PlannerException("The planner's reply had no message.");
        }
        JsonElement content = message.getAsJsonObject().get("content");
        if (content == null || !content.isJsonPrimitive()) {
            throw new PlannerException("The planner's reply had no content.");
        }
        return content.getAsString();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<none>";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }
}
