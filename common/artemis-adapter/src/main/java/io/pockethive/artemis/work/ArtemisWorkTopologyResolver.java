package io.pockethive.artemis.work;

import io.pockethive.artemis.config.ArtemisEnvironmentKeys;
import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.topology.work.WorkChannelAddress;
import io.pockethive.topology.work.WorkResourceIdentity;
import io.pockethive.topology.work.WorkTopologyResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: project one Artemis name resolution into transport, resource, ENV and status values.
 * Must not: duplicate name formulas, create resources or retain mutable swarm state.
 * Contract: RESP-ARTEMIS-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-artemis-resource-names.
 */
public final class ArtemisWorkTopologyResolver implements WorkTopologyResolver {
    private final ArtemisResourceNames names;

    public ArtemisWorkTopologyResolver(ArtemisResourceNames names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    @Override
    public ResolvedWorkTopology resolve(String swarmId, Set<String> logicalChannels) {
        ArtemisSettingValues.requiredText(swarmId, "swarmId");
        Objects.requireNonNull(logicalChannels, "logicalChannels");
        var channels = new LinkedHashMap<String, WorkChannelAddress>();
        var resources = new ArrayList<WorkResourceIdentity>();
        var uniqueNames = new HashSet<String>();
        for (String logical : logicalChannels.stream().sorted().toList()) {
            String name = names.channel(swarmId, logical);
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("Logical channels resolve to the same Artemis resource");
            }
            var queue = ArtemisResourceKind.QUEUE.identity(name);
            resources.add(ArtemisResourceKind.ADDRESS.identity(name));
            resources.add(queue);
            channels.put(logical, new WorkChannelAddress(name, name,
                Map.of(ArtemisEnvironmentKeys.INPUT_QUEUE, name),
                Map.of(ArtemisEnvironmentKeys.OUTPUT_ADDRESS, name),
                Map.of("queue", name), Map.of("address", name), queue));
        }
        return new ResolvedWorkTopology(channels, resources,
            Map.of(ArtemisEnvironmentKeys.NAMESPACE, names.namespace()),
            Map.of("namespace", names.namespace()));
    }
}
