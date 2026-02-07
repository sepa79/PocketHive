package io.pockethive.controlplane.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.ControlPlaneConsumer;
import io.pockethive.controlplane.consumer.ControlSignalHandler;
import io.pockethive.controlplane.consumer.DuplicateSignalGuard;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;

import java.time.Duration;
import java.util.Objects;

/**
 * Helper that wires worker-facing consumers to {@link WorkerSignalListener}
 * callbacks, translating incoming control signals into structured payloads.
 */
public final class WorkerControlPlane {

    private final ControlPlaneConsumer consumer;
    private final ObjectMapper objectMapper;

    private WorkerControlPlane(ControlPlaneConsumer consumer, ObjectMapper objectMapper) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public boolean consume(String payload, String routingKey, WorkerSignalListener listener) {
        Objects.requireNonNull(listener, "listener");
        return consumer.consume(payload, routingKey, handler(listener));
    }

	    private ControlSignalHandler handler(WorkerSignalListener listener) {
	        return envelope -> {
	            WorkerSignalListener.WorkerSignalContext context =
	                new WorkerSignalListener.WorkerSignalContext(envelope, envelope.payload());
	            String resolvedSignal = resolveSignalName(envelope);
	            if (resolvedSignal == null) {
	                listener.onUnsupported(context);
	                return;
	            }
            switch (resolvedSignal) {
                case "config-update" -> listener.onConfigUpdate(WorkerConfigCommand.from(envelope, envelope.payload(), objectMapper));
                case "status-request" -> listener.onStatusRequest(new WorkerStatusRequest(envelope, envelope.payload()));
                default -> listener.onUnsupported(context);
            }
        };
    }

    private String resolveSignalName(io.pockethive.controlplane.consumer.ControlSignalEnvelope envelope) {
        ControlSignal signal = envelope.signal();
        if (signal != null && hasText(signal.type())) {
            return signal.type();
        }
        ControlPlaneRouting.RoutingKey routingKey = ControlPlaneRouting.parseSignal(envelope.routingKey());
        if (routingKey != null && hasText(routingKey.type())) {
            return routingKey.type();
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static Builder builder(ObjectMapper objectMapper) {
        return new Builder(objectMapper);
    }

    public static final class Builder {

        private final ObjectMapper objectMapper;
        private final ControlPlaneConsumer.Builder consumerBuilder;
        private boolean selfFilterConfigured;
        private boolean duplicateConfigured;
        private Duration duplicateTtl;
        private Integer duplicateCapacity;

        private Builder(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            this.consumerBuilder = ControlPlaneConsumer.builder(this.objectMapper);
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

        public WorkerControlPlane build() {
            if (!selfFilterConfigured) {
                consumerBuilder.selfFilter(SelfFilter.NONE);
            }
            if (!duplicateConfigured && duplicateTtl != null && duplicateCapacity != null) {
                consumerBuilder.duplicateGuard(DuplicateSignalGuard.create(duplicateTtl, duplicateCapacity));
            }
            ControlPlaneConsumer consumer = consumerBuilder.build();
            return new WorkerControlPlane(consumer, objectMapper);
        }
    }
}
