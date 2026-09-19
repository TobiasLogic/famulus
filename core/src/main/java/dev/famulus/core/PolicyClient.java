package dev.famulus.core;

public interface PolicyClient {
    PolicyDecision decide(PolicyRequest request) throws PolicyException;
}
