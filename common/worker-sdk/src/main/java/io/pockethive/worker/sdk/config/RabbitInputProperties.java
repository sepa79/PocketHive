package io.pockethive.worker.sdk.config;

/**
 * Responsibility: bind Rabbit Work input settings from pockethive.inputs.rabbit.*.
 * Must not: define defaults or validation rules, normalize names, start listeners or configure Control Plane.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public class RabbitInputProperties implements WorkInputConfig {

    private int prefetch = io.pockethive.rabbit.api.RabbitInputSettings.DEFAULT_PREFETCH;
    private int concurrentConsumers = io.pockethive.rabbit.api.RabbitInputSettings.DEFAULT_CONCURRENT_CONSUMERS;
    private boolean exclusive = io.pockethive.rabbit.api.RabbitInputSettings.DEFAULT_EXCLUSIVE;
    private String queue;
    private String deadLetterQueue;

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        this.prefetch = prefetch;
    }

    public int getConcurrentConsumers() {
        return concurrentConsumers;
    }

    public void setConcurrentConsumers(int concurrentConsumers) {
        this.concurrentConsumers = concurrentConsumers;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public void setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public void setDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
    }

    public io.pockethive.rabbit.api.RabbitInputSettings settings() {
        return io.pockethive.rabbit.api.RabbitConfiguration.resolveInput(queue, prefetch, concurrentConsumers, exclusive, deadLetterQueue);
    }
    @Override public void validateConfigured(String prefix) { queue = settings().queue(); }
}
