package io.pockethive.examples.starter.processor;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitMessageWorkerAdapter;
import jakarta.annotation.PostConstruct;
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
 * Wires the RabbitMQ adapter to the sample processor worker definition.
 */
@Component
class ProcessorWorkerRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(ProcessorWorkerRuntimeAdapter.class);
  private static final String LISTENER_ID = "sampleProcessorListener";
  private final RabbitMessageWorkerAdapter delegate;

  ProcessorWorkerRuntimeAdapter(WorkerRuntime workerRuntime,
                                WorkerRegistry workerRegistry,
                                WorkerControlPlaneRuntime controlPlaneRuntime,
                                RabbitTemplate rabbitTemplate,
                                RabbitListenerEndpointRegistry listenerRegistry,
                                ControlPlaneIdentity identity) {

    WorkerDefinition definition = workerRegistry
        .findByRoleAndType("processor", WorkerType.MESSAGE)
        .orElseThrow();

    this.delegate = RabbitMessageWorkerAdapter.builder()
        .logger(log)
        .displayName("Sample Processor")
        .listenerId(LISTENER_ID)
        .workerDefinition(definition)
        .controlPlaneRuntime(controlPlaneRuntime)
        .listenerRegistry(listenerRegistry)
        .identity(identity)
        .defaultEnabledSupplier(() -> true)
        .desiredStateResolver(snapshot -> snapshot.enabled().orElse(true))
        .dispatcher(message -> workerRuntime.dispatch(definition.beanName(), message))
        .messageResultPublisher((result, outbound) -> {
          String routingKey = Optional.ofNullable(definition.outQueue()).orElse("ph.processor.out");
          rabbitTemplate.send("ph.exchange", routingKey, outbound);
        })
        .build();
  }

  @PostConstruct
  void initialise() {
    delegate.initialiseStateListener();
  }

  @RabbitListener(id = LISTENER_ID, queues = "${ph.processor.work.queue:ph.processor.in}")
  public void onWork(Message message) {
    delegate.onWork(message);
  }

  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    delegate.emitStatusDelta();
  }

  @RabbitListener(queues = "${ph.processor.control.queue:ph.processor.control}")
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
