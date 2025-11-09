package io.pockethive.worker.sdk.config;

/**
 * Declares how a worker emits downstream work/results. Additional output types can be added
 * when new transports are supported.
 */
public enum WorkerOutputType {

    /**
     * Worker does not emit downstream work (pure scheduler/status producer, etc.).
     */
    NONE,

    /**
     * Worker publishes results to RabbitMQ queues/exchanges.
     */
    RABBITMQ
}
