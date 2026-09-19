package io.pockethive.swarmcontroller.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.ScenarioLifecyclePort;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.SwarmMetrics;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SwarmScenarioCoordinatorTest {

  @Test
  void appliesAndTicksTimelineThroughExplicitPorts() {
    ConfigFanout configFanout = mock(ConfigFanout.class);
    TimelineScenarioObserver observer = mock(TimelineScenarioObserver.class);
    SwarmScenarioCoordinator scenarios = coordinator(
        configFanout,
        observer,
        new AtomicReference<>(WorkloadState.RUNNING),
        new AtomicReference<>(metrics(4, 4, 4, 4)));
    scenarios.applyPlan("""
        {
          "bees": [{
            "instanceId": "generator-1",
            "role": "generator",
            "steps": [{
              "stepId": "enable",
              "time": "PT0S",
              "type": "config-update",
              "config": {"enabled": true}
            }]
          }]
        }
        """);

    scenarios.tick();

    verify(configFanout).publishConfigUpdate(
        eq(ControlScope.forInstance("swarm-1", "generator", "generator-1")),
        any(),
        eq("scenario"));
    verify(observer).onPlanLoaded(1, 0);
    assertThat(scenarios.progress().firedStepIds).containsExactly("enable");
  }

  @Test
  void projectsCurrentOwnerStateWithoutKeepingItsOwnLifecycleState() {
    AtomicReference<WorkloadState> state = new AtomicReference<>(WorkloadState.STOPPED);
    AtomicReference<SwarmMetrics> metrics = new AtomicReference<>(metrics(3, 2, 1, 0));
    SwarmScenarioCoordinator scenarios = coordinator(
        mock(ConfigFanout.class),
        mock(TimelineScenarioObserver.class),
        state,
        metrics);

    ManagerRuntimeView first = scenarios.runtimeView();
    state.set(WorkloadState.RUNNING);
    metrics.set(metrics(5, 5, 5, 5));
    ManagerRuntimeView second = scenarios.runtimeView();

    assertThat(first.workloadState()).isEqualTo(WorkloadState.STOPPED);
    assertThat(first.metrics().desired()).isEqualTo(3);
    assertThat(first.metrics().healthy()).isEqualTo(2);
    assertThat(second.workloadState()).isEqualTo(WorkloadState.RUNNING);
    assertThat(second.metrics().desired()).isEqualTo(5);
    assertThat(second.metrics().enabled()).isEqualTo(5);
  }

  private static SwarmScenarioCoordinator coordinator(
      ConfigFanout configFanout,
      TimelineScenarioObserver observer,
      AtomicReference<WorkloadState> state,
      AtomicReference<SwarmMetrics> metrics) {
    return new SwarmScenarioCoordinator(
        new ObjectMapper().findAndRegisterModules(),
        "swarm-1",
        configFanout,
        observer,
        mock(ScenarioLifecyclePort.class),
        state::get,
        metrics::get);
  }

  private static SwarmMetrics metrics(int desired, int healthy, int running, int enabled) {
    return new SwarmMetrics(desired, healthy, running, enabled, Instant.parse("2026-09-02T10:00:00Z"));
  }
}
