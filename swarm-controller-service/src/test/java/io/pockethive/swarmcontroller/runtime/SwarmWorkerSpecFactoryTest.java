package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarmcontroller.config.SwarmControllerMetricsProperties;

import io.pockethive.rabbit.api.RabbitResourceNames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemMount;
import io.pockethive.controlplane.spring.MetricsSettings;
import io.pockethive.controlplane.spring.WorkerSettings;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.SpringConnectionEnvironment;
import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.redis.config.RedisConfigurationParser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import io.pockethive.rabbit.api.RabbitWorkSettingsBootstrap;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class SwarmWorkerSpecFactoryTest {

  private static Map<String, Object> csvSettings() {
    return Map.of("filePath", "/data/input.csv", "ratePerSec", 1, "rotate", false, "skipHeader", true,
        "delimiter", ",", "charset", "UTF-8", "startupDelaySeconds", 0, "tickIntervalMs", 1000);
  }

  @Test
  void materializesRabbitDefaultsFromFinalTopologyEnvironment() {
    Bee bee = new Bee(
        "generator",
        "generator:test",
        Work.ofDefaults("input", "output"),
        Map.of(),
        Map.of(
            "inputs", Map.of("type", "RABBITMQ", "rabbit", Map.of()),
            "outputs", Map.of("type", "RABBITMQ", "rabbit", Map.of())));

    PlannedSwarmWorker planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));

    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("inputs")).get("rabbit"))).isEqualTo(Map.of(
        "queue", "ph.test.input",
        "prefetch", 50,
        "concurrentConsumers", 1,
        "exclusive", false));
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("rabbit"))).isEqualTo(Map.of(
        "exchange", "ph.test.hive",
        "routingKey", "ph.test.output",
        "persistent", true,
        "publisherConfirms", false));
  }

  @Test
  void preservesDeclaredRabbitTuningAndUsesTopologyValues() {
    Bee bee = new Bee(
        "generator",
        "generator:test",
        Work.ofDefaults("input", "output"),
        Map.of(),
        Map.of(
            "inputs", Map.of("type", "RABBITMQ", "rabbit", Map.of(
                "prefetch", 7, "concurrentConsumers", 3, "exclusive", "false")),
            "outputs", Map.of("type", "RABBITMQ", "rabbit", Map.of(
                "persistent", "false", "publisherConfirms", true))));

    PlannedSwarmWorker planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));

    assertThat(planned.spec().environment())
        .containsEntry("POCKETHIVE_INPUTS_RABBIT_PREFETCH", "7")
        .containsEntry("POCKETHIVE_INPUTS_RABBIT_CONCURRENTCONSUMERS", "3")
        .containsEntry("POCKETHIVE_OUTPUTS_RABBIT_PERSISTENT", "false")
        .containsEntry(RabbitWorkSettingsBootstrap.INPUT_QUEUE_ENV, "ph.test.input")
        .containsEntry(RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV, "ph.test.hive")
        .containsEntry(RabbitWorkSettingsBootstrap.OUTPUT_ROUTING_KEY_ENV, "ph.test.output");
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("inputs")).get("rabbit"))).isEqualTo(Map.of(
        "queue", "ph.test.input",
        "prefetch", 7,
        "concurrentConsumers", 3,
        "exclusive", false));
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("rabbit"))).isEqualTo(Map.of(
        "exchange", "ph.test.hive",
        "routingKey", "ph.test.output",
        "persistent", false,
        "publisherConfirms", true));
  }

  @Test
  void rejectsCompetingIoSelectorsBeforeExternalContextLookup() {
    for (String key : List.of("POCKETHIVE_INPUTS_TYPE", "POCKETHIVE_OUTPUTS_TYPE",
        "pockethive.inputs.type", "pockethive.outputs.type")) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var bee = new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of(key, "NONE"),
          Map.of("inputs", Map.of("type", "RABBITMQ"), "outputs", Map.of("type", "RABBITMQ")));
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties(), () -> {
        calls.incrementAndGet(); return "network";
      }).plan(bee, null, topology(bee))).isInstanceOf(WorkConfigurationException.class).hasMessageContaining("selection belongs in config");
      assertThat(calls).hasValue(0);
    }
  }

  @Test
  void rejectsExplicitNullRabbitTuningForEitherDirection() {
    for (String root : List.of("inputs", "outputs")) {
      var block = new java.util.LinkedHashMap<String, Object>();
      block.put("type", "RABBITMQ");
      block.put("rabbit", null);
      var config = new java.util.LinkedHashMap<String, Object>();
      config.put("inputs", Map.of("type", "RABBITMQ"));
      config.put("outputs", Map.of("type", "RABBITMQ"));
      config.put(root, block);
      var bee = new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of(), config);
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee)))
          .isInstanceOf(WorkConfigurationException.class).hasMessageContaining(root + ".rabbit");
    }
  }

  @Test
  void rejectsRabbitEnvironmentBypassesBeforeExternalContextLookup() {
    for (String key : List.of("POCKETHIVE_INPUT_RABBIT_QUEUE", "POCKETHIVE_INPUTS_RABBIT_QUEUE",
        "POCKETHIVE_OUTPUTS_RABBIT_ROUTINGKEY", "POCKETHIVE_INPUTS_RABBIT_PREFETCH")) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      Bee bee = new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of(key, "other"),
          Map.of("inputs", Map.of("type", "RABBITMQ"), "outputs", Map.of("type", "RABBITMQ")));
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties(), () -> {
        calls.incrementAndGet(); return "network";
      }).plan(bee, null, topology(bee))).isInstanceOf(WorkConfigurationException.class).hasMessageContaining("owned by topology");
      assertThat(calls).hasValue(0);
    }
  }

  @Test
  void rejectsInvalidOrConflictingRabbitDeclarationsBeforeReturningPlan() {
    Bee conflicting = new Bee(
        "generator",
        "generator:test",
        Work.ofDefaults("input", null),
        Map.of(), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("type", "RABBITMQ", "rabbit", Map.of("queue", "another-input"))));
    Bee invalid = new Bee(
        "generator",
        "generator:test",
        Work.ofDefaults("input", null),
        Map.of(), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("type", "RABBITMQ", "rabbit", Map.of("prefetch", 0))));

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(conflicting, null, topology(conflicting)))
        .isInstanceOf(WorkConfigurationException.class)
        .hasMessageContaining("owned by topology");
    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(invalid, null, topology(invalid)))
        .isInstanceOf(WorkConfigurationException.class)
        .hasMessageContaining("positive 32-bit integer");
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
            "outputs", Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379, "ssl", false,
                "sourceStep", "FIRST", "pushDirection", "RPUSH", "maxLen", 0, "defaultList", "out"))));
    var plan = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));
    var properties = io.pockethive.swarmcontroller.config.SpringConnectionEnvironment.resolved(plan.spec().environment());
    var startup = new io.pockethive.work.local.csv.CsvDatasetParser().parse(
        new io.pockethive.work.local.csv.CsvDatasetEnvironment().candidate(Map.of(), properties), "inputs.csv");
    var bootstrap = objectMap(objectMap(plan.bootstrapConfig().get("inputs")).get("csv"));
    assertThat(bootstrap).isEqualTo(io.pockethive.work.local.csv.CsvDatasetParser.configuration(startup));
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
    assertThatThrownBy(() -> factory.plan(new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("type", "CSV_DATASET", "csv", fields))), null, emptyTopology()))
        .hasMessageContaining("inputs.csv.filePath");
    for (String value : List.of("yes", "", "${MISSING_FLAG}")) {
      assertThatThrownBy(() -> factory.plan(new Bee("generator", "generator:test", Work.ofDefaults(null, null),
          Map.of("POCKETHIVE_INPUTS_CSV_ROTATE", value), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("type", "CSV_DATASET", "csv", csvSettings()))), null, emptyTopology()))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void schedulerOverridesDefaultsAndCrossFieldPlaceholdersReachStartupAndBootstrap() {
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(
        "POCKETHIVE_INPUTS_SCHEDULER_RATE_PER_SEC", "${pockethive.outputs.redis.port}"), Map.of(
        "inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1, "maxMessages", 0, "reset", true)),
        "outputs", Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379, "ssl", false,
                "sourceStep", "FIRST", "pushDirection", "RPUSH", "maxLen", 0, "defaultList", "out"))));

    var planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));
    var properties = SpringConnectionEnvironment.resolved(planned.spec().environment());
    var bootstrap = objectMap(objectMap(planned.bootstrapConfig().get("inputs")).get("scheduler"));

    assertThat(properties.apply("pockethive.inputs.scheduler.rate-per-sec")).isEqualTo("6379");
    assertThat(planned.spec().environment())
        .containsEntry("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC", "${pockethive.outputs.redis.port}")
        .containsEntry("POCKETHIVE_INPUTS_SCHEDULER_INITIALDELAYMS", "0")
        .containsEntry("POCKETHIVE_INPUTS_SCHEDULER_TICKINTERVALMS", "1000")
        .containsEntry("POCKETHIVE_INPUTS_SCHEDULER_MAXPENDINGTICKS", "1")
        .doesNotContainKey("POCKETHIVE_INPUTS_SCHEDULER_RESET");
    assertThat(bootstrap).containsEntry("ratePerSec", 6379.0).containsEntry("maxMessages", 0L)
        .containsEntry("initialDelayMs", 0L).containsEntry("tickIntervalMs", 1000L)
        .containsEntry("maxPendingTicks", 1).containsEntry("reset", true);
  }

  @Test
  void schedulerRejectsInvalidFinalOverridesBeforeReturningWorkerPlan() {
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null),
        Map.of("POCKETHIVE_INPUTS_SCHEDULER_MAX_MESSAGES", "-1"), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1, "maxMessages", 0))));

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee)))
        .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("inputs.scheduler.maxMessages");
  }

  @Test
  void redisDatasetExportBindsTheFrozenEnvironmentAndMatchesBootstrapSettings() {
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(
        "POCKETHIVE_INPUTS_REDIS_PORT", "6381", "DATASET_HOST", "redis", "DATASET_SOURCE", "orders",
        "DATASET_WEIGHT", "2.5", "DATASET_RATE", "3"), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of(
            "type", "REDIS_DATASET", "redis", Map.of("host", "${DATASET_HOST}", "port", 0, "ssl", false,
                "sources", List.of(Map.of("listName", "${DATASET_SOURCE}", "weight", "${DATASET_WEIGHT}")),
                "pickStrategy", "ROUND_ROBIN", "ratePerSec", "${DATASET_RATE}"))));

    var planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));
    var properties = SpringConnectionEnvironment.resolved(planned.spec().environment());
    var bootstrap = objectMap(objectMap(planned.bootstrapConfig().get("inputs")).get("redis"));

    assertThat(planned.spec().environment())
        .containsEntry("POCKETHIVE_INPUTS_REDIS_HOST", "${DATASET_HOST}")
        .containsEntry("POCKETHIVE_INPUTS_REDIS_PORT", "6381")
        .containsEntry("POCKETHIVE_INPUTS_REDIS_SOURCES_0_LISTNAME", "${DATASET_SOURCE}")
        .containsEntry("POCKETHIVE_INPUTS_REDIS_SOURCES_0_WEIGHT", "${DATASET_WEIGHT}");
    assertThat(properties.apply("pockethive.inputs.redis.sources[0].list-name")).isEqualTo("orders");
    var settings = new RedisConfigurationParser().parseRedisDatasetSettings(bootstrap, "inputs.redis");
    assertThat(settings.connection().port()).isEqualTo(6381);
    assertThat(settings.connection().host()).isEqualTo("redis");
    assertThat(settings.sources()).extracting(source -> source.getListName()).containsExactly("orders");
    assertThat(settings.sources()).extracting(source -> source.getWeight()).containsExactly(2.5);
    assertThat(settings.ratePerSec()).isEqualTo(3.0);
  }

  @Test
  void plansCanonicalWorkerEnvironmentConfigAndVolumeOrder() {
    ClickHouseSinkProperties clickHouse = new ClickHouseSinkProperties();
    clickHouse.setEndpoint("http://clickhouse:8123");
    clickHouse.setTable(" events ");
    clickHouse.setUsername(" writer ");
    clickHouse.setPassword(" pass ");
    clickHouse.setConnectTimeoutMs(123);
    clickHouse.setReadTimeoutMs(456);
    clickHouse.setBatchSize(7);
    clickHouse.setFlushIntervalMs(89);
    clickHouse.setMaxBufferedEvents(999);
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
            "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", "http://worker-clickhouse:8123",
            "POCKETHIVE_SINK_CLICKHOUSE_USERNAME", "",
            "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE", "42"), Map.of("outputs", Map.of("type", "NONE"),
            "inputs", Map.of("type", "csv_dataset", "csv", csvSettings()),
            "docker", Map.of("volumes", List.of(" /host/input:/data:ro ")),
            "sut", Map.of("targetEndpointId", "default")));

    PlannedSwarmWorker planned = factory.plan(bee, sutEnvironment, topology(bee));

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
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_TABLE", "events")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_USERNAME", "")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_PASSWORD", "pass")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS", "123")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS", "456")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE", "42")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS", "89")
        .containsEntry("POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS", "999");
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

      var planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));

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
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null), Map.of(), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379))));

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee)))
        .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
        .hasMessageContaining("outputs.redis.ssl");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPRING_RABBITMQ_PORT", "POCKETHIVE_OUTPUTS_REDIS_PORT", "pockethive.outputs.redis.port"})
  void rejectsInvalidConnectionOverrideBeforeReturningWorkerPlan(String variable) {
    var bee = redisOutputBee(Map.of(variable, "0"), 6379);

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("port");
  }

  @Test
  void validOverridesReachBothStartupAndBootstrapWithoutChangingSource() {
    var bee = redisOutputBee(Map.of(
        "POCKETHIVE_OUTPUTS_REDIS_PORT", "6381",
        "pockethive.outputs.redis.password", " new secret "), 6379);

    var planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));

    assertThat(planned.spec().environment())
        .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PORT", "6381")
        .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PASSWORD", " new secret ");
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("port", 6381).containsEntry("password", " new secret ")
        .containsEntry("defaultList", "out");
    assertThat(objectMap(objectMap(bee.config().get("outputs")).get("redis")))
        .containsEntry("port", 6379).containsEntry("password", "old secret");
  }

  @Test
  void redisOutputOverridesProduceOneEffectiveBootstrapAndEnvironment() {
    for (String key : List.of("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", "POCKETHIVE_OUTPUTS_REDIS_PUSH_DIRECTION",
        "pockethive.outputs.redis.push-direction")) {
      var bee = redisOutputBee(Map.of(key, "${DIRECTION}", "DIRECTION", "LPUSH",
          "POCKETHIVE_OUTPUTS_REDIS_MAXLEN", "42", "pockethive.outputs.redis.default-list", "selected"), 6379);
      var planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));
      var properties = SpringConnectionEnvironment.resolved(planned.spec().environment());
      var output = objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis"));
      assertThat(output).containsEntry("pushDirection", "LPUSH").containsEntry("maxLen", 42)
          .containsEntry("defaultList", "selected");
      assertThat(properties.apply("pockethive.outputs.redis.push-direction")).isEqualTo(output.get("pushDirection"));
      assertThat(Integer.parseInt(properties.apply("pockethive.outputs.redis.max-len"))).isEqualTo(output.get("maxLen"));
      assertThat(properties.apply("pockethive.outputs.redis.default-list")).isEqualTo(output.get("defaultList"));
      assertThat(objectMap(objectMap(bee.config().get("outputs")).get("redis")))
          .containsEntry("defaultList", "out");
    }
  }

  @Test
  void rejectsInvalidRedisOutputOverridesAndEnvironmentRoutesBeforeReturningPlan() {
    for (String key : List.of("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", "pockethive.outputs.redis.push-direction",
        "POCKETHIVE_OUTPUTS_REDIS_MAXLEN", "POCKETHIVE_OUTPUTS_REDIS_SOURCESTEP")) {
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(redisOutputBee(Map.of(key, "INVALID"), 6379), null, emptyTopology()))
          .isInstanceOf(WorkConfigurationException.class);
    }
    for (String key : List.of("POCKETHIVE_OUTPUTS_REDIS_ROUTES_0_LIST", "POCKETHIVE_OUTPUTS_REDIS_ROUTES_3_LIST",
        "pockethive.outputs.redis.routes[0].list", "POCKETHIVE_OUTPUTS_REDIS_ROUTES")) {
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(redisOutputBee(Map.of(key, "other"), 6379), null, emptyTopology()))
          .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("routes belong in config");
    }
  }

  @Test
  void validatesAfterOverridesHaveCorrectedTheDeclaredConnection() {
    var planned = factory(new ClickHouseSinkProperties())
        .plan(redisOutputBee(Map.of("POCKETHIVE_OUTPUTS_REDIS_PORT", "6381"), 0), null, emptyTopology());

    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("port", 6381);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPRING_RABBITMQ_HOST", "SPRING_RABBITMQ_PORT", "SPRING_RABBITMQ_USERNAME",
      "SPRING_RABBITMQ_PASSWORD", "SPRING_RABBITMQ_VIRTUALHOST", "SPRING_RABBITMQ_VIRTUAL_HOST",
      "spring.rabbitmq.virtual-host", "SPRING_RABBITMQ_ADDRESSES", "SPRING_RABBITMQ_SSL_ENABLED"})
  void rejectsControlConnectionOverridesBeforeReturningPlan(String key) {
    for (String value : List.of("other", "", "${SECRET}")) {
      assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(redisOutputBee(Map.of(key, value), 6379), null, emptyTopology()))
          .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("per-worker overrides are unsupported")
          .hasMessageNotContaining("${SECRET}");
    }
  }

  @Test
  void springAliasesAndPlaceholdersReachBothStartupAndBootstrap() {
    var planned = factory(new ClickHouseSinkProperties()).plan(redisOutputBee(Map.of(
        "POCKETHIVE_OUTPUTS_REDIS_PASSWORD", "${REDIS_SECRET}",
        "REDIS_SECRET", " secret "), 6379), null, emptyTopology());
    var workerEnvironment = new MockEnvironment();
    workerEnvironment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        "systemEnvironment", new LinkedHashMap<>(planned.spec().environment())));
    var startup = Binder.get(workerEnvironment).bind("spring.rabbitmq", RabbitConnectionSettings.class).get();
    assertThat(startup.virtualHost()).isEqualTo("/");
    assertThat(planned.spec().environment()).containsEntry("SPRING_RABBITMQ_VIRTUAL_HOST", startup.virtualHost())
        .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PASSWORD", "${REDIS_SECRET}");
    assertThat(objectMap(objectMap(planned.bootstrapConfig().get("outputs")).get("redis")))
        .containsEntry("password", " secret ");
  }

  @Test
  void rejectsRabbitPasswordThatResolvesToEmptyDeclaredRedisPassword() {
    var bee = new Bee("generator", "generator:test", Work.ofDefaults(null, null),
        Map.of("SPRING_RABBITMQ_PASSWORD", "${POCKETHIVE_OUTPUTS_REDIS_PASSWORD}"), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "REDIS", "redis", Map.of(
            "host", "redis", "port", 6379, "ssl", false, "password", ""))));

    assertThatThrownBy(() -> factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee)))
        .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("spring.rabbitmq.password");
  }

  @Test
  void resolvesConnectionReferencesAgainstCompleteUnchangedEnvironment() {
    var bee = redisOutputBee(Map.of("POCKETHIVE_OUTPUTS_REDIS_HOST", "${SPRING_RABBITMQ_HOST}"), 6379);
    var planned = factory(new ClickHouseSinkProperties()).plan(bee, null, topology(bee));
    var workerEnvironment = new MockEnvironment();
    workerEnvironment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        "systemEnvironment", new LinkedHashMap<>(planned.spec().environment())));
    var binder = Binder.get(workerEnvironment);

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

  @Test
  void rejectsRemovedControlsBeforeResolvingExternalNetworkContext() {
    var networkReads = new java.util.concurrent.atomic.AtomicInteger();
    var factory = factory(new ClickHouseSinkProperties(), () -> {
      networkReads.incrementAndGet();
      return "control-network";
    });
    Bee invalid = new Bee("generator", "image", Work.ofDefaults(null, null), Map.of(), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("type", "SCHEDULER", "scheduler",
            Map.of("ratePerSec", 1.0, "enabled", true))));
    assertThatThrownBy(() -> factory.plan(invalid, null, topology(invalid)))
        .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("enabled");
    assertThat(networkReads).hasValue(0);
  }

  private static SwarmWorkerSpecFactory factory(ClickHouseSinkProperties clickHouse) {
    return factory(clickHouse, () -> "control-network");
  }

  private static SwarmWorkerSpecFactory factory(ClickHouseSinkProperties clickHouse,
      java.util.function.Supplier<String> controlNetwork) {
    SwarmControllerProperties properties = properties();
    WorkerSettings workerSettings = new WorkerSettings(
        properties.getSwarmId(),
        "run-1",
        properties.getControlExchange(),
        properties.getControlQueuePrefixBase(),
        new MetricsSettings(
            PocketHiveMetricsAdapter.DISABLED,
            Duration.ofSeconds(10),
            ClickHouseMetricsSinkProperties.disabled()));
    RabbitConnectionSettings rabbit = new RabbitConnectionSettings("rabbitmq", 5672, "guest", "guest", "/");
    return new SwarmWorkerSpecFactory(
        properties,
        workerSettings,
        rabbit,
        controlNetwork,
        clickHouse,
        RuntimeFilesystemMount.of("/opt/pockethive/scenarios-runtime"),
        () -> "template-1",
        new io.pockethive.swarmcontroller.config.WorkerWorkConfigurationComposition()
            .workerWorkConfiguration(new io.pockethive.rabbit.work.RabbitWorkBootstrapEnvironment(new io.pockethive.rabbit.api.RabbitConnectionSettings("work-broker", 5673, "worker", "worksecret", "/work"))));
  }

  private static io.pockethive.topology.work.ResolvedWorkTopology emptyTopology() {
    return new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(new io.pockethive.rabbit.api.RabbitResourceNames(),
        new io.pockethive.rabbit.api.RabbitResourceNames()::forSwarm).resolve("test", java.util.Set.of());
  }

  private static io.pockethive.topology.work.ResolvedWorkTopology topology(Bee bee) {
    return new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(new io.pockethive.rabbit.api.RabbitResourceNames(),
        swarm -> new io.pockethive.rabbit.api.RabbitWorkTopologySettings("ph.test", "ph.test.hive"))
        .resolve(properties().getSwarmId(), io.pockethive.topology.work.WorkTopologyChannels.from(java.util.List.of(bee)));
  }

  private static Bee redisOutputBee(Map<String, String> environment, int port) {
    return new Bee("generator", "generator:test", Work.ofDefaults(null, null), environment, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "REDIS", "redis", Map.of(
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
            new SwarmControllerMetricsProperties(
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
