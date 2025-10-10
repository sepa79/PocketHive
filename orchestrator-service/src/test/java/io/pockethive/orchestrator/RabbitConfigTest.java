package io.pockethive.orchestrator;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.QueueDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitConfigTest {

    private final OrchestratorControlPlaneConfig config = new OrchestratorControlPlaneConfig();
    private final ControlPlaneTopologyDescriptor descriptor =
        ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator");

    @Test
    void resolvesControlQueueNameFromDescriptor() {
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", descriptor.role(), "orch-1");
        String queueName = config.managerControlQueueName(descriptor, identity);
        assertThat(queueName).isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator.orch-1");
    }

    @Test
    void resolvesControllerStatusQueueNameFromDescriptor() {
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", descriptor.role(), "orch-1");
        String queueName = config.controllerStatusQueueName(descriptor, identity);
        assertThat(queueName).isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator-status.orch-1");
    }

    @Test
    void fallsBackToStatusQueueWhenDescriptorProvidesOnlyTrafficQueues() {
        ControlPlaneTopologyDescriptor trafficOnlyDescriptor = new TrafficOnlyDescriptor();
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", trafficOnlyDescriptor.role(), "orch-1");

        String queueName = config.controllerStatusQueueName(trafficOnlyDescriptor, identity);

        assertThat(queueName).isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator-status.orch-1");
    }

    private static final class TrafficOnlyDescriptor implements ControlPlaneTopologyDescriptor {

        @Override
        public String role() {
            return "orchestrator";
        }

        @Override
        public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
            return Optional.empty();
        }

        @Override
        public Collection<QueueDescriptor> additionalQueues(String instanceId) {
            return List.of(QueueDescriptor.traffic("ignored", Set.of()));
        }

        @Override
        public ControlPlaneRouteCatalog routes() {
            return ControlPlaneRouteCatalog.empty();
        }
    }
}
