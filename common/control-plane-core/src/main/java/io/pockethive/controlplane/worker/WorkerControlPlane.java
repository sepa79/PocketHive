package io.pockethive.controlplane.worker;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.consumer.ControlPlaneConsumer;
import io.pockethive.controlplane.consumer.ControlSignalHandler;
import io.pockethive.controlplane.consumer.DuplicateSignalGuard;
import io.pockethive.controlplane.consumer.SelfFilter;

import java.time.Duration;
import java.util.Objects;

/**
 * Helper that wires worker-facing consumers to {@link WorkerSignalListener}
 * callbacks, translating incoming control signals into structured payloads.
 */
public final class WorkerControlPlane {

    private final ControlPlaneConsumer consumer;

    private WorkerControlPlane(ControlPlaneConsumer consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    public boolean consume(String payload, String routingKey, WorkerSignalListener listener) {
        Objects.requireNonNull(listener, "listener");
        return consumer.consume(payload, routingKey, handler(listener));
    }

    private ControlSignalHandler handler(WorkerSignalListener listener) {
        return envelope -> {
            WorkerSignalListener.WorkerSignalContext context =
                new WorkerSignalListener.WorkerSignalContext(envelope);
            switch (envelope.signal().type()) {
                case ControlPlaneSignals.CONFIG_UPDATE -> listener.onConfigUpdate(WorkerConfigCommand.from(envelope));
                case ControlPlaneSignals.STATUS_REQUEST -> listener.onStatusRequest(new WorkerStatusRequest(envelope));
                default -> listener.onUnsupported(context);
            }
        };
    }

    public static Builder builder(ControlPlaneCodec codec) {
        return new Builder(codec);
    }

    public static final class Builder {

        private final ControlPlaneConsumer.Builder consumerBuilder;
        private boolean selfFilterConfigured;
        private boolean duplicateConfigured;
        private Duration duplicateTtl;
        private Integer duplicateCapacity;

        private Builder(ControlPlaneCodec codec) {
            this.consumerBuilder = ControlPlaneConsumer.builder(Objects.requireNonNull(codec, "codec"));
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

        public WorkerControlPlane build() {
            if (!selfFilterConfigured) {
                consumerBuilder.selfFilter(SelfFilter.NONE);
            }
            if (!duplicateConfigured && duplicateTtl != null && duplicateCapacity != null) {
                consumerBuilder.duplicateGuard(DuplicateSignalGuard.create(duplicateTtl, duplicateCapacity));
            }
            ControlPlaneConsumer consumer = consumerBuilder.build();
            return new WorkerControlPlane(consumer);
        }
    }
}
