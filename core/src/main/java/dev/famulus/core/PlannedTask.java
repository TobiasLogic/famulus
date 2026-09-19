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
            requireItem(itemId);
            requireCount(count, "Gather");
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

    record Travel(String id, int x, int y, int z) implements PlannedTask {
        public Travel {
            requireId(id);
            if (y < -256 || y > 512) {
                throw new IllegalArgumentException("Destination height is outside any world: " + y);
            }
        }

        @Override
        public AgentAction action() {
            return AgentAction.TRAVEL;
        }

        @Override
        public String describe() {
            return "travel to " + x + "," + y + "," + z;
        }
    }

    record Deposit(String id, String itemId, int count) implements PlannedTask {
        public Deposit {
            requireId(id);
            requireItem(itemId);
            requireCount(count, "Deposit");
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

    record Withdraw(String id, String itemId, int count) implements PlannedTask {
        public Withdraw {
            requireId(id);
            requireItem(itemId);
            requireCount(count, "Withdraw");
        }

        @Override
        public AgentAction action() {
            return AgentAction.WITHDRAW_ITEM;
        }

        @Override
        public String describe() {
            return "withdraw " + count + " " + itemId;
        }
    }

    record Craft(String id, String itemId, int count) implements PlannedTask {
        public Craft {
            requireId(id);
            requireItem(itemId);
            requireCount(count, "Craft");
        }

        @Override
        public AgentAction action() {
            return AgentAction.CRAFT;
        }

        @Override
        public String describe() {
            return "craft " + count + " " + itemId;
        }
    }

    private static void requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
    }

    private static void requireItem(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (!RESOURCE_ID.matcher(itemId).matches()) {
            throw new IllegalArgumentException("Item must be a namespaced identifier: " + itemId);
        }
    }

    private static void requireCount(int count, String what) {
        if (count < 1) {
            throw new IllegalArgumentException(what + " count must be positive");
        }
    }
}
