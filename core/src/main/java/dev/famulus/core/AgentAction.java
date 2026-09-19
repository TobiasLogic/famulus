package dev.famulus.core;

public enum AgentAction {
    GATHER(true),
    MINE(false),
    CRAFT(false),
    TRAVEL(false),

    EXPLORE(true),
    BUILD(true),
    PLACE_BLOCK(false),
    INTERACT(false),
    DEPOSIT_ITEM(false),
    WITHDRAW_ITEM(false),
    WAIT(true),
    VERIFY(true),
    RECOVER(true),
    COMPLETE_TASK(true),
    REQUEST_REPLAN(true),
    ABORT_TASK(true);

    private final boolean executable;

    AgentAction(boolean executable) {
        this.executable = executable;
    }

    public boolean isExecutable() {
        return executable;
    }

    public static AgentAction parse(String name) {
        if (name == null) {
            return null;
        }
        for (AgentAction action : values()) {
            if (action.name().equalsIgnoreCase(name.trim())) {
                return action;
            }
        }
        return null;
    }
}
