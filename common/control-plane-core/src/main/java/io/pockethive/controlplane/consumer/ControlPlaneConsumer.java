package io.pockethive.controlplane.consumer;

import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.codec.ControlPlaneCodec;

import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses control-plane messages, applies duplicate/self filtering, then invokes the handler.
 */
public final class ControlPlaneConsumer {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneConsumer.class);

    private final ControlPlaneCodec codec;
    private final ControlPlaneIdentity identity;
    private final DuplicateSignalGuard duplicateGuard;
    private final SelfFilter selfFilter;
    private final Clock clock;

    private ControlPlaneConsumer(Builder builder) {
        this.codec = Objects.requireNonNull(builder.codec, "codec");
        this.identity = builder.identity;
        this.duplicateGuard = builder.duplicateGuard != null ? builder.duplicateGuard : DuplicateSignalGuard.disabled();
        this.selfFilter = builder.selfFilter != null ? builder.selfFilter : SelfFilter.NONE;
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
    }

    public boolean consume(String payload, String routingKey, ControlSignalHandler handler) {
        Objects.requireNonNull(handler, "handler");
        requireText(payload, "payload");
        requireText(routingKey, "routingKey");
        ControlSignal signal = parse(payload, routingKey);
        ControlSignalEnvelope envelope = new ControlSignalEnvelope(signal, routingKey, clock.instant());
        logReceived(envelope);
        if (!selfFilter.shouldProcess(identity, envelope)) {
            log.info(
                "Skipping control-plane signal '{}' via '{}' for identity {} due to self-filter",
                safe(resolveSignalName(envelope)), routingKey, describeIdentity(identity)
            );
            return false;
        }
        if (!duplicateGuard.markIfNew(signal)) {
            log.info(
                "Skipping duplicate control-plane signal '{}' via '{}' (correlationId='{}', idempotencyKey='{}')",
                safe(resolveSignalName(envelope)), routingKey, safe(signal.correlationId()), safe(signal.idempotencyKey())
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

    private ControlSignal parse(String payload, String routingKey) {
        return codec.decode(payload, routingKey, ControlSignal.class);
    }

    private void logReceived(ControlSignalEnvelope envelope) {
        if (!log.isInfoEnabled()) {
            return;
        }
        ControlSignal signal = envelope.signal();
        log.info(
            "Received control-plane signal '{}' via '{}' for identity {} (scope='{}', origin='{}', correlationId='{}', idempotencyKey='{}')",
            safe(resolveSignalName(envelope)),
            envelope.routingKey(),
            describeIdentity(identity),
            safe(signal.scope()),
            safe(signal.origin()),
            safe(signal.correlationId()),
            safe(signal.idempotencyKey())
        );
    }

    private String resolveSignalName(ControlSignalEnvelope envelope) {
        return envelope.signal().type();
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

    public static Builder builder(ControlPlaneCodec codec) {
        return new Builder(codec);
    }

    public static final class Builder {
        private final ControlPlaneCodec codec;
        private ControlPlaneIdentity identity;
        private DuplicateSignalGuard duplicateGuard;
        private SelfFilter selfFilter;
        private Clock clock;

        private Builder(ControlPlaneCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
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

        public ControlPlaneConsumer build() {
            return new ControlPlaneConsumer(this);
        }
    }
}
