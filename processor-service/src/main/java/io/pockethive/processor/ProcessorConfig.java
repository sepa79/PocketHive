package io.pockethive.processor;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import java.util.Objects;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class ProcessorConfig {
  private static final String CONTROL_EXCHANGE_PROPERTY =
      "${pockethive.control-plane.exchange:${PH_CONTROL_EXCHANGE:ph.control}}";

  @Value("${ph.processor.baseUrl:}")
  private String baseUrl;

  @Bean
  public String baseUrl(){
    return baseUrl;
  }

  @Bean
  @ConditionalOnMissingBean(ControlPlanePublisher.class)
  public ControlPlanePublisher processorControlPlanePublisher(
      RabbitTemplate rabbitTemplate,
      @Value(CONTROL_EXCHANGE_PROPERTY) String controlExchange) {
    Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    if (!StringUtils.hasText(controlExchange)) {
      throw new IllegalArgumentException("pockethive.control-plane.exchange must not be null or blank");
    }
    return new AmqpControlPlanePublisher(rabbitTemplate, controlExchange);
  }

  @Bean
  public ControlPlaneEmitter processorControlPlaneEmitter(
      @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
      ControlPlanePublisher publisher) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(publisher, "publisher");
    return ControlPlaneEmitter.processor(identity, publisher);
  }

  @Bean(name = "processorControlQueueName")
  public String processorControlQueueName(
      @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
      @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(identity, "identity");
    return descriptor.controlQueue(identity.instanceId())
        .map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Processor control queue descriptor is missing"));
  }
}
