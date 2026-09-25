package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.*;
import io.pockethive.work.config.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
/**
 * Responsibility: project selected Rabbit Work bootstrap settings and connection through their canonical owners.
 * Must not: choose a WorkPlane, resolve resource names, duplicate field rules or create clients.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-work-connection-environment.
 */
public final class RabbitWorkBootstrapEnvironment implements WorkAdapterEnvironment {
  private final RabbitConnectionSettings connection;
  private final RabbitWorkEnvironment rabbitEnvironment = new RabbitWorkEnvironment();
  private final RabbitWorkSettingsBootstrap rabbitWorkSettingsBootstrap =
      new RabbitWorkSettingsBootstrap(RabbitConfiguration.inputParser(), RabbitConfiguration.outputParser());

  public RabbitWorkBootstrapEnvironment(RabbitConnectionSettings connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }
  @Override public Map<String, String> connectionEnvironment() {
    var result = new LinkedHashMap<>(RabbitConnectionEnvironment.encodeWork(connection));
    result.putAll(new WorkPlaneSelection(WorkerInputType.RABBITMQ).environment());
    return Map.copyOf(result);
  }
  @Override public void validateConnection(Function<String, String> properties) {
    RabbitConnectionEnvironment.decodeWork(properties);
  }
  @Override public List<WorkConfigurationProblem> overrideProblems(Function<String, String> properties) {
    return rabbitEnvironment.overrideProblems(properties);
  }
  @Override public WorkBootstrapProjection bootstrap(Map<String, Object> configuration,
                                                       Map<String, String> destinationEnvironment) {
    var materialized = materializeRabbitWorkSettings(configuration, destinationEnvironment);
    var environment = new LinkedHashMap<String, String>();
    exportRabbitSettings(materialized, environment);
    return new WorkBootstrapProjection(materialized, environment);
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

}
