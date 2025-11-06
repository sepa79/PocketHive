package io.pockethive.generator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.SchedulerStates;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
class GeneratorRuntimeAdapter {

  private static final Logger log = LoggerFactory.getLogger(GeneratorRuntimeAdapter.class);

  private final RabbitTemplate rabbitTemplate;
  private final RabbitWorkMessageConverter messageConverter = new RabbitWorkMessageConverter();
  private final List<SchedulerWorkInput<GeneratorWorkerConfig>> workInputs = new ArrayList<>();
  private final Clock clock;

  @Autowired
  GeneratorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          ControlPlaneIdentity identity,
                          GeneratorDefaults defaults,
                          WorkInputRegistry inputRegistry) {
    this(workerRuntime, workerRegistry, controlPlaneRuntime, rabbitTemplate, identity, defaults, inputRegistry, Clock.systemUTC());
  }

  GeneratorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          ControlPlaneIdentity identity,
                          GeneratorDefaults defaults,
                          WorkInputRegistry inputRegistry,
                          Clock clock) {
    Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    GeneratorDefaults generatorDefaults = Objects.requireNonNull(defaults, "defaults");
    Objects.requireNonNull(inputRegistry, "inputRegistry");
    this.clock = Objects.requireNonNull(clock, "clock");

    registry.streamByRoleAndInput("generator", WorkerInputType.SCHEDULER)
        .forEach(definition -> {
          Logger stateLogger = LoggerFactory.getLogger(definition.beanType());
          SchedulerWorkInput<GeneratorWorkerConfig> input = SchedulerWorkInput.<GeneratorWorkerConfig>builder()
              .workerDefinition(definition)
              .controlPlaneRuntime(controlRuntime)
              .workerRuntime(workerRuntime)
              .identity(controlIdentity)
              .schedulerState(SchedulerStates.ratePerSecond(
                  GeneratorWorkerConfig.class,
                  generatorDefaults::asConfig,
                  stateLogger))
              .resultHandler((result, workerDefinition) -> handleResult(workerDefinition, result))
              .dispatchErrorHandler(ex -> log.warn("Generator worker {} invocation failed", definition.beanName(), ex))
              .logger(log)
              .build();
          workInputs.add(input);
          inputRegistry.register(definition, input);
        });
  }

  @Scheduled(fixedRate = 1000)
  public void tick() {
    long now = clock.millis();
    workInputs.forEach(input -> input.tick(now));
  }

  @PostConstruct
  void onStart() {
    start();
  }

  void start() {
    workInputs.forEach(SchedulerWorkInput::start);
  }

  @PreDestroy
  void onStop() {
    stop();
  }

  void stop() {
    workInputs.forEach(SchedulerWorkInput::stop);
  }

  private void handleResult(WorkerDefinition definition, WorkResult result) {
    if (!(result instanceof WorkResult.Message messageResult)) {
      return;
    }
    Message message = messageConverter.toMessage(messageResult.value());
    String routingKey = resolveOutbound(definition);
    String exchange = resolveExchange(definition);
    rabbitTemplate.send(exchange, routingKey, message);
  }

  private String resolveOutbound(WorkerDefinition definition) {
    String out = definition.outQueue();
    if (out == null || out.isBlank()) {
      throw new IllegalStateException(
          "Generator worker " + definition.beanName() + " has no outbound queue configured");
    }
    return out;
  }

  private String resolveExchange(WorkerDefinition definition) {
    String exchange = definition.exchange();
    if (exchange == null || exchange.isBlank()) {
      throw new IllegalStateException(
          "Generator worker " + definition.beanName() + " has no exchange configured");
    }
    return exchange;
  }
}
