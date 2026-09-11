package io.pockethive.worker.sdk.config;

/**
 * Responsibility: bind output properties and project canonical Rabbit settings.
 * Must not: define defaults, normalize names or implement validation rules.
 * Contract: docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public class RabbitOutputProperties implements WorkOutputConfig {

    private boolean persistent = io.pockethive.rabbit.api.RabbitOutputSettings.DEFAULT_PERSISTENT;
    private boolean publisherConfirms = io.pockethive.rabbit.api.RabbitOutputSettings.DEFAULT_PUBLISHER_CONFIRMS;
    private String exchange;
    private String routingKey;

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean isPublisherConfirms() {
        return publisherConfirms;
    }

    public void setPublisherConfirms(boolean publisherConfirms) {
        this.publisherConfirms = publisherConfirms;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public io.pockethive.rabbit.api.RabbitOutputSettings settings() {
        return io.pockethive.rabbit.api.RabbitConfiguration.resolveOutput(exchange, routingKey, persistent, publisherConfirms);
    }
    @Override public void validateConfigured(String prefix) {
        var resolved = settings();
        exchange = resolved.exchange(); routingKey = resolved.routingKey();
    }
}
