package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.rabbit.api.RabbitResources;
import java.util.Objects;

/**
 * Responsibility: project Rabbit queue observations into manager queue statistics.
 * Must not: decode broker properties, own defaults or access Rabbit clients.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public final class SwarmQueueStatsPortAdapter implements QueueStatsPort {
    private final RabbitResources resources;
    public SwarmQueueStatsPortAdapter(@org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.WORK) RabbitResources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }
    @Override public QueueStats getQueueStats(String queueName) {
        return resources.queue(queueName)
            .map(queue -> new QueueStats(queue.messages(), queue.consumers(), queue.oldestAgeSeconds()))
            .orElseGet(QueueStats::empty);
    }
}
