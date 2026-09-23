package io.pockethive.swarmcontroller.runtime;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemMount;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.WorkerSettings;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.docker.DockerRuntimeNames;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.util.BeeNameGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import io.pockethive.rabbit.api.RabbitConnectionSettings;

/**
 * Responsibility: assemble worker identity, base environment, SUT context, mounts and the delegated Work result into a spec.
 * Must not: interpret Work settings, construct Work providers, provision workers or mutate accepted state.
 * Contract: RESP-CONTROLLER-WORKER-PLAN — docs/architecture/runtime-responsibilities.md#resp-controller-worker-plan.
 * Consumes: RESP-CONTROLLER-WORK-CONFIGURATION through WorkerWorkConfigurationPort.
 */
public final class SwarmWorkerSpecFactory {

  private final SwarmControllerProperties properties;
  private final WorkerSettings workerSettings;
  private final RabbitConnectionSettings rabbitConnection;
  private final Supplier<String> controlNetwork;
  private final Supplier<String> templateId;
  private final ClickHouseSinkProperties clickHouseSink;
  private final RuntimeFilesystemMount runtimeFilesystemMount;
  private final WorkerWorkConfigurationPort workConfiguration;

  public SwarmWorkerSpecFactory(
      SwarmControllerProperties properties,
      WorkerSettings workerSettings,
      RabbitConnectionSettings rabbitConnection,
      Supplier<String> controlNetwork,
      ClickHouseSinkProperties clickHouseSink,
      RuntimeFilesystemMount runtimeFilesystemMount,
      Supplier<String> templateId,
      WorkerWorkConfigurationPort workConfiguration) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workerSettings = Objects.requireNonNull(workerSettings, "workerSettings");
    this.rabbitConnection = Objects.requireNonNull(rabbitConnection, "rabbitConnection");
    this.controlNetwork = Objects.requireNonNull(controlNetwork, "controlNetwork");
    this.templateId = Objects.requireNonNull(templateId, "templateId");
    this.clickHouseSink = Objects.requireNonNull(clickHouseSink, "clickHouseSink");
    this.runtimeFilesystemMount = Objects.requireNonNull(runtimeFilesystemMount, "runtimeFilesystemMount");
    this.workConfiguration = Objects.requireNonNull(workConfiguration, "workConfiguration");
  }

  public PlannedSwarmWorker plan(Bee bee, SutEnvironment sutEnvironment, io.pockethive.topology.work.ResolvedWorkTopology topology) {
    Objects.requireNonNull(bee, "bee");
    workConfiguration.validateDeclaration(bee);
    String beeName = BeeNameGenerator.generate(bee.role(), properties.getSwarmId());
    Map<String, String> environment = new LinkedHashMap<>(
        ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            beeName, bee.role(), workerSettings, rabbitConnection));
    environment.put("POCKETHIVE_JOURNAL_RUN_ID", workerSettings.runId());
    environment.put("POCKETHIVE_TEMPLATE_ID", requireText(templateId.get(), "templateId"));
    if (hasText(bee.image())) {
      environment.put("POCKETHIVE_RUNTIME_IMAGE", bee.image());
    }
    environment.put(
        DockerRuntimeNames.STACK_NAME_ENV,
        DockerRuntimeNames.stackName(properties.getSwarmId()));
    applyClickHouseSinkEnvironment(environment);
    String network = controlNetwork.get();
    if (hasText(network)) {
      environment.put("CONTROL_NETWORK", network);
    }
    var work = workConfiguration.compose(bee, enrichConfigWithSut(bee.config(), sutEnvironment), environment, topology);
    Map<String, Object> effectiveConfig = work.bootstrapConfig();
    List<String> configuredVolumes = resolveVolumes(effectiveConfig);
    List<String> volumes = new ArrayList<>(configuredVolumes.size() + 1);
    volumes.add(runtimeFilesystemMount.volume());
    volumes.addAll(configuredVolumes);

    WorkerSpec spec = new WorkerSpec(
        beeName,
        bee.role(),
        bee.image(),
        work.environment(),
        List.copyOf(volumes));
    return new PlannedSwarmWorker(spec, effectiveConfig);
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
