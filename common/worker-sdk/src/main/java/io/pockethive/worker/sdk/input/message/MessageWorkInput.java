package io.pockethive.worker.sdk.input.message;

import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputChannelState;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Responsibility: serialize desired Work listener effects while allowing disable to pause admission during transport start.
 * Must not: publish worker results, configure broker clients or define adapter settings.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public final class MessageWorkInput implements WorkInput, ApplicationListener<ContextRefreshedEvent> {

    private final Logger log;
    private final String displayName;
    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkInputChannel channel;
    private final io.pockethive.controlplane.ControlPlaneIdentity identity;
    private final MessageWorkExecution execution;
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private final Object desiredStateLock = new Object();
    private boolean desiredEnabled;
    private boolean closed;

    MessageWorkInput(MessageWorkInputBuilder builder) {
        this.log = builder.log;
        this.displayName = builder.displayName;
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.channel = builder.channel;
        this.identity = builder.identity;
        this.execution = new MessageWorkExecution(builder);
        channel.register(execution);
    }

    /**
     * Creates a new {@link MessageWorkInputBuilder} instance used to construct the adapter.
     *
     * @return a fresh builder pre-configured for {@link MessageWorkInput}
     */
    public static MessageWorkInputBuilder builder() {
        return new MessageWorkInputBuilder();
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
        updateDesiredState(false);
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            updateConcurrency(snapshot);
            toggleListener(snapshot.enabled());
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

    private boolean running;

    @Override public synchronized void start() {
        synchronized (desiredStateLock) {
            if (closed) throw new IllegalStateException("Work input is closed");
        }
        if (running) return;
        initialiseStateListener();
        running = true;
    }

    @Override public void stop() {
        stopListener();
        synchronized (this) { running = false; }
    }

    @Override public void close() {
        synchronized (desiredStateLock) { closed = true; }
        try {
            stop();
        } finally {
            execution.close();
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        applyListenerState();
    }

    private void updateConcurrency(WorkerStateSnapshot snapshot) {
        execution.setMaxInFlight(snapshot.config(MaxInFlightConfig.class)
            .map(MaxInFlightConfig::maxInFlight).orElse(MessageWorkExecutor.MIN_IN_FLIGHT));
    }

    private void toggleListener(boolean enabled) {
        boolean previous = updateDesiredState(enabled);
        if (previous != enabled && log.isInfoEnabled()) {
            log.info(
                "{} work lifecycle {} (instance={})",
                displayName,
                enabled ? "enabled" : "disabled",
                identity.instanceId());
        }
        applyListenerState();
    }

    private boolean updateDesiredState(boolean enabled) {
        synchronized (desiredStateLock) {
            if (enabled && closed) throw new IllegalStateException("Work input is closed");
            boolean previous = desiredEnabled;
            desiredEnabled = enabled;
            // Never wait for the transport monitor before waking admission waiters.
            if (!enabled) execution.pause();
            return previous;
        }
    }

    /** Applies transport effects serially; desiredStateLock is never held across a channel call. */
    private synchronized void applyListenerState() {
        boolean enabled;
        synchronized (desiredStateLock) {
            enabled = desiredEnabled && !closed;
            // Reopening waits for preceding transport stop; closing does not.
            if (enabled) execution.resume();
            else execution.pause();
        }
        WorkInputChannelState state = channel.state();
        if (state == WorkInputChannelState.NOT_REGISTERED) {
            log.debug("{} listener not yet registered; desiredEnabled={}", displayName, enabled);
            return;
        }
        if (enabled && state != WorkInputChannelState.RUNNING) {
            channel.start();
        } else if (!enabled && state == WorkInputChannelState.RUNNING) {
            channel.stop();
        }
    }

}
