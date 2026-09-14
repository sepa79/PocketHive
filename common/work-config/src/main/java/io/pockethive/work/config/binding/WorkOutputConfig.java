package io.pockethive.work.config.binding;


/**
 * Marker interface for framework-managed output configuration (RabbitMQ publishing, HTTP sinks, etc.).
 * Concrete types are supplied by the selected adapter configuration provider.
 * <p>
 * Responsibility: define the selected Work output settings contract.
 * Must not: make settings objects open connections or infer successful publication from configuration.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public interface WorkOutputConfig {
    default String outboundRoute() { return null; }
    default String outboundGroup() { return null; }

    default void validateConfigured(String prefix) {
    }
}
