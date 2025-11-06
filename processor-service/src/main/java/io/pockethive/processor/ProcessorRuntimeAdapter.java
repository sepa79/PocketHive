package io.pockethive.processor;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.rabbit.RabbitWorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

/**
 * Bridges the Spring Boot runtime with the PocketHive worker SDK for the processor service while delegating
 * shared plumbing and outbound routing to {@link RabbitWorkInput}.
 */
@Component
class ProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(ProcessorRuntimeAdapter.class);
  private static final String LISTENER_ID = "processorWorkerListener";

  private final RabbitWorkInput workInput;

  ProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          RabbitListenerEndpointRegistry listenerRegistry,
                          ControlPlaneIdentity identity,
                          ProcessorDefaults defaults,
                          WorkInputRegistry inputRegistry) {
    WorkerRuntime runtime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    RabbitTemplate template = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    RabbitListenerEndpointRegistry endpointRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    ProcessorDefaults processorDefaults = Objects.requireNonNull(defaults, "defaults");
    WorkInputRegistry registryRef = Objects.requireNonNull(inputRegistry, "inputRegistry");

    WorkerDefinition workerDefinition = registry.findByRoleAndInput("processor", WorkerInputType.RABBIT)
        .orElseThrow(() -> new IllegalStateException("Processor worker definition not found"));
    this.workInput = RabbitWorkInput.builder()
        .logger(log)
        .listenerId(LISTENER_ID)
        .displayName("Processor")
        .workerDefinition(workerDefinition)
        .controlPlaneRuntime(controlRuntime)
        .listenerRegistry(endpointRegistry)
        .identity(controlIdentity)
        .withConfigDefaults(ProcessorWorkerConfig.class, processorDefaults::asConfig, ProcessorWorkerConfig::enabled)
        .dispatcher(message -> runtime.dispatch(workerDefinition.beanName(), message))
        .rabbitTemplate(template)
        .dispatchErrorHandler(ex -> log.warn("Processor worker invocation failed", ex))
        .build()
        .register(registryRef);
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = "${pockethive.control-plane.queues.moderator}")
  public void onWork(Message message) {
    workInput.onWork(message);
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    workInput.onApplicationEvent(event);
  }

  @PostConstruct
  void onStart() {
    start();
  }

  void start() {
    workInput.start();
  }

  @PreDestroy
  void onStop() {
    stop();
  }

  void stop() {
    workInput.stop();
  }
}
