package io.pockethive.controlplane.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * Parses control-plane messages, applies duplicate/self filtering, then invokes the handler.
 */
public final class ControlPlaneConsumer {

    private final ObjectMapper objectMapper;
    private final ControlPlaneIdentity identity;
    private final DuplicateSignalGuard duplicateGuard;
    private final SelfFilter selfFilter;
    private final Clock clock;
    private final Function<IOException, RuntimeException> errorMapper;

    private ControlPlaneConsumer(Builder builder) {
        this.objectMapper = Objects.requireNonNull(builder.objectMapper, "objectMapper");
        this.identity = builder.identity;
        this.duplicateGuard = builder.duplicateGuard != null ? builder.duplicateGuard : DuplicateSignalGuard.disabled();
        this.selfFilter = builder.selfFilter != null ? builder.selfFilter : SelfFilter.NONE;
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
        this.errorMapper = builder.errorMapper != null ? builder.errorMapper : RuntimeException::new;
    }

    public boolean consume(String payload, String routingKey, ControlSignalHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (payload == null || payload.isBlank() || routingKey == null || routingKey.isBlank()) {
            return false;
        }
        ControlSignal signal = parse(payload);
        ControlSignalEnvelope envelope = new ControlSignalEnvelope(signal, routingKey, clock.instant());
        if (!selfFilter.shouldProcess(identity, envelope)) {
            return false;
        }
        if (!duplicateGuard.markIfNew(signal)) {
            return false;
        }
        try {
            handler.handle(envelope);
            return true;
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private ControlSignal parse(String payload) {
        try {
            return objectMapper.readValue(payload, ControlSignal.class);
        } catch (IOException e) {
            throw errorMapper.apply(e);
        }
    }

    public static Builder builder(ObjectMapper objectMapper) {
        return new Builder(objectMapper);
    }

    public static final class Builder {
        private final ObjectMapper objectMapper;
        private ControlPlaneIdentity identity;
        private DuplicateSignalGuard duplicateGuard;
        private SelfFilter selfFilter;
        private Clock clock;
        private Function<IOException, RuntimeException> errorMapper;

        private Builder(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        }

        public Builder identity(ControlPlaneIdentity identity) {
            this.identity = identity;
            return this;
        }

        public Builder duplicateGuard(DuplicateSignalGuard duplicateGuard) {
            this.duplicateGuard = duplicateGuard;
            return this;
        }

        public Builder selfFilter(SelfFilter selfFilter) {
            this.selfFilter = selfFilter;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder errorMapper(Function<IOException, RuntimeException> errorMapper) {
            this.errorMapper = errorMapper;
            return this;
        }

        public ControlPlaneConsumer build() {
            return new ControlPlaneConsumer(this);
        }
    }
}
