package io.pockethive.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerControlPlaneConfigTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          ControlPlaneCommonAutoConfiguration.class,
          WorkerControlPlaneAutoConfiguration.class))
      .withUserConfiguration(TestConfig.class, TriggerControlPlaneConfig.class)
      .withPropertyValues(
          "pockethive.control-plane.swarm-id=swarm-test",
          "pockethive.control-plane.worker.role=trigger",
          "pockethive.control-plane.worker.instance-id=trigger-1");

  @Test
  void autoConfigurationProvidesControlPlanePublisherAndEmitter() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ControlPlanePublisher.class);
      assertThat(context).hasBean("triggerControlPlaneEmitter");
      ControlPlaneEmitter emitter = context.getBean("triggerControlPlaneEmitter", ControlPlaneEmitter.class);
      assertThat(emitter).isNotNull();
      assertThat(context.getBean("triggerControlQueueName", String.class)).isNotBlank();
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    RabbitOperations amqpTemplate() {
      return Mockito.mock(RabbitOperations.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
