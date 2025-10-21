package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.ApplicationArguments;

class SwarmControllerControlQueueVerifierTest {

  private final SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
  private final ApplicationArguments args = mock(ApplicationArguments.class);

  @Test
  void runThrowsWhenQueueMissing() {
    AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    String queueName = properties.controlQueueName("bee-one");
    when(amqpAdmin.getQueueProperties(queueName)).thenReturn(null);

    SwarmControllerControlQueueVerifier verifier =
        new SwarmControllerControlQueueVerifier(amqpAdmin, properties, "bee-one");

    assertThatThrownBy(() -> verifier.run(args))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(queueName);
  }

  @Test
  void runSucceedsWhenQueueExists() {
    AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    String queueName = properties.controlQueueName("bee-two");
    when(amqpAdmin.getQueueProperties(queueName)).thenReturn(new Properties());

    SwarmControllerControlQueueVerifier verifier =
        new SwarmControllerControlQueueVerifier(amqpAdmin, properties, "bee-two");

    assertThatCode(() -> verifier.run(args)).doesNotThrowAnyException();
  }
}
