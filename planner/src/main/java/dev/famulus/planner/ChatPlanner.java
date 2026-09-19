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
        return PlanParser.parse(content(response.body()), request.gatherableItems());
    }

    private String systemPrompt(PlanRequest request) {
        return """
               You plan tasks for a Minecraft agent. Reply with one JSON object and nothing else.

               Shape:
               {"goal": "short description",
                "tasks": [{"id": "t1", "type": "gather", "item": "minecraft:oak_log", "count": 32}]}

               Task types:
                 gather  - obtain an item. Needs "item" and "count".
                 deposit - put items in a container. Needs "item" and "count".
                 build   - place a saved blueprint. Needs "blueprint", optionally "x", "y", "z".

               Rules:
                 - "count" is the TOTAL the player should end up holding, not how many to collect.
                 - Maximum %d tasks, and no count above %d.
                 - Only these items can be gathered, and a plan naming anything else is rejected:
                   %s
                 - If the goal cannot be met with those items, still reply with JSON, using a goal
                   that says what is missing and an empty-but-valid single gather task is NOT
                   acceptable. Prefer to explain by choosing fewer tasks.
                 - No prose, no markdown fences, no comments. JSON only.

               Current state:
               %s
               """.formatted(PlanParser.MAX_TASKS, PlanParser.MAX_COUNT,
                String.join(", ", request.gatherableItems().stream().sorted().toList()),
                request.context());
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
