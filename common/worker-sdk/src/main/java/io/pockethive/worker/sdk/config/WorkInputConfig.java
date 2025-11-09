package io.pockethive.worker.sdk.config;

/**
 * Marker interface for framework-provided input configuration objects. Concrete implementations
 * (scheduler, RabbitMQ, HTTP poller, etc.) can expose structured properties while remaining optional
 * for worker code.
 */
public interface WorkInputConfig {
}
