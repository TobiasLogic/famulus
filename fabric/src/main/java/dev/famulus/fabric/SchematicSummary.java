package dev.famulus.fabric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record SchematicSummary(String name, int widthX, int heightY, int lengthZ,
                               Map<String, Integer> itemCounts) {
    public SchematicSummary {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(itemCounts, "itemCounts");
        if (widthX < 1 || heightY < 1 || lengthZ < 1) {
            throw new IllegalArgumentException("Schematic dimensions must be positive");
        }
        itemCounts = Collections.unmodifiableMap(new LinkedHashMap<>(itemCounts));
    }

    public int totalBlocks() {
        return itemCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public long volume() {
        return (long) widthX * heightY * lengthZ;
    }

    public String dimensions() {
        return widthX + "x" + heightY + "x" + lengthZ;
    }
}
