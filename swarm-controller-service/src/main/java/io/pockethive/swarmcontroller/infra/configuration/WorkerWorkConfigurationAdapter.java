package io.pockethive.swarmcontroller.infra.configuration;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.topology.work.WorkResourceNamesPort;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.SpringConnectionEnvironment;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationPort;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationResult;
import io.pockethive.swarmcontroller.runtime.environment.WorkConnectionEnvironmentResolver;
import io.pockethive.rabbit.api.RabbitWorkSettingsBootstrap;
import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationFields;
import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.policy.InputLifecyclePolicy;
import io.pockethive.work.local.csv.CsvDatasetEnvironment;
import io.pockethive.work.local.scheduler.SchedulerSettingsEnvironment;
import io.pockethive.redis.config.RedisDatasetEnvironment;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: compose worker Work environment and bootstrap through existing settings owners.
 * Must not: duplicate field constraints, provision resources, read process settings or own worker state.
 * Contract: RESP-CONTROLLER-WORK-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-controller-work-configuration.
 * Work naming delegates RESP-WORK-RESOURCE-NAMES; the final bootstrap candidate passes neutral RESOLVED validation.
 */
public final class WorkerWorkConfigurationAdapter implements WorkerWorkConfigurationPort {
  private static final Logger log = LoggerFactory.getLogger(WorkerWorkConfigurationAdapter.class);
  private final WorkConfigurationParser parser;
  private final SwarmControllerProperties properties;
  private final WorkResourceNamesPort names;
  private final io.pockethive.rabbit.api.RabbitWorkEnvironment rabbitEnvironment;
  private final InputLifecyclePolicy inputControls;
  private final CsvDatasetEnvironment csvEnvironment;
  private final SchedulerSettingsEnvironment schedulerEnvironment;
  private final RedisDatasetEnvironment redisDatasetEnvironment;
  private final io.pockethive.redis.config.RedisOutputEnvironment redisOutputEnvironment;
  private final WorkConnectionEnvironmentResolver connectionsResolver;
  private final RabbitWorkSettingsBootstrap rabbitWorkSettingsBootstrap;

  public WorkerWorkConfigurationAdapter(SwarmControllerProperties properties, WorkResourceNamesPort names,
      io.pockethive.rabbit.api.RabbitWorkEnvironment rabbitEnvironment,
      InputLifecyclePolicy inputControls, CsvDatasetEnvironment csvEnvironment,
      SchedulerSettingsEnvironment schedulerEnvironment, RedisDatasetEnvironment redisDatasetEnvironment,
      WorkConnectionEnvironmentResolver connectionsResolver, RabbitWorkSettingsBootstrap rabbitWorkSettingsBootstrap,
      WorkConfigurationParser parser, io.pockethive.redis.config.RedisOutputEnvironment redisOutputEnvironment) {
    this.redisOutputEnvironment = Objects.requireNonNull(redisOutputEnvironment, "redisOutputEnvironment");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.rabbitEnvironment = Objects.requireNonNull(rabbitEnvironment, "rabbitEnvironment");
    this.names = Objects.requireNonNull(names, "names");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.inputControls = Objects.requireNonNull(inputControls, "inputControls");
    this.csvEnvironment = Objects.requireNonNull(csvEnvironment, "csvEnvironment");
    this.schedulerEnvironment = Objects.requireNonNull(schedulerEnvironment, "schedulerEnvironment");
    this.redisDatasetEnvironment = Objects.requireNonNull(redisDatasetEnvironment, "redisDatasetEnvironment");
    this.connectionsResolver = Objects.requireNonNull(connectionsResolver, "connectionsResolver");
    this.rabbitWorkSettingsBootstrap = Objects.requireNonNull(rabbitWorkSettingsBootstrap, "rabbitWorkSettingsBootstrap");
  }

  @Override
  public void validateDeclaration(Bee bee) {
    Objects.requireNonNull(bee, "bee");
    var controlOverrides = io.pockethive.rabbit.api.RabbitConnectionEnvironment.controlOverrideProblems(bee.env());
    if (!controlOverrides.isEmpty()) throw new WorkConfigurationException(controlOverrides);
    var unsupported = inputControls.configurationProblems(
        bee.config().get(WorkConfigurationFields.INPUTS), WorkConfigurationFields.INPUTS);
    if (!unsupported.isEmpty()) throw new WorkConfigurationException(unsupported);
    var selectors = new io.pockethive.work.config.policy.WorkSelectorEnvironmentPolicy()
        .problems(SpringConnectionEnvironment.raw(bee.env()));
    if (!selectors.isEmpty()) throw new WorkConfigurationException(selectors);
    redisOutputEnvironment.validateOverrides(path -> SpringConnectionEnvironment.containsPropertyTree(bee.env(), path));
    var overrides = rabbitEnvironment.overrideProblems(SpringConnectionEnvironment.raw(bee.env()));
    if (!overrides.isEmpty()) throw new WorkConfigurationException(overrides);
  }

