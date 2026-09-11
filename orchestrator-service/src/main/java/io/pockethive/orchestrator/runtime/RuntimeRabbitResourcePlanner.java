package io.pockethive.orchestrator.runtime;

import io.pockethive.rabbit.api.RabbitResourceNames;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.WorkerControlPlaneTopologyDescriptor;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologySnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.SourceSummary;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import static io.pockethive.orchestrator.runtime.RuntimeReconciliationService.isRunningState;
import static io.pockethive.orchestrator.runtime.RuntimeReconciliationService.hasText;
/**
 * Responsibility: derive scoped Rabbit cleanup targets and projections from the ownership manifest.
 * Must not: infer planes from names, collapse equal names across planes or grant cleanup approval.
 * Contract: docs/architecture/work-plane-boundaries.md#connection-split-prerequisite-resource-identity.
 */
final class RuntimeRabbitResourcePlanner {
    private final RabbitTopologyPort rabbitTopology;
    private final ControlPlaneProperties controlPlaneProperties;

    RuntimeRabbitResourcePlanner(RabbitTopologyPort rabbitTopology, ControlPlaneProperties controlPlaneProperties) {
        this.rabbitTopology = Objects.requireNonNull(rabbitTopology, "rabbitTopology");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
    }

    RabbitTopologySnapshot snapshot(CleanupScope scope, RuntimeOwnershipManifest manifest, List<ComputeRuntimeResource> computeResources) {
        RuntimeOwnershipManifest.RabbitResources rabbit = manifest.rabbit();
        var queues = queues(rabbit, derivedWorkerControlQueues(scope, computeResources));
        LinkedHashSet<String> exchanges = new LinkedHashSet<>(rabbit.exchanges());

        return new RabbitTopologySnapshot(
            scope.computeAdapter(),
            scope.swarmId(),
            scope.runId().orElse(null),
            SourceSummary.present(),
            SourceSummary.present(),
            true,
            queues.stream().sorted(Comparator.comparing(ScopedRabbitName::plane).thenComparing(ScopedRabbitName::name)).map(q -> queueSnapshot(q.plane(), q.name())).toList(),
            exchanges.stream().sorted().map(this::exchangeSnapshot).toList(),
            List.of());
    }

