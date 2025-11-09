package io.pockethive.worker.sdk.testing;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.QueueDescriptor;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Testing helpers that expose canonical control-plane descriptors and identities.
 * Complements the configuration steps in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class ControlPlaneTestFixtures {

    private ControlPlaneTestFixtures() {
    }

    public static WorkerControlPlaneProperties workerProperties(String swarmId, String role, String instanceId) {
        String resolvedSwarm = requireText("swarmId", swarmId);
        String resolvedRole = requireText("role", role);
        String resolvedInstance = requireText("instanceId", instanceId);

        WorkerControlPlaneProperties.Worker worker = new WorkerControlPlaneProperties.Worker(
            true,
            true,
            resolvedRole,
            null,
            true,
            null);
        WorkerControlPlaneProperties.SwarmController.Rabbit.Logging logging =
            new WorkerControlPlaneProperties.SwarmController.Rabbit.Logging(false);
        WorkerControlPlaneProperties.SwarmController.Rabbit rabbit =
            new WorkerControlPlaneProperties.SwarmController.Rabbit("ph.logs", logging);
        WorkerControlPlaneProperties.SwarmController swarmController =
            new WorkerControlPlaneProperties.SwarmController(rabbit);

        return new WorkerControlPlaneProperties(
            true,
            true,
            "ph.control",
            resolvedSwarm,
            resolvedInstance,
            "ph.control",
            worker,
            swarmController);
    }

    public static Map<String, String> workerQueues(String swarmId) {
        String resolvedSwarm = requireText("swarmId", swarmId);
        Map<String, String> queues = new LinkedHashMap<>();
        String prefix = "ph." + resolvedSwarm + ".";
        DEFAULT_QUEUE_SUFFIXES.forEach((role, suffix) -> queues.put(role, prefix + suffix));
        return Map.copyOf(queues);
    }

    public static String workerQueue(String swarmId, String queueRole) {
        String resolvedSwarm = requireText("swarmId", swarmId);
        String normalizedRole = requireText("queueRole", queueRole).trim().toLowerCase(Locale.ROOT);
        String suffix = DEFAULT_QUEUE_SUFFIXES.getOrDefault(normalizedRole, normalizedRole);
        return "ph." + resolvedSwarm + "." + suffix;
    }

    public static String hiveExchange(String swarmId) {
        return "ph." + requireText("swarmId", swarmId) + ".hive";
    }

    public static ControlPlaneProperties managerProperties(String swarmId, String role, String instanceId) {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        String resolvedSwarm = requireText("swarmId", swarmId);
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control");
        properties.setSwarmId(resolvedSwarm);
        properties.setInstanceId(requireText("instanceId", instanceId));
        ControlPlaneProperties.ManagerProperties manager = properties.getManager();
        manager.setRole(requireText("role", role));
        return properties;
    }

    public static ControlPlaneIdentity workerIdentity(String swarmId, String role, String instanceId) {
        return new ControlPlaneIdentity(requireText("swarmId", swarmId), requireText("role", role),
            requireText("instanceId", instanceId));
    }

    public static ControlPlaneIdentity managerIdentity(String swarmId, String role, String instanceId) {
        return new ControlPlaneIdentity(requireText("swarmId", swarmId), requireText("role", role),
            requireText("instanceId", instanceId));
    }

    public static ControlPlaneTopologyDescriptor workerTopology(String role) {
        String resolvedRole = requireText("role", role);
        ControlPlaneTopologySettings settings = workerTopologySettings("swarm-fixture", resolvedRole, "fixture-1");
        return ControlPlaneTopologyDescriptorFactory.forWorkerRole(resolvedRole, settings);
    }

    public static ControlPlaneTopologyDescriptor managerTopology(String role) {
        String resolvedRole = requireText("role", role);
        ControlPlaneTopologySettings settings = new ControlPlaneTopologySettings(
            "swarm-fixture", "ph.control", Map.of());
        return ControlPlaneTopologyDescriptorFactory.forManagerRole(resolvedRole, settings);
    }

    public static ControlPlaneEmitter workerEmitter(ControlPlanePublisher publisher, ControlPlaneIdentity identity) {
        ControlPlaneIdentity validated = Objects.requireNonNull(identity, "identity must not be null");
        ControlPlaneTopologySettings settings = workerTopologySettings(
            validated.swarmId(), validated.role(), validated.instanceId());
        ControlPlaneTopologyDescriptor descriptor = ControlPlaneTopologyDescriptorFactory
            .forWorkerRole(validated.role(), settings);
        return ControlPlaneEmitter.using(descriptor, RoleContext.fromIdentity(validated), requirePublisher(publisher));
    }

    public static ControlPlaneEmitter managerEmitter(ControlPlanePublisher publisher, ControlPlaneIdentity identity) {
        ControlPlaneIdentity validated = Objects.requireNonNull(identity, "identity must not be null");
        ControlPlaneTopologySettings settings = new ControlPlaneTopologySettings(
            validated.swarmId(), "ph.control", Map.of());
        ControlPlaneTopologyDescriptor descriptor = ControlPlaneTopologyDescriptorFactory
            .forManagerRole(validated.role(), settings);
        return ControlPlaneEmitter.using(descriptor, RoleContext.fromIdentity(validated), requirePublisher(publisher));
    }

    private static ControlPlanePublisher requirePublisher(ControlPlanePublisher publisher) {
        return Objects.requireNonNull(publisher, "publisher must not be null");
    }

    private static String requireText(String field, String value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }

    private static ControlPlaneTopologySettings workerTopologySettings(String swarmId, String role, String instanceId) {
        String resolvedSwarm = requireText("swarmId", swarmId);
        String resolvedRole = requireText("role", role);
        String resolvedInstance = requireText("instanceId", instanceId);
        WorkerControlPlaneProperties properties = workerProperties(resolvedSwarm, resolvedRole, resolvedInstance);
        Map<String, QueueDescriptor> trafficQueues = new LinkedHashMap<>();
        workerQueues(resolvedSwarm).forEach((queueRole, queueName) ->
            trafficQueues.put(queueRole, new QueueDescriptor(queueName, Set.of())));
        String controlQueue = properties.getControlPlane().getControlQueueName();
        String suffix = "." + resolvedSwarm + "." + resolvedRole + "." + resolvedInstance;
        String prefix = controlQueue.endsWith(suffix)
            ? controlQueue.substring(0, controlQueue.length() - suffix.length())
            : controlQueue;
        return new ControlPlaneTopologySettings(properties.getSwarmId(), prefix, trafficQueues);
    }

    private static final Map<String, String> DEFAULT_QUEUE_SUFFIXES = Map.of(
        "generator", "gen",
        "moderator", "mod",
        "processor", "processor",
        "postprocessor", "post",
        "trigger", "trigger",
        "final", "final"
    );
}
