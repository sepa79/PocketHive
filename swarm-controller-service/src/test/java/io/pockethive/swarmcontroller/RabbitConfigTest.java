package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.Topology;
import io.pockethive.controlplane.spring.BeeIdentityProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.junit.jupiter.api.Test;

class RabbitConfigTest {

  private final SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
  private final RabbitConfig config = new RabbitConfig(properties);

  @Test
  void controlQueueNameUsesSwarmRoleAndInstanceSegments() {
    String queueName = config.controlQueueName("test-instance");
    assertThat(queueName)
        .isEqualTo("ph.control." + Topology.SWARM_ID + ".swarm-controller.test-instance");
  }

  @Test
  void controlQueuePrefixIncludesSwarmId() {
    assertThat(properties.getControlQueuePrefix())
        .isEqualTo("ph.control." + Topology.SWARM_ID);
    assertThat(properties.getControlQueuePrefixBase()).isEqualTo("ph.control");
  }

  @Test
  void instanceIdReturnsConfiguredBeeName() {
    BeeIdentityProperties beeIdentityProperties = new BeeIdentityProperties();
    beeIdentityProperties.setInstanceId("test-swarm-controller-bee");
    assertThat(config.instanceId(beeIdentityProperties)).isEqualTo("test-swarm-controller-bee");
  }

  @Test
  void instanceIdFailsWhenBeeNameMissing() {
    BeeIdentityProperties beeIdentityProperties = new BeeIdentityProperties();
    assertThatThrownBy(() -> config.instanceId(beeIdentityProperties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BeeIdentityProperties.INSTANCE_ID_PROPERTY);
  }
}
