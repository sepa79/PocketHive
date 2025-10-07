package io.pockethive.processor;

import io.pockethive.Topology;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bridges the Spring Boot runtime with the PocketHive worker SDK for the processor service.
 * <p>
 * The adapter's lifecycle consists of three main responsibilities:
 * <ol>
 *   <li><strong>State listener registration</strong> – During {@link #initialiseStateListener()} we
 *       subscribe to the control plane so feature flags (for example a payload on
 *       {@code processor.control.toggle}) can enable or disable the Rabbit listener at runtime. The
 *       default desired state is read from {@link ProcessorDefaults}.</li>
 *   <li><strong>Work dispatch</strong> – {@link #onWork(Message)} is bound to the moderator queue via
 *       {@link RabbitListener}. Incoming AMQP messages are converted into {@link WorkMessage}
 *       instances and dispatched through {@link WorkerRuntime#dispatch(String, WorkMessage)}. When
 *       a {@link WorkResult#message(WorkMessage) message result} is returned we forward it to the
 *       final queue inside {@link #publishResult(WorkResult)}.</li>
 *   <li><strong>Control-plane handling</strong> – {@link #onControl(String, String, String)} listens to
 *       control topics (for example {@code processor.control.config}) so operators can push config
 *       updates or request snapshots. Observability headers are preserved using
 *       {@link ObservabilityContextUtil} to keep traces intact.</li>
 * </ol>
 * <p>
 * Users can use this adapter as a blueprint for new workers: wire in your
 * {@link WorkerDefinition} via the {@link WorkerRegistry}, bind listeners to your service-specific
 * queues, and reuse {@link #toggleListener(boolean)} to guard your Rabbit containers with
 * control-plane state. Override {@link #publishResult(WorkResult)} if your worker emits non-message
 * results (e.g., acknowledgements only).
 */
@Component
class ProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(ProcessorRuntimeAdapter.class);
  private static final String LISTENER_ID = "processorWorkerListener";

  private final WorkerRuntime workerRuntime;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final RabbitTemplate rabbitTemplate;
  private final RabbitListenerEndpointRegistry listenerRegistry;
  private final ControlPlaneIdentity identity;
  private final ProcessorDefaults defaults;
  private final RabbitWorkMessageConverter messageConverter = new RabbitWorkMessageConverter();
  private final WorkerDefinition workerDefinition;
  private volatile boolean desiredEnabled;

  /**
   * Creates the runtime adapter with all infrastructure dependencies needed to dispatch work.
   *
   * @param workerRuntime worker engine that invokes {@code processorWorker}
   * @param workerRegistry registry used to resolve the {@link WorkerDefinition} metadata such as
   *                       queue bindings and bean names
   * @param controlPlaneRuntime facade used to register state listeners and emit status snapshots
   * @param rabbitTemplate template for publishing enriched {@link WorkMessage} instances back to
   *                       RabbitMQ
   * @param listenerRegistry Spring registry used to locate and control the listener container for
   *                         {@link #LISTENER_ID}
   * @param identity identifies the running instance (swarm, instance id) for logging and metrics
   * @param defaults fallback configuration for the processor worker (base URL and enable flag)
   */
  ProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          RabbitListenerEndpointRegistry listenerRegistry,
                          ControlPlaneIdentity identity,
                          ProcessorDefaults defaults) {
    this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    this.listenerRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.workerDefinition = workerRegistry.findByRoleAndType("processor", WorkerType.MESSAGE)
        .orElseThrow(() -> new IllegalStateException("Processor worker definition not found"));
  }

  /**
   * Registers the control-plane listener after Spring constructs the adapter.
   * <p>
   * We capture the default {@link ProcessorWorkerConfig#enabled()} value, subscribe to
   * {@link WorkerControlPlaneRuntime#registerStateListener(String, java.util.function.Consumer)},
   * and immediately apply the desired state. This ensures that messages such as
   * <pre>{@code {
   *   "enabled": false
   * }}</pre>
   * sent on {@code processor.control.config} take effect without restarts.
   */
  @PostConstruct
  void initialiseStateListener() {
    desiredEnabled = defaults.asConfig().enabled();
    controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
      boolean enabled = snapshot.enabled().orElseGet(() -> snapshot.config(ProcessorWorkerConfig.class)
          .map(ProcessorWorkerConfig::enabled).orElse(defaults.asConfig().enabled()));
      toggleListener(enabled);
    });
    applyListenerState();
    controlPlaneRuntime.emitStatusSnapshot();
  }

  /**
   * Handles moderator queue messages and delegates them to the registered worker bean.
   *
   * @param message AMQP payload (converted to {@link WorkMessage}) received on the moderator queue
   */
  @RabbitListener(id = LISTENER_ID, queues = "${ph.modQueue:" + TopologyDefaults.MOD_QUEUE + "}")
  public void onWork(Message message) {
    WorkMessage workMessage = messageConverter.fromMessage(message);
    try {
      WorkResult result = workerRuntime.dispatch(workerDefinition.beanName(), workMessage);
      publishResult(result);
    } catch (Exception ex) {
      log.warn("Processor worker invocation failed", ex);
    }
  }

  /**
   * Periodically forwards worker status deltas to the control plane (every 5 seconds by default).
   * Beginners can tweak the schedule with {@code ph.processor.statusIntervalMs} if the service
   * needs faster or slower updates.
   */
  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    controlPlaneRuntime.emitStatusDelta();
  }

  /**
   * Consumes control-plane messages used to toggle the worker or push configuration.
   * <p>
   * Example routing keys include {@code processor.control.config} (config snapshots) and
   * {@code processor.control.toggle} (explicit enable/disable). The payload is forwarded to
   * {@link WorkerControlPlaneRuntime#handle(String, String)} which returns {@code true} when a
   * handler is registered for the routing key. Observability context headers are propagated so
   * dashboards can correlate operator actions.
   *
   * @param payload JSON control message (for example {@code {"baseUrl":"https://mock"}})
   * @param routingKey AMQP routing key derived from the control exchange
   * @param traceHeader optional observability header forwarded from orchestrator tooling
   */
  @RabbitListener(queues = "#{@processorControlQueueName}")
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

  /**
   * Publishes successful worker results to the final queue.
   * <p>
   * Only {@link WorkResult.Message} instances are forwarded; other result types (ack/nack) are
   * ignored because the worker runtime already acknowledged them. Override this method if your
   * worker emits other types that must be bridged to Rabbit.
   */
  private void publishResult(WorkResult result) {
    if (!(result instanceof WorkResult.Message messageResult)) {
      return;
    }
    Message outbound = messageConverter.toMessage(messageResult.value());
    String routingKey = Optional.ofNullable(workerDefinition.outQueue()).orElse(Topology.FINAL_QUEUE);
    rabbitTemplate.send(Topology.EXCHANGE, routingKey, outbound);
  }

  /**
   * Updates the desired state of the Rabbit listener and applies it immediately.
   *
   * @param enabled {@code true} to start consuming from the moderator queue, {@code false} to stop
   */
  private void toggleListener(boolean enabled) {
    this.desiredEnabled = enabled;
    applyListenerState();
  }

  /**
   * Starts or stops the Rabbit listener container so the runtime matches {@link #desiredEnabled}.
   * <p>
   * This method is idempotent and can safely be called during context refreshes or control-plane
   * updates. When the listener container is not yet registered we log the desired state for
   * troubleshooting; subsequent invocations (triggered by
   * {@link ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)})
   * will retry.
   */
  private void applyListenerState() {
    var container = listenerRegistry.getListenerContainer(LISTENER_ID);
    if (container == null) {
      log.debug("Processor listener container not yet available; desiredEnabled={} (instance={})", desiredEnabled, identity.instanceId());
      return;
    }
    if (desiredEnabled && !container.isRunning()) {
      container.start();
      log.info("Processor work listener started (instance={})", identity.instanceId());
    } else if (!desiredEnabled && container.isRunning()) {
      container.stop();
      log.info("Processor work listener stopped (instance={})", identity.instanceId());
    }
  }

  /**
   * Re-applies listener state when the Spring context is refreshed (e.g., after the container is
   * fully initialised). This acts as a safety net in case {@link #applyListenerState()} ran before
   * the listener container became available.
   */
  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    applyListenerState();
  }
}
