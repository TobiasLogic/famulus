package dev.famulus.planner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record PlannerConfig(String endpoint, String model, String apiKey, Duration timeout) {
    public static final String OPENROUTER = "https://openrouter.ai/api/v1/chat/completions";
    public static final String OLLAMA = "http://localhost:11434/v1/chat/completions";
    public static final String LLAMA_CPP = "http://localhost:8080/v1/chat/completions";
    public static final String LM_STUDIO = "http://localhost:1234/v1/chat/completions";

    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash";

    public static List<String> suggestedModels() {
        return List.of(
                "deepseek/deepseek-v4.1-flash",
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v4-pro",
                "anthropic/claude-sonnet-5",
                "openai/gpt-5",
                "google/gemini-3-pro",
                "qwen/qwen3-max");
    }

    public static List<String> suggestedEndpoints() {
        return List.of(OPENROUTER, OLLAMA, LLAMA_CPP, LM_STUDIO);
    }

    public PlannerConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(timeout, "timeout");
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("A planner endpoint is required");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Endpoint must be an http or https URL: " + endpoint);
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("A model must be chosen");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }

    public static PlannerConfig openRouter(String apiKey, String model) {
        return new PlannerConfig(OPENROUTER, model, apiKey, Duration.ofSeconds(90));
    }

    public static PlannerConfig local(String endpoint, String model) {
        return new PlannerConfig(endpoint, model, "", Duration.ofSeconds(180));
    }

    public boolean isLocal() {
        return endpoint.contains("localhost") || endpoint.contains("127.0.0.1");
    }

    public boolean hasKey() {
        return !apiKey.isBlank();
    }
}
