package io.pockethive.worker.sdk.config;

import io.pockethive.work.api.PocketHiveWorker;

/**
 * Marker interface for framework-managed output configuration (RabbitMQ publishing, HTTP sinks, etc.).
 * Workers can override the concrete type via {@link PocketHiveWorker}.
 * <p>
 * Responsibility: define the selected Work output settings contract.
 * Must not: make settings objects open connections or infer successful publication from configuration.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public interface WorkOutputConfig {
    default void validateConfigured(String prefix) {
    }
}