    void appendCandidates(CleanupScope scope,
                          Optional<Swarm> activeSwarm,
                          Optional<RuntimeOwnershipManifest> manifest,
                          List<ComputeRuntimeResource> computeResources,
                          List<Candidate> candidates,
                          List<Blocked> blocked) {
        if (!scope.includeRabbit()) {
            return;
        }
        if (manifest.isEmpty()) {
            blocked.add(new Blocked(
                "rabbit:manifest:" + scope.swarmId(),
                RuntimeCleanupAction.DELETE_RABBIT_QUEUE,
                scope.swarmId(),
                "manifest",
                "missing ownership manifest",
                Map.of(), ResourcePlane.NONE));
            return;
        }
        RuntimeOwnershipManifest.RabbitResources rabbit = manifest.get().rabbit();
        var queues = queues(rabbit, derivedWorkerControlQueues(scope, computeResources));
        for (var target : queues) {
            ResourcePlane plane = target.plane();
            String queue = target.name();
            Optional<RabbitQueueResource> live = rabbitTopology.queue(plane, queue);
            if (live.isEmpty()) {
                continue;
            }
            if (activeSwarm.isPresent()) {
                blocked.add(new Blocked(
                    rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_QUEUE, plane, queue),
                    RuntimeCleanupAction.DELETE_RABBIT_QUEUE,
                    queue,
                    "queue",
                    "registered swarm RabbitMQ resources must be removed through lifecycle cleanup",
                    Map.of(), plane));
                continue;
            }
            RabbitQueueResource q = live.get();
            boolean highRisk = q.depth() > 0 || q.consumers() > 0;
            candidates.add(new Candidate(
                rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_QUEUE, plane, queue),
                RuntimeCleanupAction.DELETE_RABBIT_QUEUE,
                queue,
                "queue",
                null,
                null,
                null,
                "present",
                null,
                q.depth(),
                q.consumers(),
                q.consumers() > 0,
                highRisk,
                highRisk ? "RabbitMQ queue has messages or consumers" : "orphaned RabbitMQ queue from ownership manifest",
                Map.of(), plane));
        }
        for (String exchange : rabbit.exchanges()) {
            Optional<RabbitExchangeResource> live = rabbitTopology.exchange(ResourcePlane.WORK, exchange);
            if (live.isEmpty()) {
                continue;
            }
            if (activeSwarm.isPresent()) {
                blocked.add(new Blocked(
                    rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE, ResourcePlane.WORK, exchange),
                    RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE,
                    exchange,
                    "exchange",
                    "active swarm shared RabbitMQ resource is protected",
                    Map.of(), ResourcePlane.WORK));
                continue;
            }
            candidates.add(new Candidate(
                rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE, ResourcePlane.WORK, exchange),
                RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE,
                exchange,
                "exchange",
                null,
                null,
                null,
                "manifested",
                null,
                null,
                null,
                false,
                false,
                "orphaned RabbitMQ exchange from ownership manifest",
                Map.of(), ResourcePlane.WORK));
        }
    }

    private List<String> derivedWorkerControlQueues(CleanupScope scope, List<ComputeRuntimeResource> resources) {
        ControlPlaneTopologySettings settings = new ControlPlaneTopologySettings(
            scope.swarmId(),
            controlPlaneProperties.getControlQueuePrefix(),
            Map.of());
        LinkedHashSet<String> queues = new LinkedHashSet<>();
        for (ComputeRuntimeResource resource : resources) {
            Map<String, String> labels = resource.labels();
            if (!PocketHiveDockerLabels.MANAGED_VALUE.equals(labels.get(PocketHiveDockerLabels.MANAGED))) {
                continue;
            }
            if (!scope.swarmId().equals(labels.get(PocketHiveDockerLabels.SWARM_ID))) {
                continue;
            }
            if (scope.runId().isPresent() && !scope.runId().get().equals(labels.get(PocketHiveDockerLabels.RUN_ID))) {
                continue;
            }
            if (!PocketHiveDockerLabels.RESOURCE_KIND_WORKER.equals(labels.get(PocketHiveDockerLabels.RESOURCE_KIND))) {
                continue;
            }
            if (isRunningState(resource.state()) && !scope.includeRunning()) {
                continue;
            }
            String role = labels.get(PocketHiveDockerLabels.ROLE);
            String instance = labels.get(PocketHiveDockerLabels.INSTANCE);
            if (!hasText(role) || !hasText(instance)) {
                continue;
            }
            new WorkerControlPlaneTopologyDescriptor(role, settings, new RabbitResourceNames())
                .controlQueue(instance)
                .map(ControlQueueDescriptor::name)
                .ifPresent(queues::add);
        }
        return List.copyOf(queues);
    }

    private RabbitQueueSnapshot queueSnapshot(ResourcePlane plane, String name) {
        Optional<RabbitQueueResource> queue = rabbitTopology.queue(plane, name);
        if (queue.isEmpty()) {
            return new RabbitQueueSnapshot(
                name,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                "not found", plane);
        }
        RabbitQueueResource resource = queue.get();
        return new RabbitQueueSnapshot(
            name,
            true,
            resource.depth(),
            resource.consumers(),
            null,
            null,
            null,
            false,
            null, plane);
    }

    private RabbitExchangeSnapshot exchangeSnapshot(String name) {
        Optional<RabbitExchangeResource> exchange = rabbitTopology.exchange(ResourcePlane.WORK, name);
        if (exchange.isEmpty()) {
            return new RabbitExchangeSnapshot(
                name,
                false,
                null,
                null,
                null,
                "not found", ResourcePlane.WORK);
        }
        return new RabbitExchangeSnapshot(
            name,
            true,
            null,
            null,
            null,
            null, ResourcePlane.WORK);
    }

    private static String rabbitCandidateId(RuntimeCleanupAction action, ResourcePlane plane, String name) {
        String type = action == RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE ? "exchange" : "queue";
        return "rabbit:" + plane + ":" + type + ":" + name;
    }

    String connectionIdentity(ResourcePlane plane) {
        return Objects.requireNonNull(rabbitTopology.connectionIdentity(plane), "Rabbit connection identity");
    }

    void deleteQueue(Candidate candidate) { rabbitTopology.deleteQueue(candidate.plane(), candidate.resourceId()); }
    void deleteExchange(Candidate candidate) { rabbitTopology.deleteExchange(candidate.plane(), candidate.resourceId()); }
    private static Set<ScopedRabbitName> queues(RuntimeOwnershipManifest.RabbitResources manifest, List<String> workerControlQueues) {
        var result = new LinkedHashSet<ScopedRabbitName>();
        manifest.controlQueues().forEach(name -> result.add(new ScopedRabbitName(ResourcePlane.CONTROL, name)));
        workerControlQueues.forEach(name -> result.add(new ScopedRabbitName(ResourcePlane.CONTROL, name)));
        manifest.workQueues().forEach(name -> result.add(new ScopedRabbitName(ResourcePlane.WORK, name)));
        return result;
    }
}
