package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.transport.WorkOutput;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Objects;

/**
 * Responsibility: expose an adapter output factory to the sole SDK output selection path.
 * Must not: inspect adapter settings, create clients or publish results independently.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public final class TransportWorkOutputFactory implements WorkOutputFactory {
    private final WorkOutputTransportFactory transport;
    public TransportWorkOutputFactory(WorkOutputTransportFactory transport) { this.transport = Objects.requireNonNull(transport); }
    @Override public boolean supports(WorkerDefinition definition) { return definition.outputType().equals(transport.type()); }
    @Override public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) { return transport.create(config); }
}
