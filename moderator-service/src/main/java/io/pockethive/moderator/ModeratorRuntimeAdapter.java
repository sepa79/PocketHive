package io.pockethive.moderator;

import com.rabbitmq.client.Channel;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.moderator.shaper.config.PatternConfigValidator;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitMessageWorkerAdapter;
import jakarta.annotation.PostConstruct;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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

  private final RabbitMessageWorkerAdapter delegate;

  ModeratorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          RabbitListenerEndpointRegistry listenerRegistry,
                          ControlPlaneIdentity identity,
                          ModeratorDefaults defaults,
                          PatternConfigValidator validator) {
    WorkerRuntime runtime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    RabbitTemplate template = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    RabbitListenerEndpointRegistry endpointRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    ModeratorDefaults moderatorDefaults = Objects.requireNonNull(defaults, "defaults");
    PatternConfigValidator patternValidator = Objects.requireNonNull(validator, "patternValidator");

    WorkerDefinition workerDefinition = registry.findByRoleAndType("moderator", WorkerType.MESSAGE)
        .orElseThrow(() -> new IllegalStateException("Moderator worker definition not found"));

    Supplier<ModeratorWorkerConfig> defaultsSupplier =
        () -> patternValidator.validate(moderatorDefaults.asConfig());

    this.delegate = RabbitMessageWorkerAdapter.builder()
        .logger(log)
        .listenerId(LISTENER_ID)
        .displayName("Moderator")
        .workerDefinition(workerDefinition)
        .controlPlaneRuntime(controlRuntime)
        .listenerRegistry(endpointRegistry)
        .identity(controlIdentity)
        .withConfigDefaults(ModeratorWorkerConfig.class,
            defaultsSupplier,
            config -> patternValidator.validate(config).enabled())
        .desiredStateResolver(snapshot -> {
          Optional<ModeratorWorkerConfig> typedConfig =
              snapshot.config(ModeratorWorkerConfig.class).map(patternValidator::validate);
          String baseRate = typedConfig
              .map(cfg -> cfg.pattern().baseRateRps().toPlainString())
              .orElse("n/a");
          String stepCount = typedConfig
              .map(cfg -> Integer.toString(cfg.pattern().steps().size()))
              .orElse("n/a");
          log.info(
              "Moderator control snapshot received: enabled={}, baseRateRps={}, stepCount={}, rawKeys={}",
              snapshot.enabled().orElse(null),
              baseRate,
              stepCount,
              snapshot.rawConfig().keySet());
          return snapshot.enabled()
              .orElseGet(() -> typedConfig
                  .map(ModeratorWorkerConfig::enabled)
                  .orElseGet(() -> defaultsSupplier.get().enabled()));
        })
        .dispatcher(message -> runtime.dispatch(workerDefinition.beanName(), message))
        .manualAckStrategy((amqpMessage, channelRef, deliveryTag, acknowledgment, workerCall) -> {
          boolean processed = workerCall.getAsBoolean();
          if (!processed) {
            return;
          }
          try {
            long tag = deliveryTag;
            if (tag < 0L) {
              log.warn("Cannot acknowledge moderator message; delivery tag missing");
              return;
            }
            if (acknowledgeWithHandle(acknowledgment)) {
              return;
            }
            if (channelRef == null) {
              log.warn("Cannot acknowledge moderator message; channel handle missing");
              return;
            }
            channelRef.basicAck(tag, false);
          } catch (Exception ex) {
            throw new IllegalStateException("Failed to acknowledge moderator message", ex);
          }
        })
        .rabbitTemplate(template)
        .dispatchErrorHandler(ex -> log.warn("Moderator worker invocation failed", ex))
        .build();
  }

  @PostConstruct
  void initialiseStateListener() {
    delegate.initialiseStateListener();
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = "${pockethive.control-plane.queues.generator}",
      containerFactory = "moderatorManualAckListenerContainerFactory")
  public void onWork(Message message, Channel channel) {
    delegate.onWork(message, channel);
  }

  void onWork(Message message, Channel channel, Object acknowledgment) {
    delegate.onWork(message, channel, acknowledgment);
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    delegate.onApplicationEvent(event);
  }

  private static boolean acknowledgeWithHandle(Object acknowledgment) {
    if (acknowledgment == null) {
      return false;
    }
    try {
      acknowledgment.getClass().getMethod("acknowledge").invoke(acknowledgment);
      return true;
    } catch (NoSuchMethodException | IllegalAccessException ignored) {
      return false;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to acknowledge moderator message via handle", ex);
    }
  }
}
