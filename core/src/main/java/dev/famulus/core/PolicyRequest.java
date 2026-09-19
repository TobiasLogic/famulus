package dev.famulus.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PolicyRequest(String state, Map<AgentAction, String> options) {
    public PolicyRequest {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(options, "options");
        if (state.isBlank()) {
            throw new IllegalArgumentException("State must describe the situation");
        }
        if (options.size() < 2) {
            throw new IllegalArgumentException("A decision needs at least two options");
        }
        options.forEach((action, description) -> {
            Objects.requireNonNull(action, "option action");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Option " + action + " needs a description");
            }
        });
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }
}
