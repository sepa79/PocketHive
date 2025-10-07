package io.pockethive.worker.sdk.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Infrastructure-backed context passed to worker business logic.
 * <p>
 * Provides access to control-plane configuration, metrics, logging, and observability facilities. Refer to
 * {@code docs/sdk/worker-sdk-quickstart.md} for usage guidance across Stage 1–3 features.
 */
public interface WorkerContext {

    /**
     * Returns metadata about the running worker (role, queues, swarm identifiers).
     */
    WorkerInfo info();

    /**
     * Resolves the latest typed configuration supplied by the control plane.
     *
     * @param type configuration class to look up
     * @param <C>  configuration type
     * @return a typed configuration or {@link Optional#empty()} when no config is available
     */
    <C> Optional<C> config(Class<C> type);

    /**
     * Convenience wrapper that falls back to a supplier when no configuration is available.
     */
    default <C> C configOrDefault(Class<C> type, Supplier<C> fallback) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fallback, "fallback");
        return config(type).orElseGet(fallback);
    }

    /**
     * Returns the {@link StatusPublisher} used to enrich emitted status snapshots.
     */
    StatusPublisher statusPublisher();

    /**
     * Returns a SLF4J logger scoped to the worker implementation.
     */
    Logger logger();

    /**
     * Provides the Micrometer registry shared by the runtime (Stage 3).
     */
    MeterRegistry meterRegistry();

    /**
     * Provides the Micrometer Observation registry shared by the runtime (Stage 3).
     */
    ObservationRegistry observationRegistry();

    /**
     * Returns the propagated observability context (trace identifiers and hop history).
     * <p>
     * Implementations must always return a non-{@code null} instance. The SDK runtime ensures callers
     * receive an initialised context even when the inbound message does not include one.
     */
    ObservabilityContext observabilityContext();
}
