package io.pockethive.processor;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorConfig {
  @Value("${ph.processor.baseUrl:}")
  private String baseUrl;

  @Bean
  public String baseUrl(){
    return baseUrl;
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
