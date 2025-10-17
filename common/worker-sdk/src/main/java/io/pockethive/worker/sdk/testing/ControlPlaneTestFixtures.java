package io.pockethive.worker.sdk.testing;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import java.util.Objects;

/**
 * Testing helpers that expose canonical control-plane descriptors and identities.
 * Complements the configuration steps in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class ControlPlaneTestFixtures {

    private ControlPlaneTestFixtures() {
    }

    public static ControlPlaneProperties workerProperties(String swarmId, String role, String instanceId) {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        String resolvedSwarm = requireText("swarmId", swarmId);
        properties.setSwarmId(resolvedSwarm);
        properties.setInstanceId(requireText("instanceId", instanceId));
        ControlPlaneProperties.WorkerProperties worker = properties.getWorker();
        worker.setRole(requireText("role", role));
        return properties;
    }

    public static ControlPlaneProperties managerProperties(String swarmId, String role, String instanceId) {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        String resolvedSwarm = requireText("swarmId", swarmId);
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
        return ControlPlaneTopologyDescriptorFactory.forWorkerRole(requireText("role", role));
    }

    public static ControlPlaneTopologyDescriptor managerTopology(String role) {
        return ControlPlaneTopologyDescriptorFactory.forManagerRole(requireText("role", role));
    }

    public static ControlPlaneEmitter workerEmitter(ControlPlanePublisher publisher, ControlPlaneIdentity identity) {
        ControlPlaneIdentity validated = Objects.requireNonNull(identity, "identity must not be null");
        ControlPlaneTopologyDescriptor descriptor = workerTopology(validated.role());
        return ControlPlaneEmitter.using(descriptor, RoleContext.fromIdentity(validated), requirePublisher(publisher));
    }

    public static ControlPlaneEmitter managerEmitter(ControlPlanePublisher publisher, ControlPlaneIdentity identity) {
        ControlPlaneIdentity validated = Objects.requireNonNull(identity, "identity must not be null");
        ControlPlaneTopologyDescriptor descriptor = managerTopology(validated.role());
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
}
