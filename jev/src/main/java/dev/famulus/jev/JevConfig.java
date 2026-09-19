package dev.famulus.jev;

import java.time.Duration;
import java.util.Objects;

public record JevConfig(String endpoint, String model, String apiKey, Duration timeout) {
    public static final String OPENROUTER_DECISIONS = "https://openrouter.ai/api/alpha/decisions";

    public static final String TYPESAFE_SYSTEMONE = "https://api.typesafe.ai/v1/systemone";

    public static final String DEFAULT_MODEL = "typesafe/jev-1.13";
    public static final String API_KEY_VARIABLE = "OPENROUTER_API_KEY";

    public JevConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(timeout, "timeout");
        if (endpoint.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("Endpoint and model must not be blank");
        }
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing API key. Set " + API_KEY_VARIABLE + " in the environment.");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }

    public static JevConfig withKey(String apiKey) {
        return new JevConfig(OPENROUTER_DECISIONS, DEFAULT_MODEL, apiKey, Duration.ofSeconds(10));
    }

    public static JevConfig fromEnvironment() {
        String key = System.getenv(API_KEY_VARIABLE);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(API_KEY_VARIABLE
                    + " is not set. Export it before starting Minecraft; never commit it.");
        }
        return new JevConfig(OPENROUTER_DECISIONS, DEFAULT_MODEL, key, Duration.ofSeconds(10));
    }
}
