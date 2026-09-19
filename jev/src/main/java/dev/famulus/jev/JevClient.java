package dev.famulus.jev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.famulus.core.AgentAction;
import dev.famulus.core.PolicyClient;
import dev.famulus.core.PolicyDecision;
import dev.famulus.core.PolicyException;
import dev.famulus.core.PolicyRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class JevClient implements PolicyClient {
    private static final String ACTION_QUESTION = "next_action";
    private static final String REPLAN_QUESTION = "needs_replan";

    private final JevConfig config;
    private final HttpClient http;

    public JevClient(JevConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    JevClient(JevConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public PolicyDecision decide(PolicyRequest request) throws PolicyException {
        Objects.requireNonNull(request, "request");
        HttpResponse<String> response;
        try {
            response = http.send(build(request), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new PolicyException("Jev request failed: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PolicyException("Jev request was interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new PolicyException("Jev returned HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body()));
        }
        return parse(response.body(), request);
    }

    private HttpRequest build(PolicyRequest request) {
        JsonObject criteria = new JsonObject();
        request.options().forEach((action, description) -> criteria.addProperty(action.name(), description));

        JsonObject action = new JsonObject();
        action.addProperty("type", "choice");
        action.addProperty("instructions", "Choose the single next action for the Minecraft agent to take.");
        action.add("criteria", criteria);

        JsonObject replan = new JsonObject();
        replan.addProperty("type", "noul");
        replan.addProperty("instructions",
                "This situation needs the planning model to reconsider the plan, "
                        + "rather than being handled by the listed actions.");

        JsonObject questions = new JsonObject();
        questions.add(ACTION_QUESTION, action);

        questions.add(REPLAN_QUESTION, replan);

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("state", request.state());
        body.add("questions", questions);

        return HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private PolicyDecision parse(String body, PolicyRequest request) throws PolicyException {
        JsonObject answers = object(root(body), "answers", "response");
        JsonObject choice = object(answers, ACTION_QUESTION, "answers");

        AgentAction action = AgentAction.parse(string(choice, "choice"));
        if (action == null) {
            throw new PolicyException("Jev returned an unrecognised action: "
                    + abbreviate(string(choice, "choice")));
        }
        if (!request.options().containsKey(action)) {
            throw new PolicyException("Jev returned " + action + ", which was not offered");
        }

        Map<AgentAction, Double> probabilities = new EnumMap<>(AgentAction.class);
        if (choice.has("probabilities") && choice.get("probabilities").isJsonObject()) {
            for (var entry : choice.getAsJsonObject("probabilities").entrySet()) {
                AgentAction candidate = AgentAction.parse(entry.getKey());
                if (candidate != null && entry.getValue().isJsonPrimitive()) {
                    probabilities.put(candidate, clamp(entry.getValue().getAsDouble()));
                }
            }
        }

        double confidence = clamp(number(choice, "confidence", 0.0));
        double replanUrgency = 0.0;
        if (answers.has(REPLAN_QUESTION) && answers.get(REPLAN_QUESTION).isJsonObject()) {
            replanUrgency = clamp(number(answers.getAsJsonObject(REPLAN_QUESTION), "noul", 0.0));
        }
        return new PolicyDecision(action, confidence, probabilities, replanUrgency);
    }

    private static JsonObject root(String body) throws PolicyException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new PolicyException("Jev response was not a JSON object: " + abbreviate(body));
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException malformed) {
            throw new PolicyException("Jev response was not valid JSON: " + abbreviate(body), malformed);
        }
    }

    private static JsonObject object(JsonObject parent, String field, String where) throws PolicyException {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new PolicyException("Jev response is missing '" + field + "' in " + where);
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static double number(JsonObject parent, String field, double fallback) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsDouble();
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<none>";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }
}
