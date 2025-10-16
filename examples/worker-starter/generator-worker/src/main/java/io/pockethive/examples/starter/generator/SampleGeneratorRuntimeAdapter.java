package io.pockethive.examples.starter.generator;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules generator invocations and publishes the resulting messages to RabbitMQ.
 */
@Component
class SampleGeneratorRuntimeAdapter {

  private static final Logger log = LoggerFactory.getLogger(SampleGeneratorRuntimeAdapter.class);

  private final WorkerRuntime workerRuntime;
  private final WorkerDefinition definition;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final RabbitTemplate rabbitTemplate;
  private final ControlPlaneIdentity identity;
  private final SampleGeneratorDefaults defaults;
  private final RabbitWorkMessageConverter messageConverter = new RabbitWorkMessageConverter();
  private final AtomicReference<SampleGeneratorConfig> state = new AtomicReference<>();
  private double carryOver;

  SampleGeneratorRuntimeAdapter(WorkerRuntime workerRuntime,
                                WorkerRegistry workerRegistry,
                                WorkerControlPlaneRuntime controlPlaneRuntime,
                                RabbitTemplate rabbitTemplate,
                                ControlPlaneIdentity identity,
                                SampleGeneratorDefaults defaults) {
    this.workerRuntime = workerRuntime;
    this.controlPlaneRuntime = controlPlaneRuntime;
    this.rabbitTemplate = rabbitTemplate;
    this.identity = identity;
    this.defaults = defaults;
    this.definition = workerRegistry
        .findByRoleAndType("generator", WorkerType.GENERATOR)
        .orElseThrow();
    this.state.set(defaults.asConfig());
    controlPlaneRuntime.registerStateListener(definition.beanName(), snapshot -> {
      SampleGeneratorConfig incoming = snapshot.config(SampleGeneratorConfig.class)
          .orElseGet(defaults::asConfig);
      boolean enabled = snapshot.enabled().orElse(incoming.enabled());
      state.set(new SampleGeneratorConfig(enabled, incoming.ratePerSecond(), incoming.message()));
    });
  }

  @PostConstruct
  void emitInitialStatus() {
    controlPlaneRuntime.emitStatusSnapshot();
  }

  @Scheduled(fixedRate = 1000)
  void tick() {
    SampleGeneratorConfig config = state.get();
    if (config == null || !config.enabled()) {
      return;
    }
    int quota = nextQuota(config.ratePerSecond());
    for (int i = 0; i < quota; i++) {
      dispatch();
    }
  }

  private synchronized int nextQuota(double ratePerSecond) {
    carryOver += Math.max(0.0, ratePerSecond);
    int whole = (int) Math.floor(carryOver);
    carryOver -= whole;
    return whole;
  }

  private void dispatch() {
    try {
      WorkMessage seed = WorkMessage.builder()
          .header("swarmId", identity.swarmId())
          .header("instanceId", identity.instanceId())
          .build();
      WorkResult result = workerRuntime.dispatch(definition.beanName(), seed);
      if (result instanceof WorkResult.Message messageResult) {
        publish(messageResult.value());
      }
    } catch (Exception ex) {
      log.warn("Generator invocation failed", ex);
    }
  }

  private void publish(WorkMessage message) {
    String routingKey = Optional.ofNullable(definition.resolvedOutQueue()).orElse(Topology.GEN_QUEUE);
    rabbitTemplate.send(Topology.EXCHANGE, routingKey, messageConverter.toMessage(message));
  }

  @Scheduled(fixedRate = 5000)
  void emitStatusDelta() {
    controlPlaneRuntime.emitStatusDelta();
  }

  @RabbitListener(queues = "${ph.generator.control.queue:ph.generator.control}")
  void onControl(String payload,
                 @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                 @Header(value = ObservabilityContextUtil.HEADER, required = false) String traceHeader) {
    ObservabilityContext context = ObservabilityContextUtil.fromHeader(traceHeader);
    ObservabilityContextUtil.populateMdc(context);
    try {
      if (routingKey == null || routingKey.isBlank()) {
        throw new IllegalArgumentException("Control routing key must not be null or blank");
      }
      if (payload == null || payload.isBlank()) {
        throw new IllegalArgumentException("Control payload must not be null or blank");
      }
      boolean handled = controlPlaneRuntime.handle(payload, routingKey);
      if (!handled) {
        log.debug("Ignoring control-plane payload on routing key {}", routingKey);
      }
    } finally {
      MDC.clear();
    }
  }
}
