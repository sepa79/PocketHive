package io.pockethive.topology.work;

import io.pockethive.swarm.model.Bee;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
/**
 * Responsibility: derive the logical channel inventory from a swarm's declared worker ports.
 * Must not: resolve physical addresses, select an adapter or create resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class WorkTopologyChannels {
    private WorkTopologyChannels() { }

    public static Set<String> from(List<Bee> bees) {
        var channels = new LinkedHashSet<String>();
        for (Bee bee : bees) {
            if (bee.work() != null) {
                bee.work().in().values().stream().filter(WorkTopologyChannels::hasText).forEach(channels::add);
                bee.work().out().values().stream().filter(WorkTopologyChannels::hasText).forEach(channels::add);
            }
        }
        return Collections.unmodifiableSet(channels);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
