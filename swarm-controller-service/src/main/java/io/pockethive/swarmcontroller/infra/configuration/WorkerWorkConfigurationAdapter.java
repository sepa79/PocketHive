package io.pockethive.swarmcontroller.infra.configuration;

import io.pockethive.work.config.WorkDeliveryEnvironment;
import io.pockethive.work.config.WorkDeliveryParser;

import io.pockethive.rabbit.api.RabbitConnectionEnvironment;
import io.pockethive.redis.config.RedisDatasetEnvironment;
import io.pockethive.redis.config.RedisOutputEnvironment;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SpringConnectionEnvironment;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationPort;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationResult;
import io.pockethive.swarmcontroller.runtime.environment.WorkConnectionEnvironmentResolver;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.work.config.WorkAdapterEnvironment;
import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationFields;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.policy.InputLifecyclePolicy;
import io.pockethive.work.config.policy.WorkSelectorEnvironmentPolicy;
import io.pockethive.work.local.csv.CsvDatasetEnvironment;
import io.pockethive.work.local.scheduler.SchedulerSettingsEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: compose worker Work environment and bootstrap through existing settings owners.
 * Must not: duplicate field constraints, provision resources, read process settings or own worker state.
 * Contract: RESP-CONTROLLER-WORK-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-controller-work-configuration.
 * Work addresses are projections of the supplied resolved topology; the final candidate passes neutral RESOLVED validation.
 */
public final class WorkerWorkConfigurationAdapter implements WorkerWorkConfigurationPort {
  private static final Logger log = LoggerFactory.getLogger(WorkerWorkConfigurationAdapter.class);
  private final WorkConfigurationParser parser;
  private final WorkAdapterEnvironment workEnvironment;
  private final InputLifecyclePolicy inputControls;
  private final CsvDatasetEnvironment csvEnvironment;
  private final SchedulerSettingsEnvironment schedulerEnvironment;
  private final RedisDatasetEnvironment redisDatasetEnvironment;
  private final RedisOutputEnvironment redisOutputEnvironment;
  private final WorkConnectionEnvironmentResolver connectionsResolver;

  public WorkerWorkConfigurationAdapter(WorkAdapterEnvironment workEnvironment,
      InputLifecyclePolicy inputControls, CsvDatasetEnvironment csvEnvironment,
      SchedulerSettingsEnvironment schedulerEnvironment, RedisDatasetEnvironment redisDatasetEnvironment,
      WorkConnectionEnvironmentResolver connectionsResolver,
      WorkConfigurationParser parser, RedisOutputEnvironment redisOutputEnvironment) {
    this.redisOutputEnvironment = Objects.requireNonNull(redisOutputEnvironment, "redisOutputEnvironment");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.workEnvironment = Objects.requireNonNull(workEnvironment, "workEnvironment");
    this.inputControls = Objects.requireNonNull(inputControls, "inputControls");
    this.csvEnvironment = Objects.requireNonNull(csvEnvironment, "csvEnvironment");
    this.schedulerEnvironment = Objects.requireNonNull(schedulerEnvironment, "schedulerEnvironment");
    this.redisDatasetEnvironment = Objects.requireNonNull(redisDatasetEnvironment, "redisDatasetEnvironment");
    this.connectionsResolver = Objects.requireNonNull(connectionsResolver, "connectionsResolver");
  }

  @Override
  public void validateDeclaration(Bee bee) {
    Objects.requireNonNull(bee, "bee");
    var controlOverrides = RabbitConnectionEnvironment.controlOverrideProblems(bee.env());
    if (!controlOverrides.isEmpty()) throw new WorkConfigurationException(controlOverrides);
    var unsupported = inputControls.configurationProblems(
        bee.config().get(WorkConfigurationFields.INPUTS), WorkConfigurationFields.INPUTS);
    if (!unsupported.isEmpty()) throw new WorkConfigurationException(unsupported);
    var selectors = new WorkSelectorEnvironmentPolicy()
        .problems(SpringConnectionEnvironment.raw(bee.env()));
    if (!selectors.isEmpty()) throw new WorkConfigurationException(selectors);
    if (SpringConnectionEnvironment.containsPropertyTree(bee.env(), WorkDeliveryEnvironment.PREFIX)) {
      throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(
          WorkDeliveryParser.PATH, "Delivery belongs in config; ENV overrides are unsupported.")));
    }
    redisOutputEnvironment.validateOverrides(path -> SpringConnectionEnvironment.containsPropertyTree(bee.env(), path));
    var overrides = workEnvironment.overrideProblems(SpringConnectionEnvironment.raw(bee.env()));
    if (!overrides.isEmpty()) throw new WorkConfigurationException(overrides);
  }

  @Override
  public WorkerWorkConfigurationResult compose(Bee bee, Map<String, Object> effectiveConfig,
      Map<String, String> baseEnvironment, ResolvedWorkTopology topology) {
    Objects.requireNonNull(bee, "bee");
    Objects.requireNonNull(effectiveConfig, "effectiveConfig");
    validateDeclaration(bee);
    Map<String, String> environment = new LinkedHashMap<>(baseEnvironment);
    environment.putAll(workEnvironment.connectionEnvironment());
    applyWorkIoEnvironment(bee, environment, topology);
    var bootstrap = workEnvironment.bootstrap(effectiveConfig, environment);
    var materialized = bootstrap.configuration();
    environment.putAll(bootstrap.environment());
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
    var finalEnvironment = new LinkedHashMap<>(connections.environment());
    finalEnvironment.remove(WorkDeliveryEnvironment.DELAY_ENV);
    finalEnvironment.putAll(new WorkDeliveryEnvironment()
        .encode(validation.configuration().outputDelivery()));
    return new WorkerWorkConfigurationResult(finalEnvironment, resolvedConfig);
  }

  private void applyWorkIoEnvironment(Bee bee, Map<String, String> environment, ResolvedWorkTopology topology) {
    Work work = bee.work();
    if (work != null) {
      String inputQueue = work.defaultIn();
      String outputQueue = work.defaultOut();
      boolean hasInput = hasText(inputQueue);
      boolean hasOutput = hasText(outputQueue);
      if (hasInput) {
        environment.putAll(topology.channel(inputQueue).inputEnvironment());
      } else if (!work.in().isEmpty()) {
        log.warn("Bee {} declares input ports without a default; skipping input queue wiring", bee.role());
      }
      if (hasOutput) {
        environment.putAll(topology.channel(outputQueue).outputEnvironment());
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
