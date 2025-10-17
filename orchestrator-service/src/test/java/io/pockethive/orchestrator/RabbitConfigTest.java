package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.QueueDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitConfigTest {

    private final OrchestratorControlPlaneConfig config = new OrchestratorControlPlaneConfig();
    private final ControlPlaneTopologyDescriptor descriptor =
        ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator");

    @Test
    void resolvesControlQueueNameFromDescriptor() {
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", descriptor.role(), "orch-1");
        String queueName = config.managerControlQueueName(descriptor, identity);
        String expected = descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow();
        assertThat(queueName).isEqualTo(expected);
    }

    @Test
    void resolvesControllerStatusQueueNameFromDescriptor() {
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", descriptor.role(), "orch-1");
        String queueName = config.controllerStatusQueueName(descriptor, identity);
        String expected = descriptor.additionalQueues(identity.instanceId()).stream()
            .findFirst()
            .map(QueueDescriptor::name)
            .orElseThrow();
        assertThat(queueName).isEqualTo(expected);
    }
}
