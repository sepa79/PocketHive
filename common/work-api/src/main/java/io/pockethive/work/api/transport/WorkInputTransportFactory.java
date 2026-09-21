package io.pockethive.work.api.transport;

import io.pockethive.work.config.WorkIoType;
import io.pockethive.work.config.binding.WorkInputConfig;

/**
 * Responsibility: create the selected transport input from its adapter-owned bound settings.
 * Must not: inspect SDK worker state, own execution policy or select a different adapter.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public interface WorkInputTransportFactory {
    WorkIoType type();
    WorkInputChannel create(String workerName, WorkInputConfig config);
}
