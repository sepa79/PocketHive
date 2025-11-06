package io.pockethive.worker.sdk.input;

/**
 * Factory that creates {@link WorkInput} instances for a given worker definition. Implementations can
 * inject transport-specific dependencies (RabbitMQ, schedulers, HTTP clients, etc.) and wire them to the
 * provided dispatcher.
 */
@FunctionalInterface
public interface WorkInputFactory {

    /**
     * Builds a {@link WorkInput} using the supplied context.
     */
    WorkInput create(WorkInputContext context);
}
