package dev.famulus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MaterialList(List<MaterialRequirement> requirements) {
    private static final Comparator<MaterialRequirement> BY_SHORTFALL =
            Comparator.comparingInt(MaterialRequirement::shortfall).reversed()
                    .thenComparing(MaterialRequirement::itemId);

    public MaterialList {
        Objects.requireNonNull(requirements, "requirements");
        List<MaterialRequirement> sorted = new ArrayList<>(requirements);
        sorted.sort(BY_SHORTFALL);
        requirements = Collections.unmodifiableList(sorted);
    }

    public static MaterialList of(Map<String, Integer> needed, Map<String, Integer> have) {
        Objects.requireNonNull(needed, "needed");
        Objects.requireNonNull(have, "have");
        List<MaterialRequirement> requirements = new ArrayList<>(needed.size());
        needed.forEach((itemId, count) -> {
            if (count != null && count > 0) {
                requirements.add(new MaterialRequirement(
                        itemId, count, Math.max(0, have.getOrDefault(itemId, 0))));
            }
        });
        return new MaterialList(requirements);
    }

    public List<MaterialRequirement> shortfalls() {
        return requirements.stream().filter(requirement -> !requirement.satisfied()).toList();
    }

    public boolean isSatisfied() {
        return shortfalls().isEmpty();
    }

    public int distinctItems() {
        return requirements.size();
    }

    public int totalNeeded() {
        return requirements.stream().mapToInt(MaterialRequirement::needed).sum();
    }

    public int totalShortfall() {
        return requirements.stream().mapToInt(MaterialRequirement::shortfall).sum();
    }

    public List<MaterialRequirement> gatherable(java.util.function.Predicate<String> supported) {
        return shortfalls().stream().filter(r -> supported.test(r.itemId())).toList();
    }

    public List<MaterialRequirement> unobtainable(java.util.function.Predicate<String> supported) {
        return shortfalls().stream().filter(r -> !supported.test(r.itemId())).toList();
    }
}
