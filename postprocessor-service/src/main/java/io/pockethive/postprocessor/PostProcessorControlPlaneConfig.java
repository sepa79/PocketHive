package io.pockethive.postprocessor;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({
    ControlPlaneCommonAutoConfiguration.class,
    WorkerControlPlaneAutoConfiguration.class
})
class PostProcessorControlPlaneConfig {

  @Bean(name = "postProcessorControlPlaneEmitter")
  @ConditionalOnMissingBean(name = "postProcessorControlPlaneEmitter")
  ControlPlaneEmitter postProcessorControlPlaneEmitter(
      @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
      ControlPlanePublisher publisher) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(publisher, "publisher");
    return ControlPlaneEmitter.postProcessor(identity, publisher);
  }

  @Bean(name = "postProcessorControlQueueName")
  @ConditionalOnMissingBean(name = "postProcessorControlQueueName")
  String postProcessorControlQueueName(
      @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
      @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(identity, "identity");
    return descriptor.controlQueue(identity.instanceId())
        .map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Post-processor control queue descriptor is missing"));
  }
}
