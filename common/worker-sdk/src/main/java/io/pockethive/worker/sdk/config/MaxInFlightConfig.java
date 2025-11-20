package io.pockethive.worker.sdk.config;

/**
 * Marker interface for worker-domain configuration objects that expose a
 * maximum number of in-flight invocations for the worker instance.
 * <p>
 * Runtime adapters such as Rabbit inputs can use this hint to cap the
 * number of concurrent dispatches without having to know worker-specific
 * configuration types.
 */
public interface MaxInFlightConfig {

    /**
     * Returns the maximum number of in-flight invocations allowed for the
     * worker instance. Values less than {@code 1} should be treated as
     * {@code 1} by callers.
     */
    int maxInFlight();
}

