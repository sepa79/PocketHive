package io.pockethive.worker.sdk.config;

/**
 * Default RabbitMQ work-input knobs that can be overridden per worker role under
 * {@code pockethive.inputs.<role>.*}.
 */
public class RabbitInputProperties implements WorkInputConfig {

    private boolean enabled = false;
    private int prefetch = 50;
    private int concurrentConsumers = 1;
    private boolean exclusive = false;
    private boolean autoStartup = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public boolean isAutoStartup() {
        return autoStartup;
    }

    public void setAutoStartup(boolean autoStartup) {
        this.autoStartup = autoStartup;
    }
}
