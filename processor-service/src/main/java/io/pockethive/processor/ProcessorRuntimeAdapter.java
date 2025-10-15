package io.pockethive.processor;

import io.pockethive.Topology;
import io.pockethive.TopologyDefaults;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitMessageWorkerAdapter;
import jakarta.annotation.PostConstruct;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Bridges the Spring Boot runtime with the PocketHive worker SDK for the processor service while delegating
 * shared plumbing to {@link RabbitMessageWorkerAdapter}. Role-specific concerns such as outbound routing remain in
 * this adapter.
 */
@Component
class ProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(ProcessorRuntimeAdapter.class);
  private static final String LISTENER_ID = "processorWorkerListener";

  private final RabbitMessageWorkerAdapter delegate;

  ProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          RabbitListenerEndpointRegistry listenerRegistry,
                          ControlPlaneIdentity identity,
                          ProcessorDefaults defaults) {
    WorkerRuntime runtime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    RabbitTemplate template = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    RabbitListenerEndpointRegistry endpointRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    ProcessorDefaults processorDefaults = Objects.requireNonNull(defaults, "defaults");

    WorkerDefinition workerDefinition = registry.findByRoleAndType("processor", WorkerType.MESSAGE)
        .orElseThrow(() -> new IllegalStateException("Processor worker definition not found"));

    this.delegate = RabbitMessageWorkerAdapter.builder()
        .logger(log)
        .listenerId(LISTENER_ID)
        .displayName("Processor")
        .workerDefinition(workerDefinition)
        .controlPlaneRuntime(controlRuntime)
        .listenerRegistry(endpointRegistry)
        .identity(controlIdentity)
        .defaultEnabledSupplier(() -> processorDefaults.asConfig().enabled())
        .defaultConfigSupplier(processorDefaults::asConfig)
        .desiredStateResolver(snapshot -> snapshot.enabled().orElseGet(() -> snapshot.config(ProcessorWorkerConfig.class)
            .map(ProcessorWorkerConfig::enabled)
            .orElse(processorDefaults.asConfig().enabled())))
        .dispatcher(message -> runtime.dispatch(workerDefinition.beanName(), message))
        .messageResultPublisher((result, outbound) -> {
          String routingKey = Optional.ofNullable(workerDefinition.resolvedOutQueue()).orElse(Topology.FINAL_QUEUE);
          template.send(Topology.EXCHANGE, routingKey, outbound);
        })
        .dispatchErrorHandler(ex -> log.warn("Processor worker invocation failed", ex))
        .build();
  }

  @PostConstruct
  void initialiseStateListener() {
    delegate.initialiseStateListener();
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = "${pockethive.control-plane.queues.moderator:" + TopologyDefaults.MOD_QUEUE + "}")
  public void onWork(Message message) {
    delegate.onWork(message);
  }

  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    delegate.emitStatusDelta();
  }

  @RabbitListener(queues = "#{@processorControlQueueName}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String traceHeader) {
    delegate.onControl(payload, routingKey, traceHeader);
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    delegate.onApplicationEvent(event);
  }
}
