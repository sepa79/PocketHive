package io.pockethive.postprocessor;

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

@Component
class PostProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(PostProcessorRuntimeAdapter.class);
  private static final String LISTENER_ID = "postProcessorWorkerListener";

  private final RabbitMessageWorkerAdapter delegate;

  PostProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                              WorkerRegistry workerRegistry,
                              WorkerControlPlaneRuntime controlPlaneRuntime,
                              RabbitTemplate rabbitTemplate,
                              RabbitListenerEndpointRegistry listenerRegistry,
                              ControlPlaneIdentity identity,
                              PostProcessorDefaults defaults) {
    WorkerRuntime runtime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    RabbitTemplate template = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    RabbitListenerEndpointRegistry endpointRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    PostProcessorDefaults postProcessorDefaults = Objects.requireNonNull(defaults, "defaults");

    WorkerDefinition workerDefinition = registry.findByRoleAndType("postprocessor", WorkerType.MESSAGE)
        .orElseThrow(() -> new IllegalStateException("Post-processor worker definition not found"));

    this.delegate = RabbitMessageWorkerAdapter.builder()
        .logger(log)
        .listenerId(LISTENER_ID)
        .displayName("Post-processor")
        .workerDefinition(workerDefinition)
        .controlPlaneRuntime(controlRuntime)
        .listenerRegistry(endpointRegistry)
        .identity(controlIdentity)
        .defaultEnabledSupplier(() -> postProcessorDefaults.asConfig().enabled())
        .defaultConfigSupplier(postProcessorDefaults::asConfig)
        .desiredStateResolver(snapshot -> snapshot.enabled().orElseGet(() -> snapshot.config(PostProcessorWorkerConfig.class)
            .map(PostProcessorWorkerConfig::enabled)
            .orElse(postProcessorDefaults.asConfig().enabled())))
        .dispatcher(message -> runtime.dispatch(workerDefinition.beanName(), message))
        .rabbitTemplate(template)
        .dispatchErrorHandler(ex -> log.warn("Post-processor worker invocation failed", ex))
        .build();
  }

  @PostConstruct
  void initialiseStateListener() {
    delegate.initialiseStateListener();
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = "${pockethive.control-plane.queues.final:" + TopologyDefaults.FINAL_QUEUE + "}")
  public void onWork(Message message) {
    delegate.onWork(message);
  }

  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    delegate.emitStatusDelta();
  }

  @RabbitListener(queues = "#{@postProcessorControlQueueName}")
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
