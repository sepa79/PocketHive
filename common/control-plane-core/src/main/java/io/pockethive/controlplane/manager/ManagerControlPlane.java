package io.pockethive.controlplane.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.ControlPlaneConsumer;
import io.pockethive.controlplane.consumer.ControlSignalHandler;
import io.pockethive.controlplane.consumer.DuplicateSignalGuard;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import java.time.Duration;
import java.util.Objects;

/**
 * Convenience wrapper around {@link ControlPlaneConsumer} and {@link ControlPlanePublisher}
 * tuned for manager services such as the orchestrator and swarm controller.
 */
public final class ManagerControlPlane {

    private final ControlPlanePublisher publisher;
    private final ControlPlaneConsumer consumer;

    private ManagerControlPlane(ControlPlanePublisher publisher, ControlPlaneConsumer consumer) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.consumer = consumer;
    }

    /**
     * Publishes a control-plane signal using the configured publisher.
     */
    public void publishSignal(io.pockethive.controlplane.messaging.SignalMessage message) {
        publisher.publishSignal(message);
    }

    /**
     * Publishes a control-plane event using the configured publisher.
     */
    public void publishEvent(io.pockethive.controlplane.messaging.EventMessage message) {
        publisher.publishEvent(message);
    }

    /**
     * Consumes a raw signal payload if the configured consumer accepts it.
     *
     * @return {@code true} if the payload was processed by the handler, {@code false} otherwise.
     */
    public boolean consume(String payload, String routingKey, ControlSignalHandler handler) {
        if (consumer == null) {
            return false;
        }
        return consumer.consume(payload, routingKey, handler);
    }

    public ControlPlanePublisher publisher() {
        return publisher;
    }

    public static Builder builder(ControlPlanePublisher publisher, ObjectMapper objectMapper) {
        return new Builder(publisher, objectMapper);
    }

    public static final class Builder {
        private final ControlPlanePublisher publisher;
        private final ControlPlaneConsumer.Builder consumerBuilder;
        private boolean selfFilterConfigured;
        private boolean duplicateConfigured;
        private Duration duplicateTtl;
        private Integer duplicateCapacity;

        private Builder(ControlPlanePublisher publisher, ObjectMapper objectMapper) {
            this.publisher = Objects.requireNonNull(publisher, "publisher");
            this.consumerBuilder = ControlPlaneConsumer.builder(Objects.requireNonNull(objectMapper, "objectMapper"));
        }

        public Builder identity(ControlPlaneIdentity identity) {
            consumerBuilder.identity(identity);
            return this;
        }

        public Builder duplicateGuard(DuplicateSignalGuard guard) {
            consumerBuilder.duplicateGuard(guard);
            duplicateConfigured = true;
            return this;
        }

        public Builder duplicateCache(Duration ttl, int capacity) {
            this.duplicateTtl = ttl;
            this.duplicateCapacity = capacity;
            return this;
        }

        public Builder selfFilter(SelfFilter selfFilter) {
            consumerBuilder.selfFilter(selfFilter);
            selfFilterConfigured = true;
            return this;
        }

        public Builder clock(java.time.Clock clock) {
            consumerBuilder.clock(clock);
            return this;
        }

        public Builder errorMapper(java.util.function.Function<java.io.IOException, RuntimeException> mapper) {
            consumerBuilder.errorMapper(mapper);
            return this;
        }

        public ManagerControlPlane build() {
            if (!selfFilterConfigured) {
                consumerBuilder.selfFilter(SelfFilter.NONE);
            }
            if (!duplicateConfigured && duplicateTtl != null && duplicateCapacity != null) {
                consumerBuilder.duplicateGuard(DuplicateSignalGuard.create(duplicateTtl, duplicateCapacity));
            }
            ControlPlaneConsumer consumer = consumerBuilder.build();
            return new ManagerControlPlane(publisher, consumer);
        }
    }
}
