package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitListenerState;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Responsibility: apply desired Work listener state and delegate processing without changing callback-return behavior.
 * Must not: publish worker results, configure broker clients or define adapter settings.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitMessageWorkerAdapter implements ApplicationListener<ContextRefreshedEvent> {

    private final Logger log;
    private final String listenerId;
    private final String displayName;
    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final RabbitListeners listenerRegistry;
    private final io.pockethive.controlplane.ControlPlaneIdentity identity;
    private final Function<WorkerStateSnapshot, Boolean> desiredStateResolver;
    private final RabbitWorkExecution execution;
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private volatile boolean desiredEnabled;

    RabbitMessageWorkerAdapter(RabbitMessageWorkerAdapterBuilder builder) {
        this.log = builder.log;
        this.listenerId = builder.listenerId;
        this.displayName = builder.displayName;
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.listenerRegistry = builder.listenerRegistry;
        this.identity = builder.identity;
        this.desiredStateResolver = builder.desiredStateResolver;
        this.execution = new RabbitWorkExecution(builder);
    }

    /**
     * Creates a new {@link RabbitMessageWorkerAdapterBuilder} instance used to construct the adapter.
     *
     * @return a fresh builder pre-configured for {@link RabbitMessageWorkerAdapter}
     */
    public static RabbitMessageWorkerAdapterBuilder builder() {
        return new RabbitMessageWorkerAdapterBuilder();
    }

    /**
     * Registers the control-plane listener and applies the explicit control-plane enabled state.
     * <p>
     * This method should be invoked during service initialisation (typically in a {@code @PostConstruct}
     * method) so that the helper can record the desired state communicated by the control plane. The
     * listener state is immediately applied and a status snapshot emitted to ensure upstream components
     * observe the current worker availability.
     */
    public void initialiseStateListener() {
        if (!initialised.compareAndSet(false, true)) {
            return;
        }
        desiredEnabled = false;
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            updateConcurrency(snapshot);
            boolean enabled = Optional.ofNullable(desiredStateResolver.apply(snapshot)).orElse(desiredEnabled);
            toggleListener(enabled);
        });
        applyListenerState();
        controlPlaneRuntime.emitStatusSnapshot();
    }
    /**
     * Starts the underlying listener container based on the latest desired state.
     */
    public void startListener() {
        toggleListener(true);
    }

    /**
     * Stops the underlying listener container.
     */
    public void stopListener() {
        toggleListener(false);
    }

    public void onWork(RabbitMessage message) {
        execution.onWork(message);
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        applyListenerState();
    }

    private void updateConcurrency(WorkerStateSnapshot snapshot) {
        execution.setMaxInFlight(snapshot.config(MaxInFlightConfig.class)
            .map(MaxInFlightConfig::maxInFlight).orElse(1));
    }

    private void toggleListener(boolean enabled) {
        boolean previous = this.desiredEnabled;
        this.desiredEnabled = enabled;
        if (previous != enabled && log.isInfoEnabled()) {
            log.info(
                "{} work lifecycle {} (instance={})",
                displayName,
                enabled ? "enabled" : "disabled",
                identity.instanceId());
        }
        applyListenerState();
    }

    /**
     * Applies the desired listener state to the Rabbit subscription if it is
     * already available. The container may not yet be registered when the application context is starting,
     * in which case the helper logs the desired state and waits for a subsequent refresh event.
     */
    private void applyListenerState() {
        RabbitListenerState state = listenerRegistry.state(listenerId);
        if (state == RabbitListenerState.NOT_REGISTERED) {
            log.debug("{} listener not yet registered; desiredEnabled={}", displayName, desiredEnabled);
            return;
        }
        if (desiredEnabled && state != RabbitListenerState.RUNNING) {
            listenerRegistry.start(listenerId);
        } else if (!desiredEnabled && state == RabbitListenerState.RUNNING) {
            listenerRegistry.stop(listenerId);
        }
    }

}
