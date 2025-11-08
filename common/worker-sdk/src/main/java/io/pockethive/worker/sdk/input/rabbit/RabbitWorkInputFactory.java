package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import io.pockethive.worker.sdk.config.RabbitInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.core.Ordered;

/**
 * Creates {@link RabbitWorkInput} instances for {@link WorkerInputType#RABBIT} workers.
 */
public final class RabbitWorkInputFactory implements WorkInputFactory, Ordered {

    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final List<PocketHiveWorkerProperties<?>> workerProperties;

    public RabbitWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity,
        RabbitTemplate rabbitTemplate,
        RabbitListenerEndpointRegistry listenerRegistry,
        List<PocketHiveWorkerProperties<?>> workerProperties
    ) {
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rabbitTemplate = rabbitTemplate;
        this.listenerRegistry = listenerRegistry;
        this.workerProperties = workerProperties == null ? List.of() : List.copyOf(workerProperties);
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.input() == WorkerInputType.RABBIT
            && rabbitTemplate != null
            && listenerRegistry != null;
    }

    @Override
    public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        Logger logger = LoggerFactory.getLogger(definition.beanType());
        WorkerDefaults defaults = resolveDefaults(definition);
        RabbitInputProperties rabbitConfig = config instanceof RabbitInputProperties props
            ? props
            : new RabbitInputProperties();
        return RabbitWorkInput.builder()
            .logger(logger)
            .listenerId(definition.beanName() + "Listener")
            .displayName(definition.beanName())
            .workerDefinition(definition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .listenerRegistry(listenerRegistry)
            .identity(identity)
            .defaultEnabledSupplier(rabbitConfig::isEnabled)
            .defaultConfigSupplier(() -> defaults.rawConfig().isEmpty() ? null : defaults.rawConfig())
            .desiredStateResolver(snapshot -> snapshot.enabled().orElse(rabbitConfig.isEnabled()))
            .rabbitTemplate(rabbitTemplate)
            .dispatcher(message -> workerRuntime.dispatch(definition.beanName(), message))
            .messageResultPublisher((result, outbound) -> { })
            .dispatchErrorHandler(ex -> logger.warn("Rabbit worker {} invocation failed", definition.beanName(), ex))
            .build();
    }

    private WorkerDefaults resolveDefaults(WorkerDefinition definition) {
        Optional<PocketHiveWorkerProperties<?>> match = workerProperties.stream()
            .filter(props -> props.role().equalsIgnoreCase(definition.role()))
            .findFirst();
        Map<String, Object> config = match.map(PocketHiveWorkerProperties::rawConfig).orElse(Map.of());
        return new WorkerDefaults(config);
    }

    private record WorkerDefaults(Map<String, Object> rawConfig) {
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
