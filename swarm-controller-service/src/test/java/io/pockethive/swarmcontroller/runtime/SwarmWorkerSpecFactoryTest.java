package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.pockethive.rabbit.config.RabbitConnectionSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class SwarmWorkerSpecFactoryTest {

  private static Map<String, Object> csvSettings() {
    return Map.of("filePath", "/data/input.csv", "ratePerSec", 1, "rotate", false, "skipHeader", true,
        "delimiter", ",", "charset", "UTF-8", "startupDelaySeconds", 0, "tickIntervalMs", 1000);
  }

  @Test
  void csvOverridesAndCrossConnectionPlaceholdersReachStartupAndBootstrap() {
    var settings = new LinkedHashMap<>(csvSettings());
    settings.put("filePath", 123);
    settings.put("delimiter", "${CSV_SEPARATOR}");
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null),
        Map.of("POCKETHIVE_INPUTS_CSV_FILE_PATH", "/resolved.csv", "POCKETHIVE_INPUTS_CSV_SKIP_HEADER", "false",
            "CSV_SEPARATOR", "\\|", "POCKETHIVE_INPUTS_CSV_RATEPERSEC", "${pockethive.outputs.redis.port}"),
        Map.of("inputs", Map.of("type", "CSV_DATASET", "csv", settings),
            "outputs", Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379, "ssl", false))));
    var plan = factory(new ClickHouseSinkProperties()).plan(bee, null);
    var properties = io.pockethive.swarmcontroller.config.SpringConnectionEnvironment.resolved(plan.spec().environment());
    var startup = new io.pockethive.work.config.csv.CsvDatasetParser().parse(
        new io.pockethive.work.config.csv.CsvDatasetEnvironment().candidate(Map.of(), properties), "inputs.csv");
    var bootstrap = objectMap(objectMap(plan.bootstrapConfig().get("inputs")).get("csv"));
    assertThat(bootstrap).isEqualTo(io.pockethive.work.config.csv.CsvDatasetParser.configuration(startup));
    assertThat(startup.filePath()).isEqualTo("/resolved.csv");
    assertThat(startup.skipHeader()).isFalse();
    assertThat(startup.ratePerSec()).isEqualTo(6379);
    assertThat(startup.delimiter().split("a|b", -1)).containsExactly("a", "b");
    assertThat(settings.get("filePath")).isEqualTo(123);
  }

  @Test
  void csvRejectsInvalidDeclaredTypesAndFinalEnvironmentOverrides() {
    var factory = factory(new ClickHouseSinkProperties());
    var fields = new LinkedHashMap<>(csvSettings());
    fields.put("filePath", 123);
    assertThatThrownBy(() -> factory.plan(new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(),
        Map.of("inputs", Map.of("type", "CSV_DATASET", "csv", fields))), null))
        .hasMessageContaining("inputs.csv.filePath");
    for (String value : List.of("yes", "", "${MISSING_FLAG}")) {
      assertThatThrownBy(() -> factory.plan(new Bee("generator", "generator:test", Work.ofDefaults(null, null),
          Map.of("POCKETHIVE_INPUTS_CSV_ROTATE", value),
          Map.of("inputs", Map.of("type", "CSV_DATASET", "csv", csvSettings()))), null))
          .isInstanceOf(RuntimeException.class);
    }
  }

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
            "inputs", Map.of("type", "csv_dataset", "csv", csvSettings()),
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
  void preservesRedisCredentialsInBothWorkerEnvironmentDirections() {
    for (String password : List.of(" secret ", "")) {
      Map<String, Object> connection = Map.of(
          "host", " redis ", "port", 6380, "ssl", true, "username", " user ", "password", password);
      Map<String, Object> input = new java.util.LinkedHashMap<>(connection);
      input.putAll(Map.of("listName", "dataset", "pickStrategy", "ROUND_ROBIN", "ratePerSec", 1));
      Map<String, Object> output = new java.util.LinkedHashMap<>(connection);
      output.putAll(Map.of("sourceStep", "LAST", "pushDirection", "RPUSH", "defaultList", "out", "maxLen", -1));
      Map<String, Object> config = Map.of(
          "inputs", Map.of("type", "REDIS_DATASET", "redis", input),
          "outputs", Map.of("type", "REDIS", "redis", output));
      var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(), config);

      var planned = factory(new ClickHouseSinkProperties()).plan(bee, null);

      for (String prefix : List.of("POCKETHIVE_INPUTS_REDIS_", "POCKETHIVE_OUTPUTS_REDIS_")) {
        assertThat(planned.spec().environment())
            .containsEntry(prefix + "HOST", " redis ").containsEntry(prefix + "PORT", "6380")
            .containsEntry(prefix + "SSL", "true").containsEntry(prefix + "USERNAME", " user ")
            .containsEntry(prefix + "PASSWORD", password);
      }
      for (String root : List.of("inputs", "outputs")) {
        assertThat(objectMap(objectMap(planned.bootstrapConfig().get(root)).get("redis")))
            .containsEntry("host", "redis").containsEntry("username", "user")
            .containsEntry("port", 6380).containsEntry("password", password);
      }
      assertThat(connection.get("host")).isEqualTo(" redis ");
      assertThat(connection.get("password")).isEqualTo(password);
    }
  }

  @Test
  void rejectsIncompleteRedisConnectionBeforeReturningWorkerPlan() {
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(),
        Map.of("outputs", Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379))));

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null))
        .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
        .hasMessageContaining("outputs.redis.ssl");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPRING_RABBITMQ_PORT", "POCKETHIVE_OUTPUTS_REDIS_PORT", "pockethive.outputs.redis.port"})
  void rejectsInvalidConnectionOverrideBeforeReturningWorkerPlan(String variable) {
    var bee = redisOutputBee(Map.of(variable, "0"), 6379);

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("port");
  }

  @Test
  void validOverridesReachBothStartupAndBootstrapWithoutChangingSource() {
    var bee = redisOutputBee(Map.of(
        "SPRING_RABBITMQ_PORT", "5673",
        "POCKETHIVE_OUTPUTS_REDIS_PORT", "6381",
        "pockethive.outputs.redis.password", " new secret "), 6379);

    var planned = factory(new ClickHouseSinkProperties()).plan(bee, null);

    assertThat(planned.spec().environment())
        .containsEntry("SPRING_RABBITMQ_PORT", "5673")
        .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PORT", "6381")
        .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PASSWORD", " new secret ");
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("port", 6381).containsEntry("password", " new secret ")
        .containsEntry("defaultList", "out");
    assertThat(objectMap(objectMap(bee.config().get("outputs")).get("redis")))
        .containsEntry("port", 6379).containsEntry("password", "old secret");
  }

  @Test
  void validatesAfterOverridesHaveCorrectedTheDeclaredConnection() {
    var planned = factory(new ClickHouseSinkProperties())
        .plan(redisOutputBee(Map.of("POCKETHIVE_OUTPUTS_REDIS_PORT", "6381"), 0), null);

    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("port", 6381);
  }

  @Test
  void rejectsEmptyRabbitAliasAndResolvedPasswordBeforeReturningPlan() {
    for (var overrides : List.of(Map.of("SPRING_RABBITMQ_VIRTUALHOST", ""),
        Map.of("SPRING_RABBITMQ_PASSWORD", "${EMPTY}", "EMPTY", ""))) {
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(redisOutputBee(overrides, 6379), null))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("must not be null or blank");
    }
  }

  @Test
  void springAliasesAndPlaceholdersReachBothStartupAndBootstrap() {
    var planned = factory(new ClickHouseSinkProperties()).plan(redisOutputBee(Map.of(
        "SPRING_RABBITMQ_VIRTUALHOST", "/other", "POCKETHIVE_OUTPUTS_REDIS_PASSWORD", "${REDIS_SECRET}",
        "REDIS_SECRET", " secret "), 6379), null);
    var workerEnvironment = new MockEnvironment();
    workerEnvironment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        "systemEnvironment", new LinkedHashMap<>(planned.spec().environment())));
    var startup = Binder.get(workerEnvironment).bind("spring.rabbitmq", RabbitConnectionSettings.class).get();
    assertThat(startup.virtualHost()).isEqualTo("/other");
    assertThat(planned.spec().environment()).containsEntry("SPRING_RABBITMQ_VIRTUALHOST", startup.virtualHost())
        .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PASSWORD", "${REDIS_SECRET}");
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("password", " secret ");
  }

  @Test
  void rejectsRabbitPasswordThatResolvesToEmptyDeclaredRedisPassword() {
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null),
        Map.of("SPRING_RABBITMQ_PASSWORD", "${POCKETHIVE_OUTPUTS_REDIS_PASSWORD}"),
        Map.of("outputs", Map.of("type", "REDIS", "redis", Map.of(
            "host", "redis", "port", 6379, "ssl", false, "password", ""))));

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.password");
  }

  @Test
  void resolvesConnectionReferencesAgainstCompleteUnchangedEnvironment() {
    var bee = redisOutputBee(Map.of("SPRING_RABBITMQ_PASSWORD", "${POCKETHIVE_OUTPUTS_REDIS_PASSWORD}",
        "POCKETHIVE_OUTPUTS_REDIS_HOST", "${SPRING_RABBITMQ_HOST}"), 6379);
    var planned = factory(new ClickHouseSinkProperties()).plan(bee, null);
    var workerEnvironment = new MockEnvironment();
    workerEnvironment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        "systemEnvironment", new LinkedHashMap<>(planned.spec().environment())));
    var binder = Binder.get(workerEnvironment);

    assertThat(binder.bind("spring.rabbitmq", RabbitConnectionSettings.class).get().password()).isEqualTo("old secret");
    assertThat(planned.spec().environment()).containsAllEntriesOf(bee.env());
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("host", binder.bind("pockethive.outputs.redis.host", String.class).get())
        .containsEntry("password", "old secret");
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
    RabbitConnectionSettings rabbit = new RabbitConnectionSettings("rabbitmq", 5672, "guest", "guest", "/");
    return new SwarmWorkerSpecFactory(
        properties,
        workerSettings,
        rabbit,
        () -> "control-network",
        clickHouse,
        RuntimeFilesystemMount.of("/opt/pockethive/scenarios-runtime"),
        () -> "template-1");
  }

  private static Bee redisOutputBee(Map<String, String> environment, int port) {
    return new Bee("generator", "generator:test", Work.ofDefaults(null, null), environment,
        Map.of("outputs", Map.of("type", "REDIS", "redis", Map.of(
            "host", "redis", "port", port, "ssl", false, "password", "old secret",
            "sourceStep", "LAST", "pushDirection", "RPUSH", "maxLen", -1, "defaultList", "out"))));
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
