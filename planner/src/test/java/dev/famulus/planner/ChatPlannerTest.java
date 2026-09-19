package dev.famulus.planner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.famulus.core.PlanRequest;
import dev.famulus.core.PlannerException;
import dev.famulus.core.TaskPlan;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatPlannerTest {
    private static final Set<String> GATHERABLE = Set.of("minecraft:oak_log", "minecraft:dirt");

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private ChatPlanner planner(String apiKey) {
        return new ChatPlanner(new PlannerConfig(endpoint(), "test/model", apiKey, Duration.ofSeconds(5)));
    }

    private static String reply(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    private static PlanRequest request() {
        return new PlanRequest("get me some wood", "inventory empty, overworld", GATHERABLE);
    }

    @Test
    void turnsAModelReplyIntoAPlan() throws Exception {
        response = reply("""
                {"goal":"wood","tasks":[{"type":"gather","item":"minecraft:oak_log","count":32}]}
                """);
        TaskPlan plan = planner("k").plan(request());
        assertEquals("wood", plan.goal());
        assertEquals(1, plan.tasks().size());
    }

    @Test
    void tellsTheModelExactlyWhatCanBeGathered() throws Exception {
        response = reply("{\"goal\":\"g\",\"tasks\":[{\"type\":\"gather\",\"item\":\"minecraft:dirt\",\"count\":1}]}");
        planner("k").plan(request());
        JsonObject body = JsonParser.parseString(lastBody.get()).getAsJsonObject();
        assertEquals("test/model", body.get("model").getAsString());
        String system = body.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("content").getAsString();
        assertTrue(system.contains("minecraft:oak_log"));
        assertTrue(system.contains("minecraft:dirt"));
        assertTrue(system.contains("inventory empty"), "The world state must reach the model");
        String user = body.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString();
        assertEquals("get me some wood", user);
    }

    @Test
    void sendsTheKeyWhenThereIsOneAndOmitsItForALocalServer() throws Exception {
        response = reply("{\"goal\":\"g\",\"tasks\":[{\"type\":\"gather\",\"item\":\"minecraft:dirt\",\"count\":1}]}");
        planner("secret-key").plan(request());
        assertEquals("Bearer secret-key", lastAuth.get());
        assertFalse(lastBody.get().contains("secret-key"));

        planner("").plan(request());
        assertNull(lastAuth.get(), "A local server should not be sent an Authorization header");
    }

    @Test
    void reportsHttpFailuresWithTheBody() {
        status = 402;
        response = "{\"error\":{\"message\":\"insufficient credits\"}}";
        PlannerException failure = assertThrows(PlannerException.class, () -> planner("k").plan(request()));
        assertTrue(failure.getMessage().contains("402"));
        assertTrue(failure.getMessage().contains("insufficient credits"));
    }

    @Test
    void reportsAnErrorObjectEvenOnATwoHundred() {
        response = "{\"error\":{\"message\":\"model not found\"}}";
        assertTrue(assertThrows(PlannerException.class, () -> planner("k").plan(request()))
                .getMessage().contains("model not found"));
    }

    @Test
    void rejectsMalformedEnvelopes() {
        response = "not json";
        assertThrows(PlannerException.class, () -> planner("k").plan(request()));
        response = "{\"choices\":[]}";
        assertThrows(PlannerException.class, () -> planner("k").plan(request()));
        response = "{\"choices\":[{}]}";
        assertThrows(PlannerException.class, () -> planner("k").plan(request()));
        response = "{\"choices\":[{\"message\":{}}]}";
        assertThrows(PlannerException.class, () -> planner("k").plan(request()));
    }

    @Test
    void anUnreachableLocalServerSaysSo() {
        PlannerConfig config = PlannerConfig.local("http://127.0.0.1:1/v1/chat/completions", "m");
        PlannerException failure = assertThrows(PlannerException.class,
                () -> new ChatPlanner(config).plan(request()));
        assertTrue(failure.getMessage().contains("Is the local server running"),
                "A local failure should suggest the obvious cause");
    }

    @Test
    void configurationValidatesEndpointAndModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig("not-a-url", "m", "", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig("https://x/y", " ", "", Duration.ofSeconds(1)));
        assertTrue(PlannerConfig.local(PlannerConfig.OLLAMA, "llama3").isLocal());
        assertFalse(PlannerConfig.openRouter("k", "m").isLocal());
        assertFalse(PlannerConfig.local(PlannerConfig.OLLAMA, "llama3").hasKey());
        assertTrue(PlannerConfig.suggestedModels().contains(PlannerConfig.DEFAULT_MODEL));
    }
}
