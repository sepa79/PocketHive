package io.pockethive.trigger;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TriggerControlPlaneConfig {

  @Bean(name = "triggerControlPlaneEmitter")
  @ConditionalOnMissingBean(name = "triggerControlPlaneEmitter")
  ControlPlaneEmitter triggerControlPlaneEmitter(
      @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
      ControlPlanePublisher publisher) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(publisher, "publisher");
    return ControlPlaneEmitter.trigger(identity, publisher);
  }

  @Bean(name = "triggerControlQueueName")
  @ConditionalOnMissingBean(name = "triggerControlQueueName")
  String triggerControlQueueName(
      @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
      @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(identity, "identity");
    return descriptor.controlQueue(identity.instanceId())
        .map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Trigger control queue descriptor is missing"));
  }
}
