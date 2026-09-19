package dev.famulus.core;

import java.util.Objects;
import java.util.regex.Pattern;

public sealed interface PlannedTask {
    Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    String id();

    AgentAction action();

    String describe();

    record Gather(String id, String itemId, int count) implements PlannedTask {
        public Gather {
            requireId(id);
            Objects.requireNonNull(itemId, "itemId");
            if (!RESOURCE_ID.matcher(itemId).matches()) {
                throw new IllegalArgumentException("Item must be a namespaced identifier: " + itemId);
            }
            if (count < 1) {
                throw new IllegalArgumentException("Gather count must be positive");
            }
        }

        @Override
        public AgentAction action() {
            return AgentAction.GATHER;
        }

        @Override
        public String describe() {
            return "gather " + count + " " + itemId;
        }
    }

    record Build(String id, String blueprint, int originX, int originY, int originZ) implements PlannedTask {
        public Build {
            requireId(id);
            Objects.requireNonNull(blueprint, "blueprint");
            if (blueprint.isBlank()) {
                throw new IllegalArgumentException("Blueprint name must not be blank");
            }
        }

        @Override
        public AgentAction action() {
            return AgentAction.BUILD;
        }

        @Override
        public String describe() {
            return "build " + blueprint + " at " + originX + "," + originY + "," + originZ;
        }
    }

    record Deposit(String id, String itemId, int count) implements PlannedTask {
        public Deposit {
            requireId(id);
            Objects.requireNonNull(itemId, "itemId");
            if (!RESOURCE_ID.matcher(itemId).matches()) {
                throw new IllegalArgumentException("Item must be a namespaced identifier: " + itemId);
            }
            if (count < 1) {
                throw new IllegalArgumentException("Deposit count must be positive");
            }
        }

        @Override
        public AgentAction action() {
            return AgentAction.DEPOSIT_ITEM;
        }

        @Override
        public String describe() {
            return "deposit " + count + " " + itemId;
        }
    }

    private static void requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
    }
}
