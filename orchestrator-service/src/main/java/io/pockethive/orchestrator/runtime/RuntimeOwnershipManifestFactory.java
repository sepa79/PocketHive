package io.pockethive.orchestrator.runtime;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.topology.work.WorkPlaneResources;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project resolved resource intent and compute identity to the current public ownership manifest.
 * Must not: reconstruct Work addresses, provision resources, persist artifacts or infer successful cleanup.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
@Component
public final class RuntimeOwnershipManifestFactory {
    private final ControlPlaneProperties control;
    private final WorkPlaneResources work;

    public RuntimeOwnershipManifestFactory(ControlPlaneProperties control, WorkPlaneResources work) {
        this.control = Objects.requireNonNull(control, "control");
        this.work = Objects.requireNonNull(work, "work");
    }

    public RuntimeRabbitManifest resources(String swarmId, String controllerInstance, ResolvedWorkTopology topology) {
        var queues = new ArrayList<String>();
        var exchanges = new ArrayList<String>();
        for (var resource : topology.resources()) {
            var target = work.removalTarget(resource);
            if (target.plane() != ResourcePlane.WORK) throw new IllegalArgumentException("Expected WORK manifest resource");
            switch (target.type()) {
                case RABBIT_QUEUE -> queues.add(target.id());
                case RABBIT_EXCHANGE -> exchanges.add(target.id());
                default -> throw new IllegalArgumentException("Unsupported resource in current public manifest: " + target.type());
            }
        }
        var controlQueues = new SwarmControllerControlPlaneTopologyDescriptor(swarmId,
            control.getControlQueuePrefix(), new RabbitResourceNames()).controlQueue(controllerInstance)
            .stream().map(ControlQueueDescriptor::name).toList();
        return new RuntimeRabbitManifest(controlQueues, queues, exchanges);
    }

    public RuntimeOwnershipManifest create(String swarmId, String runId, String templateId,
                                           ComputeAdapterType compute, RuntimeManifestObject controller,
                                           RuntimeRabbitManifest resources) {
        return new RuntimeOwnershipManifest(swarmId, runId, templateId, compute.name(), Instant.now(),
            List.of(controller), resources);
    }
}
