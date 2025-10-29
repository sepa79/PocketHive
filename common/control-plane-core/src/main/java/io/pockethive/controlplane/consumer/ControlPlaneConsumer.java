package io.pockethive.controlplane.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses control-plane messages, applies duplicate/self filtering, then invokes the handler.
 */
public final class ControlPlaneConsumer {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneConsumer.class);

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
        requireText(payload, "payload");
        requireText(routingKey, "routingKey");
        ControlSignal signal = parse(payload);
        ControlSignalEnvelope envelope = new ControlSignalEnvelope(signal, routingKey, payload, clock.instant());
        logReceived(envelope);
        if (!selfFilter.shouldProcess(identity, envelope)) {
            log.info(
                "Skipping control-plane signal '{}' via '{}' for identity {} due to self-filter",
                safe(signal.signal()), routingKey, describeIdentity(identity)
            );
            return false;
        }
        if (!duplicateGuard.markIfNew(signal)) {
            log.info(
                "Skipping duplicate control-plane signal '{}' via '{}' (correlationId='{}', idempotencyKey='{}')",
                safe(signal.signal()), routingKey, safe(signal.correlationId()), safe(signal.idempotencyKey())
            );
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }

    private ControlSignal parse(String payload) {
        try {
            return objectMapper.readValue(payload, ControlSignal.class);
        } catch (IOException e) {
            throw errorMapper.apply(e);
        }
    }

    private void logReceived(ControlSignalEnvelope envelope) {
        if (!log.isInfoEnabled()) {
            return;
        }
        ControlSignal signal = envelope.signal();
        log.info(
            "Received control-plane signal '{}' via '{}' for identity {} (target='{}', swarm='{}', role='{}', instance='{}', origin='{}', correlationId='{}', idempotencyKey='{}')",
            safe(signal.signal()),
            envelope.routingKey(),
            describeIdentity(identity),
            safe(signal.commandTarget()),
            safe(signal.swarmId()),
            safe(signal.role()),
            safe(signal.instance()),
            safe(signal.origin()),
            safe(signal.correlationId()),
            safe(signal.idempotencyKey())
        );
    }

    private static String describeIdentity(ControlPlaneIdentity identity) {
        if (identity == null) {
            return "swarm=n/a role=n/a instance=n/a";
        }
        return String.format(
            "swarm=%s role=%s instance=%s",
            safe(identity.swarmId()),
            safe(identity.role()),
            safe(identity.instanceId())
        );
    }

    private static String safe(Object value) {
        if (value == null) {
            return "n/a";
        }
        if (value instanceof String str) {
            return str.isBlank() ? "n/a" : str;
        }
        return value.toString();
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
