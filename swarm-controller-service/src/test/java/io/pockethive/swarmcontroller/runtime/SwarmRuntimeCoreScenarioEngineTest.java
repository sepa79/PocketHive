package io.pockethive.swarmcontroller.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarmcontroller.WorkerStatusRequestCallback;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Docker;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Manager;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Metrics;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.SwarmController;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Traffic;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the lifecycle owner alone decides whether a heartbeat may tick scenario execution.
 */
class SwarmRuntimeCoreScenarioEngineTest {

  private static final String IMMEDIATE_PLAN = """
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
      """;

  @Test
  void heartbeatTicksScenarioOnlyWhileControllerIsEnabled() {
    ConfigFanout configFanout = mock(ConfigFanout.class);
    SwarmRuntimeCore core = newCore(configFanout);
    core.applyScenarioPlan(IMMEDIATE_PLAN);

    core.updateHeartbeat("generator", "generator-1", System.currentTimeMillis());

    verify(configFanout, never()).publishConfigUpdate(
        any(ControlScope.class), any(), eq("scenario"));

    core.setControllerEnabled(true);
    core.updateHeartbeat("generator", "generator-1", System.currentTimeMillis());

    verify(configFanout).publishConfigUpdate(
        eq(ControlScope.forInstance("test-swarm", "generator", "generator-1")),
        any(),
        eq("scenario"));
  }

  private static SwarmRuntimeCore newCore(ConfigFanout configFanout) {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    SwarmControllerProperties properties = new SwarmControllerProperties(
        "test-swarm",
        "ph.control",
        "ph.control",
        new Manager("swarm-controller"),
        new SwarmController(
            new Traffic("ph.test.hive", "ph.test"),
            new Metrics(
                PocketHiveMetricsAdapter.DISABLED,
                Duration.ofSeconds(10),
                ClickHouseMetricsSinkProperties.disabled()),
            new Docker(
                null,
                "/var/run/docker.sock",
                io.pockethive.manager.runtime.ComputeAdapterType.DOCKER_SINGLE),
            new SwarmControllerProperties.Features(false)));
    return new SwarmRuntimeCore(
        mapper,
        properties,
        configFanout,
        SwarmJournal.noop(),
        "controller-1",
        mock(SwarmWorkerSpecFactory.class),
        mock(SwarmRuntimeInfrastructure.class),
        mock(SwarmQueueStatsCollector.class),
        mock(WorkerStatusRequestCallback.class));
  }
}
