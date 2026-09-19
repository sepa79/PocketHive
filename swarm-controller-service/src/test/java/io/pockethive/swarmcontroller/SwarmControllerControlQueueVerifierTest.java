package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitResources;
import org.springframework.boot.ApplicationArguments;

class SwarmControllerControlQueueVerifierTest {

  private final SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
  private final ApplicationArguments args = mock(ApplicationArguments.class);

  @Test
  void runThrowsWhenQueueMissing() {
    RabbitResources amqpAdmin = mock(RabbitResources.class);
    String queueName = properties.controlQueueName("bee-one");
    when(amqpAdmin.queue(queueName)).thenReturn(java.util.Optional.empty());

    SwarmControllerControlQueueVerifier verifier =
        new SwarmControllerControlQueueVerifier(amqpAdmin, properties, "bee-one");

    assertThatThrownBy(() -> verifier.run(args))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(queueName);
  }

  @Test
  void runSucceedsWhenQueueExists() {
    RabbitResources amqpAdmin = mock(RabbitResources.class);
    String queueName = properties.controlQueueName("bee-two");
    when(amqpAdmin.queue(queueName)).thenReturn(java.util.Optional.of(new io.pockethive.rabbit.api.RabbitQueueObservation(0, 0, java.util.OptionalLong.empty())));

    SwarmControllerControlQueueVerifier verifier =
        new SwarmControllerControlQueueVerifier(amqpAdmin, properties, "bee-two");

    assertThatCode(() -> verifier.run(args)).doesNotThrowAnyException();
  }
}
