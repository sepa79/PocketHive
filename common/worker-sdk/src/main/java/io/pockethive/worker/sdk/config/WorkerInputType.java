package io.pockethive.worker.sdk.config;

/**
 * Enumerates the input bindings supported by the worker runtime. Concrete {@link
 * io.pockethive.worker.sdk.input.WorkInput} implementations are selected according to these values.
 */
public enum WorkerInputType {
    /**
     * Input driven by RabbitMQ listeners.
     */
    RABBIT,
    /**
     * Input driven by a scheduler (generators, triggers, cron-like sources).
     */
    SCHEDULER
}
