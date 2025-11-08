package io.pockethive.worker.sdk.config;

/**
 * RabbitMQ-specific output knobs surfaced via {@code pockethive.outputs.<role>.*}.
 */
public class RabbitOutputProperties implements WorkOutputConfig {

    private boolean persistent = true;
    private boolean publisherConfirms = false;
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
        this.exchange = normalise(exchange);
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = normalise(routingKey);
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
