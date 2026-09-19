package dev.famulus.fabric;

import dev.famulus.core.GatherConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record FamulusConfig(GatherConfig gather, int observationIntervalTicks,
                            long exploreTimeoutMillis, String plannerEndpoint, String plannerModel) {
    public static FamulusConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, """
                    # Famulus deterministic gather settings. Restart the client after editing.
                    # Timeouts use monotonic wall time, including time spent paused.
                    stallTimeoutMillis=60000
                    taskTimeoutMillis=600000
                    retryDelayMillis=2000
                    maxAttempts=3
                    observationIntervalTicks=5
                    # How long to range outward when a resource cannot be found nearby.
                    exploreTimeoutMillis=90000
                    # The planner speaks the OpenAI chat completions shape, so any compatible server
                    # works: OpenRouter, Ollama, llama.cpp or LM Studio. A local one needs no key.
                    plannerEndpoint=https://openrouter.ai/api/v1/chat/completions
                    plannerModel=deepseek/deepseek-v4.1-flash
                    """);
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) { values.load(reader); }
        GatherConfig gather = new GatherConfig(
                number(values, "stallTimeoutMillis", 60000),
                number(values, "taskTimeoutMillis", 600000),
                number(values, "retryDelayMillis", 2000),
                Math.toIntExact(number(values, "maxAttempts", 3)));
        int interval = Math.toIntExact(number(values, "observationIntervalTicks", 5));
        if (interval < 1 || interval > 20) {
            throw new IllegalArgumentException("observationIntervalTicks must be between 1 and 20");
        }
        long exploreTimeout = number(values, "exploreTimeoutMillis", 90000);
        if (exploreTimeout < 1000) {
            throw new IllegalArgumentException("exploreTimeoutMillis must be at least 1000");
        }
        String plannerEndpoint = values.getProperty("plannerEndpoint",
                "https://openrouter.ai/api/v1/chat/completions").trim();
        String plannerModel = values.getProperty("plannerModel", "deepseek/deepseek-v4.1-flash").trim();
        return new FamulusConfig(gather, interval, exploreTimeout, plannerEndpoint, plannerModel);
    }

    private static long number(Properties values, String key, long fallback) {
        try { return Long.parseLong(values.getProperty(key, Long.toString(fallback)).trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer", e); }
    }
}
