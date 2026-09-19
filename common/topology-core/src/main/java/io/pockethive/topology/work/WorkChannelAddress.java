package io.pockethive.topology.work;

import java.util.Map;
import java.util.Objects;
/**
 * Responsibility: retain one resolved logical channel and its read-only consumer projections.
 * Must not: choose an adapter, reconstruct consumer-specific names or decide swarm lifecycle outcomes.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record WorkChannelAddress(String inputAddress, String outputAddress,
                                 Map<String, String> inputEnvironment, Map<String, String> outputEnvironment,
                                 Map<String, Object> inputStatus, Map<String, Object> outputStatus,
                                 WorkResourceIdentity resource) {
    public WorkChannelAddress {
        Objects.requireNonNull(inputAddress, "inputAddress");
        Objects.requireNonNull(outputAddress, "outputAddress");
        Objects.requireNonNull(resource, "resource");
        inputEnvironment = Map.copyOf(inputEnvironment);
        outputEnvironment = Map.copyOf(outputEnvironment);
        inputStatus = Map.copyOf(inputStatus);
        outputStatus = Map.copyOf(outputStatus);
    }
}
