package io.pockethive.topology.work;

import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * Responsibility: retain one immutable topology result shared by configuration, effects and projections.
 * Must not: choose an adapter, reconstruct consumer-specific names or decide swarm lifecycle outcomes.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record ResolvedWorkTopology(Map<String, WorkChannelAddress> channels,
                                   List<WorkResourceIdentity> resources,
                                   Map<String, String> controllerEnvironment,
                                   Map<String, Object> status) {
    public ResolvedWorkTopology {
        channels = Map.copyOf(channels);
        resources = List.copyOf(resources);
        controllerEnvironment = Map.copyOf(controllerEnvironment);
        status = Map.copyOf(status);
    }
    /** Read-only projection retaining base resources and the supplied channel identities. */
    public ResolvedWorkTopology retainChannels(java.util.Set<WorkResourceIdentity> retained) {
        var channelResources = channels.values().stream().map(WorkChannelAddress::resource)
            .collect(java.util.stream.Collectors.toSet());
        var selected = new java.util.LinkedHashMap<String, WorkChannelAddress>();
        channels.forEach((logical, address) -> {
            if (retained.contains(address.resource())) selected.put(logical, address);
        });
        return new ResolvedWorkTopology(selected, resources.stream()
            .filter(resource -> !channelResources.contains(resource) || retained.contains(resource)).toList(),
            controllerEnvironment, status);
    }

    public WorkChannelAddress channel(String logicalName) {
        return Objects.requireNonNull(channels.get(logicalName), "Unresolved Work channel: " + logicalName);
    }
}
