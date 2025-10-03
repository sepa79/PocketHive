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
 */
public interface WorkerContext {

    WorkerInfo info();

    <C> Optional<C> config(Class<C> type);

    default <C> C configOrDefault(Class<C> type, Supplier<C> fallback) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fallback, "fallback");
        return config(type).orElseGet(fallback);
    }

    StatusPublisher statusPublisher();

    Logger logger();

    MeterRegistry meterRegistry();

    ObservationRegistry observationRegistry();

    ObservabilityContext observabilityContext();
}
