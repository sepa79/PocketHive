package io.pockethive.worker.sdk.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import java.util.Objects;
import java.util.Optional;
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
     * Indicates whether the control-plane has this worker enabled.
     */
    boolean enabled();

    /**
     * Resolves the latest typed configuration supplied by the control plane.
     *
     * @param type configuration class to look up
     * @param <C>  configuration type
     * @return the typed configuration or {@code null} when no config is available
     */
    <C> C config(Class<C> type);

    /**
     * Convenience wrapper that exposes the configuration as an {@link Optional}.
     */
    default <C> Optional<C> configOptional(Class<C> type) {
        return Optional.ofNullable(config(type));
    }

    /**
     * Convenience wrapper that fails when no runtime configuration is available.
     */
    default <C> C requireConfig(Class<C> type) {
        Objects.requireNonNull(type, "type");
        C value = config(type);
        if (value == null) {
            throw new IllegalStateException("Missing runtime config for " + type.getName());
        }
        return value;
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

    /**
     * Returns the effective {@link HistoryPolicy} for this worker, as resolved from service defaults
     * and, if applicable, Scenario configuration. The default is {@link HistoryPolicy#FULL}.
     */
    default HistoryPolicy historyPolicy() {
        return HistoryPolicy.FULL;
    }
}
