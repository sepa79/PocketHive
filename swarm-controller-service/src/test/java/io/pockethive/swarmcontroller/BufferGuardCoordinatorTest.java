package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.guard.BufferGuardCoordinator;
import io.pockethive.work.config.WorkerInputType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BufferGuardCoordinatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final QueueStatsPort queueStats = mock(QueueStatsPort.class);
  private final ControlPlanePublisher publisher = mock(ControlPlanePublisher.class);
  private final BufferGuardCoordinator coordinator = new BufferGuardCoordinator(
      SwarmControllerTestProperties.defaults(true), queueStats, new SimpleMeterRegistry(), publisher, mapper, "test");

  @ParameterizedTest
  @EnumSource(value = WorkerInputType.class, names = {"SCHEDULER", "REDIS_DATASET"})
  void acceptsCanonicalRatesBeforeApplyingGuardBounds(WorkerInputType type) throws Exception {
    for (var rate : Map.<Object, Double>of("3.0", 3.0, 0, 1.0, 500, 100.0).entrySet()) {
      configure(type, Map.of("ratePerSec", rate.getKey()));
      assertThat(coordinator.isActive()).isTrue();
      assertThat(coordinator.lastProblem()).isNull();
      assertThat(coordinator.currentSettings()).singleElement().satisfies(settings ->
          assertThat(settings.initialRatePerSec()).isEqualTo(rate.getValue()));
    }
  }

  @ParameterizedTest
  @EnumSource(value = WorkerInputType.class, names = {"SCHEDULER", "REDIS_DATASET"})
  void rejectsInvalidSelectedRatesAndClearsPreviousConfiguration(WorkerInputType type) throws Exception {
    for (Object invalid : new Object[]{null, -1, "fast", "NaN", "Infinity", true, "{{ 3 }}"}) {
      configure(type, Map.of("ratePerSec", 3));
      var settings = new LinkedHashMap<String, Object>();
      settings.put("ratePerSec", invalid);
      configure(type, settings);
      assertRejected();
    }
    configure(type, Map.of());
    assertRejected();
    coordinator.onSwarmEnabled(true);
    verifyNoInteractions(queueStats, publisher);
    coordinator.onRemove();
  }

  @Test
  void preservesBoundsOnlyConfigurationForAnInputWithoutRateControl() throws Exception {
    configure(WorkerInputType.RABBITMQ, Map.of());
    assertThat(coordinator.isActive()).isTrue();
    assertThat(coordinator.currentSettings()).singleElement().satisfies(settings ->
        assertThat(settings.initialRatePerSec()).isEqualTo(1.0));
    verifyNoInteractions(publisher);
  }

  private void assertRejected() {
    assertThat(coordinator.isActive()).isFalse();
    assertThat(coordinator.currentSettings()).isEmpty();
    assertThat(coordinator.lastProblem()).contains("WorkConfigurationException");
  }

  private void configure(WorkerInputType type, Map<String, Object> settings) throws Exception {
    var guard = new BufferGuardPolicy(true, "gen-out", 200, 150, 260, "1s", 3,
        new BufferGuardPolicy.Adjustment(20, 10, 1, 100),
        new BufferGuardPolicy.Prefill(false, "2m", 20),
        new BufferGuardPolicy.Backpressure(null, 500, 250, 15));
    var plan = new SwarmPlan("default", List.of(new Bee("generator", "generator:latest",
        Work.ofDefaults(null, "gen-out"), null,
        Map.of("inputs", Map.of("type", type.name(), type.settingsKey(), settings)))), new TrafficPolicy(guard));
    coordinator.configureFromTemplate(mapper.writeValueAsString(plan));
  }
}
