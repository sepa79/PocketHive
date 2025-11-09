package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;

/**
 * Dispatch hook invoked by {@link WorkInput inputs} when they have a {@link WorkMessage} ready for
 * processing. Implementations typically delegate to {@code WorkerRuntime.dispatch(...)} while preserving
 * error handling semantics defined by the hosting service.
 */
@FunctionalInterface
public interface WorkMessageDispatcher {

    WorkResult dispatch(WorkMessage message) throws Exception;
}
