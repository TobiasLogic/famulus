package dev.famulus.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record GatherTask(String id, String itemId, String blockId, int targetCount) {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern RESOURCE_LIST =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+(,[a-z0-9_.-]+:[a-z0-9/._-]+)*");

    public GatherTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(blockId, "blockId");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
        if (!RESOURCE_ID.matcher(itemId).matches() || !RESOURCE_LIST.matcher(blockId).matches()) {
            throw new IllegalArgumentException("Item and block must be namespaced resource identifiers");
        }
        if (targetCount < 1) {
            throw new IllegalArgumentException("Target count must be positive");
        }
    }
}
