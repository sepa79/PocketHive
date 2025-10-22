package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX_BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        .isEqualTo(CONTROL_QUEUE_PREFIX + ".swarm-controller.test-instance");
  }

  @Test
  void controlQueuePrefixIncludesSwarmId() {
    assertThat(properties.getControlQueuePrefix())
        .isEqualTo(CONTROL_QUEUE_PREFIX);
    assertThat(properties.getControlQueuePrefixBase()).isEqualTo(CONTROL_QUEUE_PREFIX_BASE);
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
