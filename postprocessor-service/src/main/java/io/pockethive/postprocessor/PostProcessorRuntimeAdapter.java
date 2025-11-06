package io.pockethive.postprocessor;

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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

@Component
class PostProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(PostProcessorRuntimeAdapter.class);
  private static final String LISTENER_ID = "postProcessorWorkerListener";

  private final RabbitWorkInput workInput;

  PostProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                              WorkerRegistry workerRegistry,
                              WorkerControlPlaneRuntime controlPlaneRuntime,
                              RabbitListenerEndpointRegistry listenerRegistry,
                              ControlPlaneIdentity identity,
                              PostProcessorDefaults defaults,
                              WorkInputRegistry inputRegistry) {
    WorkerRuntime runtime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    RabbitListenerEndpointRegistry endpointRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    PostProcessorDefaults postProcessorDefaults = Objects.requireNonNull(defaults, "defaults");
    WorkInputRegistry registryRef = Objects.requireNonNull(inputRegistry, "inputRegistry");

    WorkerDefinition workerDefinition = registry.findByRoleAndInput("postprocessor", WorkerInputType.RABBIT)
        .orElseThrow(() -> new IllegalStateException("Post-processor worker definition not found"));
    this.workInput = RabbitWorkInput.builder()
        .logger(log)
        .listenerId(LISTENER_ID)
        .displayName("Post-processor")
        .workerDefinition(workerDefinition)
        .controlPlaneRuntime(controlRuntime)
        .listenerRegistry(endpointRegistry)
        .identity(controlIdentity)
        .withConfigDefaults(PostProcessorWorkerConfig.class, postProcessorDefaults::asConfig, PostProcessorWorkerConfig::enabled)
        .dispatcher(message -> runtime.dispatch(workerDefinition.beanName(), message))
        .dispatchErrorHandler(ex -> log.warn("Post-processor worker invocation failed", ex))
        .build()
        .register(registryRef);
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = "${pockethive.control-plane.queues.final}")
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
