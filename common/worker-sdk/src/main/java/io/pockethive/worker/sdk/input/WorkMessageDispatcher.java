package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.api.WorkItem;

/**
 * Dispatch hook invoked by {@link WorkInput inputs} when they have a {@link WorkItem} ready for
 * processing. Implementations typically delegate to {@code WorkerRuntime.dispatch(...)} while preserving
 * error handling semantics defined by the hosting service.
 */
@FunctionalInterface
public interface WorkMessageDispatcher {

    WorkItem dispatch(WorkItem message) throws Exception;
}
