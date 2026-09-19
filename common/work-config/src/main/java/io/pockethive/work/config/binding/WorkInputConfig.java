package io.pockethive.work.config.binding;

/**
 * Marker interface for framework-provided input configuration objects. Concrete implementations
 * (scheduler, RabbitMQ, HTTP poller, etc.) can expose structured properties while remaining optional
 * for worker code.
 * Responsibility: bind selected input settings and expose their read-only status route.
 * Must not: select transports, define adapter rules or start inputs.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public interface WorkInputConfig {
    default String inboundRoute() { return null; }

    default void validateConfigured(String prefix) {
    }
}
