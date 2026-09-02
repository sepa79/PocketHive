package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.JournalControlPlanePublisher;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import java.time.Duration;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: Compose the Swarm Controller control-plane collaborators from their canonical owners.
 * Must not: Consume messages, execute commands, own runtime state, or provide fallback configuration.
 * Contract: Expose one journaled publisher and one instance of every listener workflow owner.
 */
@Configuration(proxyBeanMethods = false)
class SwarmControllerControlPlaneConfiguration {

  @Bean
  JournalControlPlanePublisher swarmControllerControlPlanePublisher(
      RabbitTemplate rabbit,
      SwarmControllerProperties properties,
      SwarmJournal journal,
      ControlPlaneCodec codec) {
    return new JournalControlPlanePublisher(
        ControlPlaneJson.mapper(),
        journal,
        new AmqpControlPlanePublisher(rabbit, properties.getControlExchange(), codec));
  }

  @Bean
  ManagerControlPlane swarmControllerManagerControlPlane(
      JournalControlPlanePublisher publisher,
      ControlPlaneCodec codec,
      SwarmControllerProperties properties,
      @Qualifier("instanceId") String instanceId) {
    return ManagerControlPlane.builder(publisher, codec)
        .identity(new ControlPlaneIdentity(properties.getSwarmId(), properties.getRole(), instanceId))
        .duplicateCache(Duration.ofMinutes(1), 256)
        .build();
  }

  @Bean
  ControlPlaneEmitter swarmControllerResultEmitter(
      JournalControlPlanePublisher publisher,
      SwarmControllerProperties properties,
      SwarmControllerRuntimeMetadata runtimeMetadata,
      @Qualifier("instanceId") String instanceId) {
    return ControlPlaneEmitter.swarmController(
        new ControlPlaneIdentity(properties.getSwarmId(), properties.getRole(), instanceId),
        publisher,
        new ControlPlaneTopologySettings(
            properties.getSwarmId(),
            properties.getControlQueuePrefixBase(),
            Map.of()),
        runtimeMetadata.values());
  }

  @Bean
  SwarmControllerNetworkContext swarmControllerNetworkContext(
      SwarmLifecycle lifecycle,
      SwarmControllerProperties properties) {
    return SwarmControllerNetworkContext.fromEnvironment(lifecycle, properties.getRole());
  }

  @Bean
  SwarmControllerStatusPublisher swarmControllerStatusPublisher(
      SwarmLifecycle lifecycle,
      SwarmWorkerStatusHandler workerStatuses,
      SwarmHealthJournal healthJournal,
      SwarmControllerProperties properties,
      JournalControlPlanePublisher publisher,
      SwarmControllerRuntimeMetadata runtimeMetadata,
      SwarmControllerStartupInitializer startupInitializer,
      SwarmControllerNetworkContext networkContext,
      @Qualifier("instanceId") String instanceId) {
    return new SwarmControllerStatusPublisher(
        lifecycle,
        workerStatuses,
        healthJournal,
        properties,
        instanceId,
        publisher,
        runtimeMetadata.values(),
        startupInitializer.artifactSha256(),
        startupInitializer::isInitialized,
        startupInitializer.startedAt(),
        networkContext);
  }

  @Bean
  SwarmStatusFullCoordinator swarmStatusFullCoordinator(
      SwarmLifecycle lifecycle,
      SwarmControllerStatusPublisher statusPublisher,
      SwarmControllerStartupInitializer startupInitializer) {
    return new SwarmStatusFullCoordinator(
        lifecycle, statusPublisher, startupInitializer::isInitialized);
  }

  @Bean
  SwarmCommandReadiness swarmCommandReadiness(
      SwarmLifecycle lifecycle,
      SwarmControllerStartupInitializer startupInitializer) {
    return new SwarmCommandReadiness(lifecycle, startupInitializer::isInitialized);
  }

  @Bean
  SwarmControllerResultPublisher swarmControllerResultPublisher(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      ControlPlaneEmitter emitter,
      SwarmControllerProperties properties,
      @Qualifier("instanceId") String instanceId) {
    return new SwarmControllerResultPublisher(
        lifecycle, mapper, emitter, properties.getRole(), instanceId);
  }

  @Bean
  SwarmLifecycleCommandHandler swarmLifecycleCommandHandler(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmCommandReadiness readiness,
      SwarmControllerResultPublisher results,
      SwarmStatusFullCoordinator statusFullCoordinator) {
    return new SwarmLifecycleCommandHandler(
        lifecycle, mapper, readiness, results, statusFullCoordinator);
  }

  @Bean
  SwarmConfigUpdateHandler swarmConfigUpdateHandler(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmControllerProperties properties,
      SwarmControllerNetworkContext networkContext,
      SwarmControllerStatusPublisher statusPublisher,
      SwarmCommandReadiness readiness,
      SwarmControllerResultPublisher results,
      @Qualifier("instanceId") String instanceId) {
    return new SwarmConfigUpdateHandler(
        lifecycle,
        mapper,
        properties.getRole(),
        instanceId,
        networkContext,
        statusPublisher,
        readiness,
        results);
  }
}
