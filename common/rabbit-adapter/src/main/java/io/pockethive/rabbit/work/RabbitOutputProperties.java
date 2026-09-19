package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitConfiguration;
import io.pockethive.rabbit.api.RabbitOutputSettings;
import io.pockethive.work.config.binding.WorkOutputConfig;
/**
 * Responsibility: bind output properties and project canonical Rabbit settings.
 * Must not: define defaults, normalize names or implement validation rules.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public class RabbitOutputProperties implements WorkOutputConfig {

    private boolean persistent = RabbitOutputSettings.DEFAULT_PERSISTENT;
    private boolean publisherConfirms = RabbitOutputSettings.DEFAULT_PUBLISHER_CONFIRMS;
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

    public RabbitOutputSettings settings() {
        return RabbitConfiguration.resolveOutput(exchange, routingKey, persistent, publisherConfirms);
    }
    @Override public String outboundRoute() { return settings().routingKey(); }
    @Override public String outboundGroup() { return settings().exchange(); }
    @Override public void validateConfigured(String prefix) {
        var resolved = settings();
        exchange = resolved.exchange(); routingKey = resolved.routingKey();
    }
}
