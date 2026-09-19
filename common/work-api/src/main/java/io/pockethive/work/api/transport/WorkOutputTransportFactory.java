package io.pockethive.work.api.transport;

import io.pockethive.work.config.WorkIoType;
import io.pockethive.work.config.binding.WorkOutputConfig;

/**
 * Responsibility: create the selected transport output from its adapter-owned bound settings.
 * Must not: inspect SDK worker state, own execution policy or select a different adapter.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public interface WorkOutputTransportFactory {
    WorkIoType type();
    WorkOutput create(WorkOutputConfig config);
}
