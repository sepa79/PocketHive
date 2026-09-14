package io.pockethive.worker.sdk.testing;

import io.pockethive.topology.work.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: resolve logical memory channels to one immutable topology projection.
 * Must not: provision resources or invent an exchange.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkTopologyResolver implements WorkTopologyResolver {
    @Override public ResolvedWorkTopology resolve(String swarmId, Set<String> logicalChannels) {
        var channels = new LinkedHashMap<String, WorkChannelAddress>();
        logicalChannels.stream().sorted().forEach(logical -> {
            String address = InMemoryWorkAddress.resolve(swarmId, logical);
            channels.put(logical, new WorkChannelAddress(address, address,
                Map.of(InMemoryWorkAddress.INPUT_ENV, address), Map.of(InMemoryWorkAddress.OUTPUT_ENV, address),
                Map.of(InMemoryWorkAddress.FIELD, address), Map.of(InMemoryWorkAddress.FIELD, address),
                InMemoryWorkResources.identity(address)));
        });
        return new ResolvedWorkTopology(channels, channels.values().stream().map(WorkChannelAddress::resource).toList(),
            Map.of(), Map.of());
    }
}
