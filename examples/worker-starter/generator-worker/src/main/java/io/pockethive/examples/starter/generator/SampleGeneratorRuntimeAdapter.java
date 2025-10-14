package io.pockethive.examples.starter.generator;

import io.pockethive.controlplane.ControlPlaneIdentity;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    String routingKey = Optional.ofNullable(definition.outQueue()).orElse("ph.generator.out");
    rabbitTemplate.send("ph.exchange", routingKey, messageConverter.toMessage(message));
  }
}
