package io.pockethive.moderator;

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

@Component
class ModeratorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(ModeratorRuntimeAdapter.class);
  private static final String LISTENER_ID = "moderatorWorkerListener";

  private final RabbitWorkInput workInput;

  ModeratorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          RabbitListenerEndpointRegistry listenerRegistry,
                          ControlPlaneIdentity identity,
                          ModeratorDefaults defaults,
                          WorkInputRegistry inputRegistry) {
    WorkerRuntime runtime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    RabbitTemplate template = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    RabbitListenerEndpointRegistry endpointRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    ModeratorDefaults moderatorDefaults = Objects.requireNonNull(defaults, "defaults");
    WorkInputRegistry registryRef = Objects.requireNonNull(inputRegistry, "inputRegistry");

    WorkerDefinition workerDefinition = registry.findByRoleAndInput("moderator", WorkerInputType.RABBIT)
        .orElseThrow(() -> new IllegalStateException("Moderator worker definition not found"));

    this.workInput = RabbitWorkInput.builder()
        .logger(log)
        .listenerId(LISTENER_ID)
        .displayName("Moderator")
        .workerDefinition(workerDefinition)
        .controlPlaneRuntime(controlRuntime)
        .listenerRegistry(endpointRegistry)
        .identity(controlIdentity)
        .withConfigDefaults(ModeratorWorkerConfig.class, moderatorDefaults::asConfig, ModeratorWorkerConfig::enabled)
        .dispatcher(message -> runtime.dispatch(workerDefinition.beanName(), message))
        .rabbitTemplate(template)
        .dispatchErrorHandler(ex -> log.warn("Moderator worker invocation failed", ex))
        .build()
        .register(registryRef);
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = "${pockethive.control-plane.queues.generator}")
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
