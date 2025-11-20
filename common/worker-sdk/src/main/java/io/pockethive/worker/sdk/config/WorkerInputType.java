package io.pockethive.worker.sdk.config;

/**
 * Enumerates the input bindings supported by the worker runtime. Concrete {@link
 * io.pockethive.worker.sdk.input.WorkInput} implementations are selected according to these values.
 */
public enum WorkerInputType {
    /**
     * Input driven by RabbitMQ listeners.
     */
    RABBITMQ,
    /**
     * Input driven by a scheduler (generators, triggers, cron-like sources).
     */
    SCHEDULER,
    /**
     * Input driven by Redis lists for dataset playback at a fixed rate.
     */
    REDIS_DATASET
}
