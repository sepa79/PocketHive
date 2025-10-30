package io.pockethive.moderator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.pockethive.moderator.shaper.config.PatternConfigValidator;
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ModeratorRabbitConfigurationTest.TestConfig.class)
class ModeratorRabbitConfigurationTest {

  @Autowired
  @Qualifier("moderatorManualAckListenerContainerFactory")
  private SimpleRabbitListenerContainerFactory manualFactory;

  @Autowired
  @Qualifier("rabbitListenerContainerFactory")
  private SimpleRabbitListenerContainerFactory defaultFactory;

  @Autowired
  private ModeratorDefaults defaults;

  @Test
  void containerFactoryConfiguredForManualAckAndPrefetch() {
    SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
    endpoint.setId("test-endpoint");
    endpoint.setQueueNames("q");
    endpoint.setMessageListener((MessageListener) message -> { });
    SimpleMessageListenerContainer container = manualFactory.createListenerContainer(endpoint);
    assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
    assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(defaults.getPrefetch());
    assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(1);
    assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(1);
    container.destroy();
  }

  @Test
  void defaultFactoryUsesAutoAck() {
    SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
    endpoint.setId("default-endpoint");
    endpoint.setQueueNames("q");
    endpoint.setMessageListener((MessageListener) message -> { });
    SimpleMessageListenerContainer container = defaultFactory.createListenerContainer(endpoint);
    assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
    container.destroy();
  }

  @Configuration
  @Import({ModeratorRabbitConfiguration.class})
  static class TestConfig {

    @Bean
    SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer() {
      return new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
    }

    @Bean
    ConnectionFactory connectionFactory() {
      return mock(ConnectionFactory.class);
    }

    @Bean
    ModeratorDefaults moderatorDefaults(PatternConfigValidator validator) {
      ModeratorDefaults defaults = new ModeratorDefaults();
      defaults.setValidator(validator);
      defaults.setPrefetch(7);
      return defaults;
    }

    @Bean
    PatternConfigValidator patternConfigValidator() {
      return new PatternConfigValidator();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        SimpleRabbitListenerContainerFactoryConfigurer configurer,
        ConnectionFactory connectionFactory) {
      SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
      configurer.configure(factory, connectionFactory);
      factory.setAutoStartup(false);
      return factory;
    }
  }
}
