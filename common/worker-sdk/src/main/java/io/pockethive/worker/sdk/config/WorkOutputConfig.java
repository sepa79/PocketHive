package io.pockethive.worker.sdk.config;

/**
 * Marker interface for framework-managed output configuration (RabbitMQ publishing, HTTP sinks, etc.).
 * Workers can override the concrete type via {@link io.pockethive.worker.sdk.config.PocketHiveWorker}.
 */
public interface WorkOutputConfig {
}
