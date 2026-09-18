package io.pockethive.swarmcontroller.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.manager.scenario.ScenarioEngine;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Docker;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Manager;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Metrics;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.SwarmController;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties.Traffic;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Narrow unit tests around scenario ticking to ensure that plans only
 * progress once the controller has been started.
 */
class SwarmRuntimeCoreScenarioEngineTest {

  private SwarmRuntimeCore newCoreWithScenarioSpy() throws Exception {
    AmqpAdmin amqp = mock(AmqpAdmin.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    io.pockethive.docker.DockerContainerClient docker = mock(io.pockethive.docker.DockerContainerClient.class);
    RabbitProperties rabbitProps = new RabbitProperties();
    SwarmControllerProperties props = new SwarmControllerProperties(
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
            new Docker(null, "/var/run/docker.sock", io.pockethive.manager.runtime.ComputeAdapterType.DOCKER_SINGLE),
            new SwarmControllerProperties.Features(false)));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ControlPlanePublisher controlPublisher = mock(ControlPlanePublisher.class);
    io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager topology =
        new io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager(amqp, props);
    io.pockethive.manager.ports.ComputeAdapter computeAdapter =
        mock(io.pockethive.manager.ports.ComputeAdapter.class);
    io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics queueMetrics =
        new io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics("test-swarm", meterRegistry);
    io.pockethive.manager.runtime.ConfigFanout configFanout =
        new io.pockethive.manager.runtime.ConfigFanout(
            mapper,
            new io.pockethive.swarmcontroller.runtime.SwarmControlPlanePortAdapter(controlPublisher),
            props.getSwarmId(),
            "inst");

    SwarmRuntimeCore core = new SwarmRuntimeCore(
        amqp,
        mapper,
        docker,
        rabbitProps,
        props,
        controlPublisher,
        topology,
        computeAdapter,
        queueMetrics,
        configFanout,
        SwarmJournal.noop(),
        "inst",
        new ClickHouseSinkProperties(),
        io.pockethive.controlplane.filesystem.RuntimeFilesystemMount.of(
            "/opt/pockethive/scenarios-runtime"));

    // Replace the internal ScenarioEngine with a spy so we can observe ticks.
    ScenarioEngine engineSpy = mock(ScenarioEngine.class);
    Field f = SwarmRuntimeCore.class.getDeclaredField("scenarioEngine");
    f.setAccessible(true);
    f.set(core, engineSpy);

    return core;
  }

  @Test
  void scenarioEngineDoesNotTickWhenControllerDisabled() throws Exception {
    SwarmRuntimeCore core = newCoreWithScenarioSpy();

    core.updateHeartbeat("generator", "gen-1", System.currentTimeMillis());

    ScenarioEngine engine =
        (ScenarioEngine) getPrivate(core, "scenarioEngine");
    verify(engine, never()).tick();
  }

  @Test
  void scenarioEngineTicksWhenControllerEnabled() throws Exception {
    SwarmRuntimeCore core = newCoreWithScenarioSpy();
    ScenarioEngine engine =
        (ScenarioEngine) getPrivate(core, "scenarioEngine");

    core.setControllerEnabled(true);
    core.updateHeartbeat("generator", "gen-1", System.currentTimeMillis());

    verify(engine).tick();
  }

  @Test
  void scenarioViewProjectsTheControllerStateMachine() throws Exception {
    SwarmRuntimeCore core = newCoreWithScenarioSpy();

    core.fail("worker observation failed");

    ManagerRuntimeView view = core.scenarioRuntimeView();
    org.assertj.core.api.Assertions.assertThat(view.workloadState()).isEqualTo(WorkloadState.UNKNOWN);
    org.assertj.core.api.Assertions.assertThat(view.metrics().desired()).isEqualTo(core.getMetrics().desired());
    org.assertj.core.api.Assertions.assertThat(view.metrics().healthy()).isEqualTo(core.getMetrics().healthy());
    org.assertj.core.api.Assertions.assertThat(view.metrics().running()).isEqualTo(core.getMetrics().running());
    org.assertj.core.api.Assertions.assertThat(view.metrics().enabled()).isEqualTo(core.getMetrics().enabled());
  }

  private static Object getPrivate(Object target, String fieldName) throws Exception {
    Field f = target.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.get(target);
  }
}
