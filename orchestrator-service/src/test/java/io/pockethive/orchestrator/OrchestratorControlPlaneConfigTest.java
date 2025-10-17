package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestratorControlPlaneConfigTest {

    private OrchestratorControlPlaneConfig config;
    private ControlPlaneProperties properties;
    private ControlPlaneTopologyDescriptor descriptor;

    @BeforeEach
    void setUp() {
        config = new OrchestratorControlPlaneConfig();
        properties = new ControlPlaneProperties();
        descriptor = ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator");
    }

    @Test
    void instanceIdReturnsConfiguredValue() {
        properties.setInstanceId("orch-123");

        String resolved = config.instanceId(properties);

        assertThat(resolved).isEqualTo("orch-123");
    }

    @Test
    void instanceIdFailsWhenConfigurationIsMissing() {
        assertThatThrownBy(() -> config.instanceId(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.control-plane.instance-id");
    }

    @Test
    void managerIdentityFailsWhenSwarmIdIsMissing() {
        properties.setInstanceId("orch-123");

        assertThatThrownBy(
                () ->
                    config.managerControlPlaneIdentity(
                        properties, descriptor, config.instanceId(properties)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.control-plane.swarm-id");
    }

    @Test
    void managerIdentityUsesConfiguredValues() {
        properties.setInstanceId("orch-123");
        properties.setSwarmId("swarm-7");

        ControlPlaneIdentity identity =
            config.managerControlPlaneIdentity(
                properties, descriptor, config.instanceId(properties));

        assertThat(identity.swarmId()).isEqualTo("swarm-7");
        assertThat(identity.instanceId()).isEqualTo("orch-123");
        assertThat(identity.role()).isEqualTo(descriptor.role());
    }
}
