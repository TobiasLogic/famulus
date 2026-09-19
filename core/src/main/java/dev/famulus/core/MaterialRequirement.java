package dev.famulus.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record MaterialRequirement(String itemId, int needed, int have) {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public MaterialRequirement {
        Objects.requireNonNull(itemId, "itemId");
        if (!RESOURCE_ID.matcher(itemId).matches()) {
            throw new IllegalArgumentException("Item must be a namespaced identifier: " + itemId);
        }
        if (needed < 1) {
            throw new IllegalArgumentException("A requirement of zero should not exist: " + itemId);
        }
        if (have < 0) {
            throw new IllegalArgumentException("Held count must not be negative: " + itemId);
        }
    }

    public int shortfall() {
        return Math.max(0, needed - have);
    }

    public boolean satisfied() {
        return have >= needed;
    }

    public int shortfallStacks() {
        return (shortfall() + 63) / 64;
    }
}
