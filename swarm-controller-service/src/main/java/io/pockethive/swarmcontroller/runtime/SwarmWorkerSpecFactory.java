package io.pockethive.swarmcontroller.runtime;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemMount;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.WorkerSettings;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.util.BeeNameGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Responsibility: Resolve one scenario bee into its effective config, environment, identity, and worker spec.
 * Must not: Register runtime state, publish bootstrap config, provision workers, or mutate the scenario plan.
 * Contract: Build one immutable worker plan from explicit runtime settings and canonical queue/network resolvers.
 */
public final class SwarmWorkerSpecFactory {

  private static final Logger log = LoggerFactory.getLogger(SwarmWorkerSpecFactory.class);

  private final SwarmControllerProperties properties;
  private final WorkerSettings workerSettings;
  private final RabbitProperties rabbitProperties;
  private final Supplier<String> controlNetwork;
  private final Supplier<String> templateId;
  private final ClickHouseSinkProperties clickHouseSink;
  private final RuntimeFilesystemMount runtimeFilesystemMount;

  public SwarmWorkerSpecFactory(
      SwarmControllerProperties properties,
      WorkerSettings workerSettings,
      RabbitProperties rabbitProperties,
      Supplier<String> controlNetwork,
      ClickHouseSinkProperties clickHouseSink,
      RuntimeFilesystemMount runtimeFilesystemMount,
      Supplier<String> templateId) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workerSettings = Objects.requireNonNull(workerSettings, "workerSettings");
    this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
    this.controlNetwork = Objects.requireNonNull(controlNetwork, "controlNetwork");
    this.templateId = Objects.requireNonNull(templateId, "templateId");
    this.clickHouseSink = Objects.requireNonNull(clickHouseSink, "clickHouseSink");
    this.runtimeFilesystemMount = Objects.requireNonNull(runtimeFilesystemMount, "runtimeFilesystemMount");
  }

  public PlannedSwarmWorker plan(Bee bee, SutEnvironment sutEnvironment) {
    Objects.requireNonNull(bee, "bee");
    String beeName = BeeNameGenerator.generate(bee.role(), properties.getSwarmId());
    Map<String, String> environment = new LinkedHashMap<>(
        ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            beeName, bee.role(), workerSettings, rabbitProperties));
    environment.put("POCKETHIVE_JOURNAL_RUN_ID", workerSettings.runId());
    environment.put("POCKETHIVE_TEMPLATE_ID", requireText(templateId.get(), "templateId"));
    if (hasText(bee.image())) {
      environment.put("POCKETHIVE_RUNTIME_IMAGE", bee.image());
    }
    environment.put(
        "POCKETHIVE_RUNTIME_STACK_NAME",
        "ph-" + properties.getSwarmId().toLowerCase(Locale.ROOT));
    applyWorkIoEnvironment(bee, environment);
    applyClickHouseSinkEnvironment(environment);
    String network = controlNetwork.get();
    if (hasText(network)) {
      environment.put("CONTROL_NETWORK", network);
    }
    environment.putAll(bee.env());

    Map<String, Object> effectiveConfig = enrichConfigWithSut(bee.config(), sutEnvironment);
    List<String> configuredVolumes = resolveVolumes(effectiveConfig);
    List<String> volumes = new ArrayList<>(configuredVolumes.size() + 1);
    volumes.add(runtimeFilesystemMount.volume());
    volumes.addAll(configuredVolumes);

    WorkerSpec spec = new WorkerSpec(
        beeName,
        bee.role(),
        bee.image(),
        Map.copyOf(environment),
        List.copyOf(volumes));
    return new PlannedSwarmWorker(spec, effectiveConfig);
  }

  private void applyWorkIoEnvironment(Bee bee, Map<String, String> environment) {
    Work work = bee.work();
    if (work != null) {
      String inputQueue = work.defaultIn();
      String outputQueue = work.defaultOut();
      boolean hasInput = hasText(inputQueue);
      boolean hasOutput = hasText(outputQueue);
      if (hasInput) {
        environment.put("POCKETHIVE_INPUT_RABBIT_QUEUE", properties.queueName(inputQueue));
      } else if (!work.in().isEmpty()) {
        log.warn("Bee {} declares input ports without a default; skipping input queue wiring", bee.role());
      }
      if (hasOutput) {
        environment.put("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY", properties.queueName(outputQueue));
      } else if (!work.out().isEmpty()) {
        log.warn("Bee {} declares output ports without a default; skipping output queue wiring", bee.role());
      }
      if (hasInput || hasOutput) {
        environment.put("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE", properties.hiveExchange());
      }
    }

    Map<String, Object> config = bee.config();
    if (config == null || config.isEmpty()) {
      return;
    }
    applyInputEnvironment(config.get("inputs"), environment);
    applyOutputEnvironment(config.get("outputs"), environment);
  }

  private static void applyInputEnvironment(Object inputs, Map<String, String> environment) {
    if (!(inputs instanceof Map<?, ?> inputsMap)) {
      return;
    }
    putUppercaseType(environment, "POCKETHIVE_INPUTS_TYPE", inputsMap.get("type"));

    Object redis = inputsMap.get("redis");
    if (redis instanceof Map<?, ?> redisMap) {
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_HOST", redisMap.get("host"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_PORT", redisMap.get("port"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_USERNAME", redisMap.get("username"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_PASSWORD", redisMap.get("password"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_SSL", redisMap.get("ssl"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_LISTNAME", redisMap.get("listName"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_PICKSTRATEGY", redisMap.get("pickStrategy"));
      putIndexedEnvIfPresent(
          environment,
          "POCKETHIVE_INPUTS_REDIS_SOURCES",
          redisMap.get("sources"),
          Map.of("listName", "LISTNAME", "weight", "WEIGHT"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_RATEPERSEC", redisMap.get("ratePerSec"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_INITIALDELAYMS", redisMap.get("initialDelayMs"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_REDIS_TICKINTERVALMS", redisMap.get("tickIntervalMs"));
    }

    Object csv = inputsMap.get("csv");
    if (csv instanceof Map<?, ?> csvMap) {
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_FILEPATH", csvMap.get("filePath"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_RATEPERSEC", csvMap.get("ratePerSec"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_ROTATE", csvMap.get("rotate"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_SKIPHEADER", csvMap.get("skipHeader"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_DELIMITER", csvMap.get("delimiter"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_CHARSET", csvMap.get("charset"));
      putEnvIfPresent(
          environment,
          "POCKETHIVE_INPUTS_CSV_STARTUPDELAYSECONDS",
          csvMap.get("startupDelaySeconds"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_TICKINTERVALMS", csvMap.get("tickIntervalMs"));
      putEnvIfPresent(environment, "POCKETHIVE_INPUTS_CSV_ENABLED", csvMap.get("enabled"));
    }
  }

  private static void applyOutputEnvironment(Object outputs, Map<String, String> environment) {
    if (!(outputs instanceof Map<?, ?> outputsMap)) {
      return;
    }
    putUppercaseType(environment, "POCKETHIVE_OUTPUTS_TYPE", outputsMap.get("type"));
    Object redis = outputsMap.get("redis");
    if (redis instanceof Map<?, ?> redisMap) {
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_HOST", redisMap.get("host"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_PORT", redisMap.get("port"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_USERNAME", redisMap.get("username"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_PASSWORD", redisMap.get("password"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_SSL", redisMap.get("ssl"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_SOURCESTEP", redisMap.get("sourceStep"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", redisMap.get("pushDirection"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_DEFAULTLIST", redisMap.get("defaultList"));
      putEnvIfPresent(
          environment,
          "POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE",
          redisMap.get("targetListTemplate"));
      putIndexedEnvIfPresent(
          environment,
          "POCKETHIVE_OUTPUTS_REDIS_ROUTES",
          redisMap.get("routes"),
          Map.of(
              "match", "MATCH",
              "header", "HEADER",
              "headerMatch", "HEADERMATCH",
              "list", "LIST"));
      putEnvIfPresent(environment, "POCKETHIVE_OUTPUTS_REDIS_MAXLEN", redisMap.get("maxLen"));
    }
  }

  private void applyClickHouseSinkEnvironment(Map<String, String> environment) {
    if (!clickHouseSink.configured()) {
      return;
    }
    putEnvIfMissing(environment, "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", clickHouseSink.getEndpoint());
    putEnvIfMissing(environment, "POCKETHIVE_SINK_CLICKHOUSE_TABLE", clickHouseSink.getTable());
    putEnvIfMissing(environment, "POCKETHIVE_SINK_CLICKHOUSE_USERNAME", clickHouseSink.getUsername());
    putEnvIfMissing(environment, "POCKETHIVE_SINK_CLICKHOUSE_PASSWORD", clickHouseSink.getPassword());
    putEnvIfMissing(
        environment,
        "POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS",
        Integer.toString(clickHouseSink.getConnectTimeoutMs()));
    putEnvIfMissing(
        environment,
        "POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS",
        Integer.toString(clickHouseSink.getReadTimeoutMs()));
    putEnvIfMissing(
        environment,
        "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE",
        Integer.toString(clickHouseSink.getBatchSize()));
    putEnvIfMissing(
        environment,
        "POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS",
        Integer.toString(clickHouseSink.getFlushIntervalMs()));
    putEnvIfMissing(
        environment,
        "POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS",
        Integer.toString(clickHouseSink.getMaxBufferedEvents()));
  }

  static List<String> resolveVolumes(Map<String, Object> config) {
    if (config == null || config.isEmpty()) {
      return List.of();
    }
    Object dockerObject = config.get("docker");
    if (!(dockerObject instanceof Map<?, ?> dockerMap) || dockerMap.isEmpty()) {
      return List.of();
    }
    Object volumesObject = dockerMap.get("volumes");
    if (!(volumesObject instanceof List<?> rawList) || rawList.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>(rawList.size());
    for (Object entry : rawList) {
      if (entry instanceof String value && !value.trim().isBlank()) {
        result.add(value.trim());
      }
    }
    return result.isEmpty() ? List.of() : List.copyOf(result);
  }

  static Map<String, Object> enrichConfigWithSut(
      Map<String, Object> config,
      SutEnvironment sutEnvironment) {
    if (sutEnvironment == null || config == null || config.isEmpty()) {
      return config == null || config.isEmpty() ? Map.of() : config;
    }
    Object sutObject = config.get("sut");
    if (!(sutObject instanceof Map<?, ?> rawSut)) {
      return config;
    }
    Object endpointIdObject = rawSut.get("targetEndpointId");
    if (!(endpointIdObject instanceof String endpointIdText)) {
      return config;
    }
    String endpointId = endpointIdText.trim();
    if (endpointId.isEmpty()) {
      return config;
    }
    SutEndpoint endpoint = sutEnvironment.endpoints().get(endpointId);
    if (endpoint == null) {
      return config;
    }

    Map<String, Object> resolvedSut = new LinkedHashMap<>();
    rawSut.forEach((key, value) -> {
      if (key != null) {
        resolvedSut.put(key.toString(), value);
      }
    });
    resolvedSut.put("environmentId", sutEnvironment.id());
    if (hasText(sutEnvironment.type())) {
      resolvedSut.put("environmentType", sutEnvironment.type().trim());
    } else {
      resolvedSut.remove("environmentType");
    }
    resolvedSut.put("environment", sutEnvironment);
    resolvedSut.put("targetEndpointId", endpointId);
    resolvedSut.put("targetEndpoint", endpoint);

    Map<String, Object> enriched = new LinkedHashMap<>(config);
    enriched.put("sut", Map.copyOf(resolvedSut));
    if (hasText(endpoint.baseUrl())) {
      enriched.put("baseUrl", endpoint.baseUrl().trim());
    }
    return Map.copyOf(enriched);
  }

  private static void putUppercaseType(Map<String, String> environment, String key, Object value) {
    if (value == null) {
      return;
    }
    String text = value.toString().trim();
    if (!text.isBlank()) {
      environment.put(key, text.toUpperCase(Locale.ROOT));
    }
  }

  private static void putEnvIfPresent(Map<String, String> environment, String key, Object value) {
    if (value == null) {
      return;
    }
    String text = value.toString().trim();
    if (!text.isBlank()) {
      environment.put(key, text);
    }
  }

  private static void putEnvIfMissing(Map<String, String> environment, String key, String value) {
    if (environment.containsKey(key) || value == null) {
      return;
    }
    String text = value.trim();
    if (!text.isBlank()) {
      environment.put(key, text);
    }
  }

  private static void putIndexedEnvIfPresent(
      Map<String, String> environment,
      String keyPrefix,
      Object value,
      Map<String, String> fieldEnvNames) {
    if (value == null) {
      return;
    }
    if (!(value instanceof Iterable<?> entries)) {
      throw new IllegalStateException(keyPrefix + " must be a list of objects");
    }
    int index = 0;
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> entryMap)) {
        throw new IllegalStateException(keyPrefix + "_" + index + " must be an object");
      }
      for (Map.Entry<String, String> field : fieldEnvNames.entrySet()) {
        putEnvIfPresent(
            environment,
            keyPrefix + "_" + index + "_" + field.getValue(),
            entryMap.get(field.getKey()));
      }
      index++;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String requireText(String value, String field) {
    if (!hasText(value)) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
