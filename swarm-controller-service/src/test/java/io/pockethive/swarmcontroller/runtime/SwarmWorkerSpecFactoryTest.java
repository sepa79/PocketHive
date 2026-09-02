package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemMount;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.MetricsSettings;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.WorkerSettings;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

class SwarmWorkerSpecFactoryTest {

  @Test
  void plansCanonicalWorkerEnvironmentConfigAndVolumeOrder() {
    ClickHouseSinkProperties clickHouse = new ClickHouseSinkProperties();
    clickHouse.setEndpoint("http://clickhouse:8123");
    clickHouse.setTable("events");
    SwarmWorkerSpecFactory factory = factory(clickHouse);
    SutEndpoint endpoint = new SutEndpoint("HTTP", "http://wiremock:8080", null);
    SutEnvironment sutEnvironment = new SutEnvironment(
        "wiremock-local",
        "WireMock local",
        "sandbox",
        Map.of("default", endpoint));
    Bee bee = new Bee(
        "generator",
        "generator:test",
        Work.ofDefaults("generator-in", "generator-out"),
        Map.of(
            "CONTROL_NETWORK", "worker-network",
            "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", "http://worker-clickhouse:8123"),
        Map.of(
            "inputs", Map.of("type", "csv_dataset", "csv", Map.of("filePath", "/data/input.csv")),
            "docker", Map.of("volumes", List.of(" /host/input:/data:ro ")),
            "sut", Map.of("targetEndpointId", "default")));

    PlannedSwarmWorker planned = factory.plan(bee, sutEnvironment);

    assertThat(planned.spec().role()).isEqualTo("generator");
    assertThat(planned.spec().image()).isEqualTo("generator:test");
    assertThat(planned.spec().environment())
        .containsEntry("POCKETHIVE_JOURNAL_RUN_ID", "run-1")
        .containsEntry("POCKETHIVE_TEMPLATE_ID", "template-1")
        .containsEntry("POCKETHIVE_INPUT_RABBIT_QUEUE", "ph.test.generator-in")
        .containsEntry("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY", "ph.test.generator-out")
        .containsEntry("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE", "ph.test.hive")
        .containsEntry("POCKETHIVE_INPUTS_TYPE", "CSV_DATASET")
        .containsEntry("POCKETHIVE_INPUTS_CSV_FILEPATH", "/data/input.csv")
        .containsEntry("POCKETHIVE_RUNTIME_STACK_NAME", "ph-test-swarm")
        .containsEntry("CONTROL_NETWORK", "worker-network")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", "http://worker-clickhouse:8123")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_TABLE", "events");
    assertThat(planned.spec().volumes()).containsExactly(
        "/opt/pockethive/scenarios-runtime:/app/scenarios-runtime",
        "/host/input:/data:ro");
    assertThat(planned.bootstrapConfig())
        .containsEntry("baseUrl", "http://wiremock:8080");
    assertThat(objectMap(planned.bootstrapConfig().get("sut")))
        .containsEntry("targetEndpoint", endpoint)
        .containsEntry("environment", sutEnvironment);
  }

  @Test
  void resolveVolumesRetainsOnlyNonBlankStringSpecs() {
    Map<String, Object> config = Map.of(
        "docker", Map.of(
            "volumes", List.of(
                "/host/a:/container/a:ro",
                "  named-vol:/container/cache  ",
                "",
                42)));

    assertThat(SwarmWorkerSpecFactory.resolveVolumes(config))
        .containsExactly(
            "/host/a:/container/a:ro",
            "named-vol:/container/cache");
  }

  @Test
  void enrichesSutConfigUsingCanonicalEndpointMapKey() {
    SutEndpoint endpoint = new SutEndpoint("HTTP", "http://wiremock:8080", null);
    SutEnvironment environment = new SutEnvironment(
        "wiremock-local",
        "WireMock local",
        "sandbox",
        Map.of("default", endpoint));
    Map<String, Object> config = Map.of(
        "baseUrl", "http://legacy.invalid",
        "sut", Map.of("targetEndpointId", "default"));

    Map<String, Object> enriched = SwarmWorkerSpecFactory.enrichConfigWithSut(config, environment);

    assertThat(enriched.get("baseUrl")).isEqualTo("http://wiremock:8080");
    assertThat(objectMap(enriched.get("sut")))
        .containsEntry("targetEndpointId", "default")
        .containsEntry("targetEndpoint", endpoint)
        .containsEntry("environment", environment)
        .containsEntry("environmentId", "wiremock-local")
        .containsEntry("environmentType", "sandbox");
  }

  @Test
  void omitsStaleEnvironmentTypeWhenCanonicalSutHasNoType() {
    SutEndpoint endpoint = new SutEndpoint("TCP", "tcp://tcp-mock-server:9090", null);
    SutEnvironment environment = new SutEnvironment(
        "tcp-mock-local",
        "TCP Mock local",
        null,
        Map.of("tcp-server", endpoint));
    Map<String, Object> config = Map.of(
        "sut", Map.of(
            "targetEndpointId", "tcp-server",
            "environmentType", "stale"));

    Map<String, Object> enriched = SwarmWorkerSpecFactory.enrichConfigWithSut(config, environment);

    assertThat(objectMap(enriched.get("sut")))
        .containsEntry("targetEndpointId", "tcp-server")
        .doesNotContainKey("environmentType");
  }

  private static SwarmWorkerSpecFactory factory(ClickHouseSinkProperties clickHouse) {
    SwarmControllerProperties properties = properties();
    WorkerSettings workerSettings = new WorkerSettings(
        properties.getSwarmId(),
        "run-1",
        properties.getControlExchange(),
        properties.getControlQueuePrefixBase(),
        properties.hiveExchange(),
        new MetricsSettings(
            PocketHiveMetricsAdapter.DISABLED,
            Duration.ofSeconds(10),
            ClickHouseMetricsSinkProperties.disabled()));
    RabbitProperties rabbit = new RabbitProperties();
    rabbit.setHost("rabbitmq");
    rabbit.setPort(5672);
    rabbit.setUsername("guest");
    rabbit.setPassword("guest");
    rabbit.setVirtualHost("/");
    return new SwarmWorkerSpecFactory(
        properties,
        workerSettings,
        rabbit,
        () -> "control-network",
        clickHouse,
        RuntimeFilesystemMount.of("/opt/pockethive/scenarios-runtime"),
        () -> "template-1");
  }

  private static SwarmControllerProperties properties() {
    return new SwarmControllerProperties(
        "test-swarm",
        "ph.control",
        "ph.control",
        new SwarmControllerProperties.Manager("swarm-controller"),
        new SwarmControllerProperties.SwarmController(
            new SwarmControllerProperties.Traffic("ph.test.hive", "ph.test"),
            new SwarmControllerProperties.Metrics(
                PocketHiveMetricsAdapter.DISABLED,
                Duration.ofSeconds(10),
                ClickHouseMetricsSinkProperties.disabled()),
            new SwarmControllerProperties.Docker(
                null,
                "/var/run/docker.sock",
                ComputeAdapterType.DOCKER_SINGLE),
            new SwarmControllerProperties.Features(false)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return (Map<String, Object>) value;
  }
}
