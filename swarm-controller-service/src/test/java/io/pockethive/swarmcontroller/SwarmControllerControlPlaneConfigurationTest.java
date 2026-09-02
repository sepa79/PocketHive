package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.ManagerControlPlaneAutoConfiguration;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.JournalControlPlanePublisher;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class SwarmControllerControlPlaneConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          ControlPlaneCommonAutoConfiguration.class,
          ManagerControlPlaneAutoConfiguration.class))
      .withUserConfiguration(
          TestDependencies.class,
          SwarmControllerControlPlaneConfiguration.class,
          SwarmSignalListener.class,
          SwarmControllerControlPlaneScheduler.class)
      .withPropertyValues(
          "pockethive.control-plane.enabled=true",
          "pockethive.control-plane.exchange=ph.control",
          "pockethive.control-plane.swarm-id=test-swarm",
          "pockethive.control-plane.instance-id=controller-1",
          "pockethive.control-plane.control-queue-prefix=ph.control",
          "pockethive.control-plane.manager.enabled=true",
          "pockethive.control-plane.manager.role=swarm-controller",
          "pockethive.control-plane.manager.declare-topology=false",
          "pockethive.control-plane.swarm-controller.traffic.queue-prefix=ph.test-swarm",
          "pockethive.control-plane.swarm-controller.traffic.hive-exchange=ph.test-swarm.hive",
          "pockethive.control-plane.swarm-controller.metrics.adapter=DISABLED",
          "pockethive.control-plane.swarm-controller.metrics.publish-interval=PT10S",
          "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock");

  @Test
  void composesOneCanonicalPublisherManagerListenerAndScheduler() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(ControlPlanePublisher.class);
      assertThat(context.getBean(ControlPlanePublisher.class))
          .isInstanceOf(JournalControlPlanePublisher.class);
      assertThat(context).hasSingleBean(ManagerControlPlane.class);
      assertThat(context).hasSingleBean(SwarmSignalListener.class);
      assertThat(context).hasSingleBean(SwarmControllerControlPlaneScheduler.class);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SwarmControllerProperties.class)
  static class TestDependencies {

    @Bean
    RabbitTemplate rabbitTemplate() {
      return mock(RabbitTemplate.class);
    }

    @Bean
    SwarmJournal swarmJournal() {
      return SwarmJournal.noop();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(name = "instanceId")
    String instanceId() {
      return "controller-1";
    }

    @Bean
    SwarmLifecycle swarmLifecycle() {
      SwarmLifecycle lifecycle = mock(SwarmLifecycle.class);
      when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
      when(lifecycle.getMetrics())
          .thenReturn(new SwarmMetrics(0, 0, 0, 0, Instant.parse("2024-01-01T00:00:00Z")));
      when(lifecycle.isReadyForWork()).thenReturn(true);
      when(lifecycle.scenarioProgress()).thenReturn(Map.of());
      when(lifecycle.expectedWorkers()).thenReturn(java.util.List.of());
      when(lifecycle.workBindingsSnapshot()).thenReturn(Map.of());
      when(lifecycle.bufferGuards()).thenReturn(java.util.List.of());
      when(lifecycle.snapshotQueueStats()).thenReturn(Map.of());
      return lifecycle;
    }

    @Bean
    SwarmWorkerStatusHandler swarmWorkerStatusHandler() {
      SwarmWorkerStatusHandler handler = mock(SwarmWorkerStatusHandler.class);
      when(handler.workersSnapshot()).thenReturn(java.util.List.of());
      when(handler.diagnosticsSnapshot()).thenReturn(Map.of());
      return handler;
    }

    @Bean
    SwarmWorkerAlertHandler swarmWorkerAlertHandler() {
      return mock(SwarmWorkerAlertHandler.class);
    }

    @Bean
    SwarmHealthJournal swarmHealthJournal() {
      return mock(SwarmHealthJournal.class);
    }

    @Bean
    SwarmRemoveCommandHandler swarmRemoveCommandHandler() {
      return mock(SwarmRemoveCommandHandler.class);
    }

    @Bean
    SwarmControllerStartupInitializer swarmControllerStartupInitializer() {
      SwarmControllerStartupInitializer initializer =
          mock(SwarmControllerStartupInitializer.class);
      when(initializer.isInitialized()).thenReturn(true);
      when(initializer.artifactSha256()).thenReturn("a".repeat(64));
      when(initializer.startedAt()).thenReturn(Instant.parse("2024-01-01T00:00:00Z"));
      return initializer;
    }

    @Bean
    SwarmControllerRuntimeMetadata swarmControllerRuntimeMetadata() {
      SwarmControllerRuntimeMetadata metadata = mock(SwarmControllerRuntimeMetadata.class);
      when(metadata.values()).thenReturn(
          Map.of("templateId", "template-1", "runId", "run-1"));
      return metadata;
    }
  }
}
