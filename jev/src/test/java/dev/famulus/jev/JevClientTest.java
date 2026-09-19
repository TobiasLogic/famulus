package dev.famulus.jev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.famulus.core.AgentAction;
import dev.famulus.core.PolicyDecision;
import dev.famulus.core.PolicyException;
import dev.famulus.core.PolicyRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JevClientTest {
    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decisions", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private JevClient client() {
        return client(Duration.ofSeconds(5));
    }

    private JevClient client(Duration timeout) {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/decisions";
        return new JevClient(new JevConfig(endpoint, "typesafe/jev-1.13", "test-key", timeout));
    }

    private static PolicyRequest request() {
        Map<AgentAction, String> options = new LinkedHashMap<>();
        options.put(AgentAction.GATHER, "Keep gathering the current target item");
        options.put(AgentAction.DEPOSIT_ITEM, "Put items into a nearby chest");
        options.put(AgentAction.COMPLETE_TASK, "The task goal is met; finish it");
        return new PolicyRequest("32 of 32 oak logs held, chest 8 blocks away", options);
    }

    private static String reply(String choice, double confidence, double noul) {
        return """
               {"model":"typesafe/jev-1.13-20260917",
                "answers":{
                  "next_action":{"type":"choice","choice":"%s",
                    "probabilities":{"DEPOSIT_ITEM":0.89,"COMPLETE_TASK":0.07,"GATHER":0.04},
                    "confidence":%s},
                  "needs_replan":{"type":"noul","noul":%s}},
                "usage":{"input_tokens":608,"output_tokens":106,"cost":2.5536e-05}}
               """.formatted(choice, confidence, noul);
    }

    @Test
    void parsesAGenuineResponse() throws Exception {
        responseBody = reply("DEPOSIT_ITEM", 0.86, 0.04);
        PolicyDecision decision = client().decide(request());
        assertEquals(AgentAction.DEPOSIT_ITEM, decision.action());
        assertEquals(0.86, decision.confidence(), 1e-9);
        assertEquals(0.04, decision.replanUrgency(), 1e-9);
        assertEquals(0.89, decision.probabilityOf(AgentAction.DEPOSIT_ITEM), 1e-9);
        assertEquals(0.07, decision.probabilityOf(AgentAction.COMPLETE_TASK), 1e-9);
        assertEquals(0.0, decision.probabilityOf(AgentAction.ABORT_TASK), 1e-9);
    }

    @Test
    void sendsTheOfferedActionsAsTheChoiceCriteria() throws Exception {
        responseBody = reply("GATHER", 0.8, 0.0);
        client().decide(request());
        JsonObject body = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject();
        assertEquals("typesafe/jev-1.13", body.get("model").getAsString());
        assertTrue(body.get("state").getAsString().contains("32 of 32 oak logs"));

        JsonObject questions = body.getAsJsonObject("questions");
        JsonObject action = questions.getAsJsonObject("next_action");
        assertEquals("choice", action.get("type").getAsString());
        JsonObject criteria = action.getAsJsonObject("criteria");
        assertEquals(3, criteria.size());
        assertTrue(criteria.has("GATHER"));
        assertTrue(criteria.has("DEPOSIT_ITEM"));
        assertTrue(criteria.has("COMPLETE_TASK"));
        assertFalse(criteria.has("ABORT_TASK"), "Only offered actions may reach the model");
        assertEquals("noul", questions.getAsJsonObject("needs_replan").get("type").getAsString());
    }

    @Test
    void sendsTheBearerTokenAndNeverPutsItInTheBody() throws Exception {
        responseBody = reply("GATHER", 0.8, 0.0);
        client().decide(request());
        assertEquals("Bearer test-key", lastAuthHeader.get());
        assertFalse(lastRequestBody.get().contains("test-key"));
    }

    @Test
    void rejectsAnActionThatWasNotOffered() {
        responseBody = reply("ABORT_TASK", 0.99, 0.0);
        PolicyException failure = assertThrows(PolicyException.class, () -> client().decide(request()));
        assertTrue(failure.getMessage().contains("not offered"));
    }

    @Test
    void rejectsAnActionOutsideTheEnum() {
        responseBody = reply("BURN_THE_FOREST", 0.99, 0.0);
        PolicyException failure = assertThrows(PolicyException.class, () -> client().decide(request()));
        assertTrue(failure.getMessage().contains("unrecognised"));
    }

    @Test
    void reportsHttpFailuresWithTheBodyBecauseItIsUsuallyActionable() {
        status = 400;
        responseBody = "{\"error\":{\"message\":\"typesafe/jev-1.13 is a decisions model and cannot be "
                + "used with the chat/completions endpoint. Use the /api/alpha/decisions endpoint instead.\"}}";
        PolicyException failure = assertThrows(PolicyException.class, () -> client().decide(request()));
        assertTrue(failure.getMessage().contains("HTTP 400"));
        assertTrue(failure.getMessage().contains("/api/alpha/decisions"));
    }

    @Test
    void rejectsMalformedAndIncompleteResponses() {
        responseBody = "not json at all";
        assertThrows(PolicyException.class, () -> client().decide(request()));

        responseBody = "[]";
        assertThrows(PolicyException.class, () -> client().decide(request()));

        responseBody = "{\"usage\":{}}";
        PolicyException missingAnswers = assertThrows(PolicyException.class, () -> client().decide(request()));
        assertTrue(missingAnswers.getMessage().contains("answers"));

        responseBody = "{\"answers\":{}}";
        assertThrows(PolicyException.class, () -> client().decide(request()));

        responseBody = "{\"answers\":{\"next_action\":{\"type\":\"choice\"}}}";
        assertThrows(PolicyException.class, () -> client().decide(request()));
    }

    @Test
    void toleratesAMissingReplanAnswerAndMissingConfidence() throws Exception {
        responseBody = "{\"answers\":{\"next_action\":{\"type\":\"choice\",\"choice\":\"GATHER\"}}}";
        PolicyDecision decision = client().decide(request());
        assertEquals(AgentAction.GATHER, decision.action());
        assertEquals(0.0, decision.confidence(), 1e-9);
        assertEquals(0.0, decision.replanUrgency(), 1e-9);
    }

    @Test
    void clampsOutOfRangeNumbersRatherThanFailingTheWholeDecision() throws Exception {
        responseBody = """
                       {"answers":{"next_action":{"type":"choice","choice":"GATHER",
                         "probabilities":{"GATHER":1.8,"DEPOSIT_ITEM":-0.5},"confidence":2.0},
                         "needs_replan":{"type":"noul","noul":-3}}}
                       """;
        PolicyDecision decision = client().decide(request());
        assertEquals(1.0, decision.confidence(), 1e-9);
        assertEquals(0.0, decision.replanUrgency(), 1e-9);
        assertEquals(1.0, decision.probabilityOf(AgentAction.GATHER), 1e-9);
        assertEquals(0.0, decision.probabilityOf(AgentAction.DEPOSIT_ITEM), 1e-9);
    }

    @Test
    void ignoresUnknownActionNamesInsideProbabilities() throws Exception {
        responseBody = """
                       {"answers":{"next_action":{"type":"choice","choice":"GATHER",
                         "probabilities":{"GATHER":0.9,"SOMETHING_ELSE":0.1},"confidence":0.7}}}
                       """;
        PolicyDecision decision = client().decide(request());
        assertEquals(0.9, decision.probabilityOf(AgentAction.GATHER), 1e-9);
    }

    @Test
    void timesOutRatherThanBlockingForever() {
        delayMillis = 2000;
        responseBody = reply("GATHER", 0.9, 0.0);
        PolicyException failure = assertThrows(PolicyException.class,
                () -> client(Duration.ofMillis(250)).decide(request()));
        assertNotNull(failure.getMessage());
    }

    @Test
    void configRefusesABlankKeyWithAnActionableMessage() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new JevConfig("https://example.invalid", "m", "  ", Duration.ofSeconds(1)));
        assertTrue(blank.getMessage().contains(JevConfig.API_KEY_VARIABLE));
        assertThrows(IllegalArgumentException.class,
                () -> new JevConfig("https://example.invalid", "m", "k", Duration.ZERO));
    }
}
