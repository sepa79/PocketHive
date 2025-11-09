package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Collections;
import java.util.Map;

/**
 * Context supplied to {@link WorkInputFactory} when creating a new {@link WorkInput}. It exposes the
 * worker definition, control-plane runtime, dispatcher hook, and any additional attributes provided
 * by the hosting service.
 */
public interface WorkInputContext {

    /**
     * @return metadata describing the worker that will consume messages emitted by the input
     */
    WorkerDefinition definition();

    /**
     * @return control-plane runtime used to register state listeners, default configs, etc.
     */
    WorkerControlPlaneRuntime controlPlaneRuntime();

    /**
     * @return dispatcher invoked by inputs to pass {@code WorkMessage} envelopes to the worker runtime
     */
    WorkMessageDispatcher dispatcher();

    /**
     * @return immutable map of optional attributes supplied by the hosting service (transport clients,
     *         identities, custom settings, ...)
     */
    default Map<String, Object> attributes() {
        return Collections.emptyMap();
    }
}
