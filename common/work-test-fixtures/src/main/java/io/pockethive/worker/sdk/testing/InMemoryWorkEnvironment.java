package io.pockethive.worker.sdk.testing;

import io.pockethive.work.config.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Responsibility: project the test memory connection and owner-resolved addresses to bootstrap settings.
 * Must not: borrow Rabbit configuration or choose physical addresses.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkEnvironment implements WorkAdapterEnvironment {
    public static final String INSTANCE_PROPERTY = "pockethive.work.memory.instance";
    public static final String INSTANCE_ENV = "POCKETHIVE_WORK_MEMORY_INSTANCE";
    private final String instance;
    public InMemoryWorkEnvironment(String instance) {
        if (instance == null || instance.isBlank()) throw new IllegalArgumentException("Explicit memory instance required");
        this.instance = instance;
    }
    @Override public Map<String, String> connectionEnvironment() { return Map.of(INSTANCE_ENV, instance); }
    @Override public void validateConnection(Function<String, String> properties) {
        if (!instance.equals(properties.apply(INSTANCE_PROPERTY))) throw new IllegalArgumentException("Wrong memory instance");
    }
    @Override public List<WorkConfigurationProblem> overrideProblems(Function<String, String> properties) {
        if (properties.apply(INSTANCE_PROPERTY) == null) return List.of();
        return List.of(new WorkConfigurationProblem(INSTANCE_PROPERTY, "Memory instance is selected by composition"));
    }
    @Override public WorkBootstrapProjection bootstrap(Map<String, Object> configuration, Map<String, String> destinationEnvironment) {
        var resolved = new LinkedHashMap<>(configuration);
        var environment = new LinkedHashMap<String, String>();
        resolve(configuration, resolved, environment, destinationEnvironment, WorkConfigurationFields.INPUTS, InMemoryWorkAddress.INPUT_ENV);
        resolve(configuration, resolved, environment, destinationEnvironment, WorkConfigurationFields.OUTPUTS, InMemoryWorkAddress.OUTPUT_ENV);
        return new WorkBootstrapProjection(resolved, environment);
    }
    private void resolve(Map<String, Object> source, Map<String, Object> resolved, Map<String, String> exported,
                         Map<String, String> destinations, String direction, String envKey) {
        if (!(source.get(direction) instanceof Map<?, ?> io)) throw new IllegalArgumentException("Explicit IO is required");
        if (!InMemoryWorkType.MEMORY.name().equals(io.get(WorkConfigurationFields.TYPE))) return;
        String address = InMemoryWorkAddress.require(destinations.get(envKey));
        if (!io.keySet().equals(Set.of(WorkConfigurationFields.TYPE, InMemoryWorkType.MEMORY.settingsKey()))
            || !(io.get(InMemoryWorkType.MEMORY.settingsKey()) instanceof Map<?, ?> settings)) {
            throw new IllegalArgumentException("Explicit memory settings required");
        }
        if (!settings.isEmpty() && !address.equals(InMemoryWorkAddress.parse(settings))) {
            throw new IllegalArgumentException("Memory address must match resolved topology");
        }
        resolved.put(direction, Map.of(WorkConfigurationFields.TYPE, InMemoryWorkType.MEMORY.name(),
            InMemoryWorkType.MEMORY.settingsKey(), Map.of(InMemoryWorkAddress.FIELD, address)));
        exported.put(envKey, address);
    }
}
