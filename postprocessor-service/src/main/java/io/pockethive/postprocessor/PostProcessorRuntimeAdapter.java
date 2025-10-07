package io.pockethive.postprocessor;

import io.pockethive.TopologyDefaults;
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
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PostProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(PostProcessorRuntimeAdapter.class);
  private static final String LISTENER_ID = "postProcessorWorkerListener";

  private final WorkerRuntime workerRuntime;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final RabbitListenerEndpointRegistry listenerRegistry;
  private final ControlPlaneIdentity identity;
  private final PostProcessorDefaults defaults;
  private final RabbitWorkMessageConverter messageConverter = new RabbitWorkMessageConverter();
  private final WorkerDefinition workerDefinition;
  private volatile boolean desiredEnabled;

  PostProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                              WorkerRegistry workerRegistry,
                              WorkerControlPlaneRuntime controlPlaneRuntime,
                              RabbitListenerEndpointRegistry listenerRegistry,
                              ControlPlaneIdentity identity,
                              PostProcessorDefaults defaults) {
    this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    this.listenerRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.workerDefinition = workerRegistry.findByRoleAndType("postprocessor", WorkerType.MESSAGE)
        .orElseThrow(() -> new IllegalStateException("Post-processor worker definition not found"));
  }

  @PostConstruct
  void initialiseStateListener() {
    desiredEnabled = defaults.asConfig().enabled();
    controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
      boolean enabled = snapshot.enabled().orElseGet(() -> snapshot.config(PostProcessorWorkerConfig.class)
          .map(PostProcessorWorkerConfig::enabled)
          .orElse(defaults.asConfig().enabled()));
      toggleListener(enabled);
    });
    applyListenerState();
    controlPlaneRuntime.emitStatusSnapshot();
  }

  @RabbitListener(id = LISTENER_ID, queues = "${ph.finalQueue:" + TopologyDefaults.FINAL_QUEUE + "}")
  public void onWork(Message message) {
    WorkMessage workMessage = messageConverter.fromMessage(message);
    try {
      WorkResult result = workerRuntime.dispatch(workerDefinition.beanName(), workMessage);
      publishResult(result);
    } catch (Exception ex) {
      log.warn("Post-processor worker invocation failed", ex);
    }
  }

  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    controlPlaneRuntime.emitStatusDelta();
  }

  @RabbitListener(queues = "#{@postProcessorControlQueueName}")
  public void onControl(String payload,
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

  private void publishResult(WorkResult result) {
    if (result instanceof WorkResult.Message messageResult) {
      log.debug("Dropping unexpected outbound message from post-processor worker: {} bytes", messageResult.value().body().length);
    }
  }

  private void toggleListener(boolean enabled) {
    this.desiredEnabled = enabled;
    applyListenerState();
  }

  private void applyListenerState() {
    var container = listenerRegistry.getListenerContainer(LISTENER_ID);
    if (container == null) {
      log.debug("Post-processor listener container not yet available; desiredEnabled={} (instance={})", desiredEnabled, identity.instanceId());
      return;
    }
    if (desiredEnabled && !container.isRunning()) {
      container.start();
      log.info("Post-processor work listener started (instance={})", identity.instanceId());
    } else if (!desiredEnabled && container.isRunning()) {
      container.stop();
      log.info("Post-processor work listener stopped (instance={})", identity.instanceId());
    }
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    applyListenerState();
  }
}
