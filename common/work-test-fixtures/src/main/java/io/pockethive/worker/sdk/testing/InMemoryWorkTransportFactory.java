package io.pockethive.worker.sdk.testing;

import io.pockethive.work.api.transport.*;
import io.pockethive.work.config.WorkIoType;
import io.pockethive.work.config.binding.*;
import java.util.Objects;

/**
 * Responsibility: bind typed memory IO settings to transport handles.
 * Must not: create missing resources or dispatch worker logic.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkTransportFactory implements WorkInputTransportFactory, WorkOutputTransportFactory {
    private final InMemoryWorkTransport transport;
    public InMemoryWorkTransportFactory(InMemoryWorkTransport transport) { this.transport = Objects.requireNonNull(transport); }
    @Override public WorkIoType type() { return InMemoryWorkType.MEMORY; }
    @Override public WorkInputChannel create(String workerName, WorkInputConfig config) {
        return transport.input(((InMemoryWorkInputSettings) config).address());
    }
    @Override public WorkOutput create(WorkOutputConfig config) {
        return transport.output(((InMemoryWorkOutputSettings) config).address());
    }
}