  @Override
  public WorkerWorkConfigurationResult compose(Bee bee, Map<String, Object> effectiveConfig,
      Map<String, String> baseEnvironment) {
    Objects.requireNonNull(bee, "bee");
    Objects.requireNonNull(effectiveConfig, "effectiveConfig");
    validateDeclaration(bee);
    Map<String, String> environment = new LinkedHashMap<>(baseEnvironment);
    applyWorkIoEnvironment(bee, environment);
    var materialized = materializeRabbitWorkSettings(effectiveConfig, environment);
    exportRabbitSettings(materialized, environment);
    environment.putAll(bee.env());
    var csvCandidate = csvEnvironment.candidate(bee.config().get(WorkConfigurationFields.INPUTS),
        SpringConnectionEnvironment.raw(bee.env()));
    var schedulerCandidate = schedulerEnvironment.candidate(bee.config().get(WorkConfigurationFields.INPUTS),
        SpringConnectionEnvironment.raw(bee.env()));
    var redisDatasetCandidate = redisDatasetEnvironment.candidate(bee.config().get(WorkConfigurationFields.INPUTS),
        SpringConnectionEnvironment.raw(bee.env()));
    var redisOutputCandidate = redisOutputEnvironment.candidate(bee.config().get(WorkConfigurationFields.OUTPUTS),
        SpringConnectionEnvironment.raw(bee.env()));
    environment.putAll(redisOutputEnvironment.encode(redisOutputCandidate));
    environment.putAll(csvEnvironment.encode(csvCandidate));
    environment.putAll(schedulerEnvironment.encode(schedulerCandidate));
    environment.putAll(redisDatasetEnvironment.encode(redisDatasetCandidate));
    var rawEnvironment = SpringConnectionEnvironment.raw(environment);
    var unsupported = inputControls.propertyProblems(path -> rawEnvironment.apply(path) != null);
    if (!unsupported.isEmpty()) {
      throw new WorkConfigurationException(unsupported);
    }

    var connections = connectionsResolver.resolve(materialized, environment,
        rawEnvironment, SpringConnectionEnvironment::resolved);
    var finalProperties = SpringConnectionEnvironment.resolved(connections.environment());
    Map<String, Object> resolvedConfig = csvEnvironment.resolve(connections.bootstrapConfig(), csvCandidate, finalProperties);
    resolvedConfig = schedulerEnvironment.resolve(resolvedConfig, schedulerCandidate, finalProperties);
    resolvedConfig = redisDatasetEnvironment.resolve(resolvedConfig, redisDatasetCandidate, finalProperties);
    resolvedConfig = redisOutputEnvironment.resolve(resolvedConfig, redisOutputCandidate, finalProperties);
    var validation = parser.validate(resolvedConfig, WorkConfigurationMode.RESOLVED);
    if (!validation.problems().isEmpty()) throw new WorkConfigurationException(validation.problems());
    if (!validation.deferredPaths().isEmpty()) {
      throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(WorkConfigurationFields.INPUTS,
          "Resolved Work configuration must not contain deferred paths.")));
    }
    return new WorkerWorkConfigurationResult(connections.environment(), resolvedConfig);
  }

  private void exportRabbitSettings(Map<String, Object> config, Map<String, String> environment) {
    var input = io(config, WorkConfigurationFields.INPUTS);
    if (selected(input, WorkerInputType.RABBITMQ)) {
      environment.putAll(rabbitEnvironment.input((Map<?, ?>) input.get(WorkerInputType.RABBITMQ.settingsKey())));
    }
    var output = io(config, WorkConfigurationFields.OUTPUTS);
    if (selected(output, WorkerOutputType.RABBITMQ)) {
      environment.putAll(rabbitEnvironment.output((Map<?, ?>) output.get(WorkerOutputType.RABBITMQ.settingsKey())));
    }
  }

  private Map<String, Object> materializeRabbitWorkSettings(
      Map<String, Object> effectiveConfig,
      Map<String, String> finalEnvironment) {
    Map<String, Object> materialized = new LinkedHashMap<>(effectiveConfig);
    materializeRabbitInput(materialized, finalEnvironment);
    materializeRabbitOutput(materialized, finalEnvironment);
    return Map.copyOf(materialized);
  }

  private void materializeRabbitInput(
      Map<String, Object> configuration,
      Map<String, String> finalEnvironment) {
    Map<?, ?> inputs = io(configuration, WorkConfigurationFields.INPUTS);
    if (!selected(inputs, WorkerInputType.RABBITMQ)) {
      return;
    }
    String queue = finalEnvironment.get(RabbitWorkSettingsBootstrap.INPUT_QUEUE_ENV);
    configuration.put(WorkConfigurationFields.INPUTS, withRabbitSettings(
        inputs,
        rabbitWorkSettingsBootstrap.input(
            inputs.containsKey(WorkerInputType.RABBITMQ.settingsKey())
                ? inputs.get(WorkerInputType.RABBITMQ.settingsKey()) : Map.of(), queue)));
  }

  private void materializeRabbitOutput(
      Map<String, Object> configuration,
      Map<String, String> finalEnvironment) {
    Map<?, ?> outputs = io(configuration, WorkConfigurationFields.OUTPUTS);
    if (!selected(outputs, WorkerOutputType.RABBITMQ)) {
      return;
    }
    String exchange = finalEnvironment.get(RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV);
    String routingKey = finalEnvironment.get(RabbitWorkSettingsBootstrap.OUTPUT_ROUTING_KEY_ENV);
    configuration.put(WorkConfigurationFields.OUTPUTS, withRabbitSettings(
        outputs,
        rabbitWorkSettingsBootstrap.output(
            outputs.containsKey(WorkerOutputType.RABBITMQ.settingsKey())
                ? outputs.get(WorkerOutputType.RABBITMQ.settingsKey()) : Map.of(), exchange, routingKey)));
  }

  private static Map<?, ?> io(Map<String, Object> configuration, String root) {
    Object value = configuration.get(root);
    return value instanceof Map<?, ?> fields ? fields : Map.of();
  }

  private static boolean selected(Map<?, ?> fields, WorkerInputType type) {
    return selected(fields, type.name());
  }

  private static boolean selected(Map<?, ?> fields, WorkerOutputType type) {
    return selected(fields, type.name());
  }

  private static boolean selected(Map<?, ?> fields, String type) {
    Object declared = fields.get(WorkConfigurationFields.TYPE);
    return declared instanceof String text && type.equalsIgnoreCase(text.trim());
  }

  private static Map<String, Object> withRabbitSettings(Map<?, ?> root, Map<String, Object> settings) {
    Map<String, Object> materialized = new LinkedHashMap<>();
    root.forEach((key, value) -> materialized.put(Objects.toString(key), value));
    materialized.put(WorkerInputType.RABBITMQ.settingsKey(), settings);
    return Map.copyOf(materialized);
  }

  private void applyWorkIoEnvironment(Bee bee, Map<String, String> environment) {
    Work work = bee.work();
    if (work != null) {
      String inputQueue = work.defaultIn();
      String outputQueue = work.defaultOut();
      boolean hasInput = hasText(inputQueue);
      boolean hasOutput = hasText(outputQueue);
      if (hasInput) {
        var address = names.address(properties.getTraffic().hiveExchange(), properties.getTraffic().queuePrefix(), inputQueue);
        environment.put(RabbitWorkSettingsBootstrap.INPUT_QUEUE_ENV, address.queue());
        environment.put(RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV, address.exchange());
      } else if (!work.in().isEmpty()) {
        log.warn("Bee {} declares input ports without a default; skipping input queue wiring", bee.role());
      }
      if (hasOutput) {
        var address = names.address(properties.getTraffic().hiveExchange(), properties.getTraffic().queuePrefix(), outputQueue);
        environment.put(RabbitWorkSettingsBootstrap.OUTPUT_ROUTING_KEY_ENV, address.routingKey());
        environment.put(RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV, address.exchange());
      } else if (!work.out().isEmpty()) {
        log.warn("Bee {} declares output ports without a default; skipping output queue wiring", bee.role());
      }
    }

    Map<String, Object> config = bee.config();
    if (config == null || config.isEmpty()) {
      return;
    }
    applyInputEnvironment(config.get(WorkConfigurationFields.INPUTS), environment);
    applyOutputEnvironment(config.get(WorkConfigurationFields.OUTPUTS), environment);
  }

  private static void applyInputEnvironment(Object inputs, Map<String, String> environment) {
    if (!(inputs instanceof Map<?, ?> inputsMap)) {
      return;
    }
    putUppercaseType(environment, "POCKETHIVE_INPUTS_TYPE", inputsMap.get(WorkConfigurationFields.TYPE));

  }

  private static void applyOutputEnvironment(Object outputs, Map<String, String> environment) {
    if (!(outputs instanceof Map<?, ?> outputsMap)) {
      return;
    }
    putUppercaseType(environment, "POCKETHIVE_OUTPUTS_TYPE", outputsMap.get(WorkConfigurationFields.TYPE));
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

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
