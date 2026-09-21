package io.pockethive.worker.sdk.input.message;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * Responsibility: connect the selected input transport to the shared SDK dispatch/state bridge.
 * Must not: create broker clients, ignore selected settings or publish worker results.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public final class MessageWorkInputFactory implements WorkInputFactory, Ordered {
    private final WorkerRuntime runtime;
    private final WorkerControlPlaneRuntime control;
    private final ControlPlaneIdentity identity;
    private final WorkInputTransportFactory transport;
    public MessageWorkInputFactory(WorkerRuntime runtime, WorkerControlPlaneRuntime control,
                                  ControlPlaneIdentity identity, WorkInputTransportFactory transport) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.control = Objects.requireNonNull(control, "control");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.transport = Objects.requireNonNull(transport, "transport");
    }
    @Override public boolean supports(WorkerDefinition definition) { return definition.input().equals(transport.type()); }
    @Override public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        var channel = transport.create(definition.beanName(), config);
        return MessageWorkInput.builder().logger(LoggerFactory.getLogger(definition.beanType()))
            .channel(channel).displayName(definition.beanName()).workerDefinition(definition)
            .controlPlaneRuntime(control).identity(identity)
            .dispatcher(item -> runtime.dispatch(definition.beanName(), item)).build();

    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
