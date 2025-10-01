package io.pockethive.orchestrator;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
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
        assertThat(queueName).isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator.orch-1");
    }

    @Test
    void resolvesControllerStatusQueueNameFromDescriptor() {
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", descriptor.role(), "orch-1");
        String queueName = config.controllerStatusQueueName(descriptor, identity);
        assertThat(queueName).isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator-status.orch-1");
    }
}
