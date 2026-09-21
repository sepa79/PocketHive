package io.pockethive.worker.sdk.input;

import io.pockethive.work.api.WorkItem;

/**
 * Dispatch hook invoked by {@link WorkInput inputs} when they have a {@link WorkItem} ready for
 * processing. Implementations typically delegate to {@code WorkerRuntime.dispatch(...)} while preserving
 * error handling semantics defined by the hosting service.
 * <p>
 * Responsibility: define the transport-independent dispatch callback for one Work item.
 * Must not: reimplement service business logic or introduce a second output publication for the same result.
 * Contract: RESP-WORK-INVOCATION — docs/architecture/runtime-responsibilities.md#resp-work-invocation.
 */
@FunctionalInterface
public interface WorkMessageDispatcher {

    WorkItem dispatch(WorkItem message) throws Exception;
}
