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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project compute identity and Rabbit-only resource intent to the diagnostic ownership manifest.
 * Must not: gate native Work startup, claim complete Work inventory, provision resources or infer cleanup success.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
@Component
public final class RuntimeOwnershipManifestFactory {
    private static final Logger log = LoggerFactory.getLogger(RuntimeOwnershipManifestFactory.class);
    private final ControlPlaneProperties control;
    private final WorkPlaneResources work;

    public RuntimeOwnershipManifestFactory(ControlPlaneProperties control, WorkPlaneResources work) {
        this.control = Objects.requireNonNull(control, "control");
        this.work = Objects.requireNonNull(work, "work");
    }

    public RuntimeRabbitManifest resources(String swarmId, String controllerInstance, ResolvedWorkTopology topology) {
        var queues = new ArrayList<String>();
        var exchanges = new ArrayList<String>();
        int nativeResources = 0;
        for (var resource : topology.resources()) {
            var target = work.removalTarget(resource);
            if (target.plane() != ResourcePlane.WORK) throw new IllegalArgumentException("Expected WORK manifest resource");
            switch (target.type()) {
                case RABBIT_QUEUE -> queues.add(target.id());
                case RABBIT_EXCHANGE -> exchanges.add(target.id());
                case WORK_RESOURCE -> nativeResources++;
                default -> throw new IllegalArgumentException("Unsupported resource in current public manifest: " + target.type());
            }
        }
        if (nativeResources > 0) {
            log.warn("Rabbit-only ownership manifest excludes {} native WORK resources for swarm={}; "
                + "native Work inventory and orphan cleanup are outside its scope", nativeResources, swarmId);
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
