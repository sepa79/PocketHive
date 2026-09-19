package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.topology.work.WorkPlaneResources;
import java.util.Objects;

/**
 * Responsibility: project selected Work observations for resolved channel addresses into manager statistics.
 * Must not: resolve names, decode broker properties or access clients.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class SwarmQueueStatsPortAdapter implements QueueStatsPort {
    private final WorkPlaneResources resources;
    public SwarmQueueStatsPortAdapter(WorkPlaneResources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }
    @Override public QueueStats getQueueStats(String queueName) {
        return resources.observeInput(queueName)
            .map(queue -> new QueueStats(queue.messages(), queue.consumers(), queue.oldestAgeSeconds()))
            .orElseGet(QueueStats::empty);
    }
}
