package dev.famulus.core;

public record PolicyGateConfig(double confidenceFloor, double replanUrgencyCeiling,
                               int maxConsecutiveEscalations) {
    public PolicyGateConfig {
        requireUnitInterval(confidenceFloor, "confidenceFloor");
        requireUnitInterval(replanUrgencyCeiling, "replanUrgencyCeiling");
        if (maxConsecutiveEscalations < 1) {
            throw new IllegalArgumentException("Must allow at least one escalation");
        }
    }

    public static PolicyGateConfig defaults() {
        return new PolicyGateConfig(0.55, 0.70, 3);
    }

    private static void requireUnitInterval(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, was " + value);
        }
    }
}
