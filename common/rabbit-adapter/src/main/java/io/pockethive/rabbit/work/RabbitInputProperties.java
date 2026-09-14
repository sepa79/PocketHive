package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitConfiguration;
import io.pockethive.rabbit.api.RabbitInputSettings;
import io.pockethive.work.config.binding.WorkInputConfig;
/**
 * Responsibility: bind Rabbit Work input settings from pockethive.inputs.rabbit.*.
 * Must not: define defaults or validation rules, normalize names, start listeners or configure Control Plane.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public class RabbitInputProperties implements WorkInputConfig {

    private int prefetch = RabbitInputSettings.DEFAULT_PREFETCH;
    private int concurrentConsumers = RabbitInputSettings.DEFAULT_CONCURRENT_CONSUMERS;
    private boolean exclusive = RabbitInputSettings.DEFAULT_EXCLUSIVE;
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

    public RabbitInputSettings settings() {
        return RabbitConfiguration.resolveInput(queue, prefetch, concurrentConsumers, exclusive, deadLetterQueue);
    }
    @Override public String inboundRoute() { return settings().queue(); }
    @Override public void validateConfigured(String prefix) { queue = settings().queue(); }
}
