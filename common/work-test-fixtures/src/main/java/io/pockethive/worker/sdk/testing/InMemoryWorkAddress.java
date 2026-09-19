package io.pockethive.worker.sdk.testing;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: own test adapter address construction and validation.
 * Must not: reconstruct Rabbit names or access resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkAddress {
    public static final String FIELD = "address";
    public static final String INPUT_ENV = "POCKETHIVE_INPUTS_MEMORY_ADDRESS";
    public static final String OUTPUT_ENV = "POCKETHIVE_OUTPUTS_MEMORY_ADDRESS";
    private InMemoryWorkAddress() { }
    public static String require(String value) {
        URI uri = URI.create(Objects.requireNonNull(value, "memory address"));
        if (!"memory".equals(uri.getScheme()) || uri.getAuthority() == null
            || uri.getPath().length() < 2 || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("Explicit memory://scope/channel address required");
        }
        return value;
    }
    public static String parse(Map<?, ?> settings) {
        if (!settings.keySet().equals(java.util.Set.of(FIELD)) || !(settings.get(FIELD) instanceof String text)) {
            throw new IllegalArgumentException("Only an explicit address setting is supported");
        }
        return require(text);
    }
    public static String resolve(String swarm, String channel) {
        if (swarm == null || channel == null || !swarm.matches("[a-zA-Z0-9_-]+")
            || !channel.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Invalid memory resource scope/channel");
        return require("memory://" + swarm + "/" + channel);
    }
}
