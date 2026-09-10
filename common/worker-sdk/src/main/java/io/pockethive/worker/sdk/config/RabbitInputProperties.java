package io.pockethive.worker.sdk.config;

/**
 * Responsibility: bind Rabbit Work input settings from pockethive.inputs.rabbit.*.
 * Must not: own worker enablement, start listeners or configure Control Plane.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public class RabbitInputProperties implements WorkInputConfig {

    private int prefetch = 50;
    private int concurrentConsumers = 1;
    private boolean exclusive = false;
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
        this.queue = normalise(queue);
    }

    public String getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public void setDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = normalise(deadLetterQueue);
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
