package io.pockethive.controlplane.messaging;

import io.pockethive.control.CommandState;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.payload.ConfirmationPayloadFactory;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.payload.ScopeContext;
import io.pockethive.controlplane.payload.StatusPayloadFactory;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.WorkerControlPlaneTopologyDescriptor;
import io.pockethive.observability.StatusEnvelopeBuilder;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Emits control-plane events using canonical routing keys and payload builders.
 */
public final class ControlPlaneEmitter {

    private final ControlPlaneTopologyDescriptor topology;
    private final RoleContext role;
    private final ControlPlanePublisher publisher;
    private final ConfirmationPayloadFactory confirmationFactory;
    private final StatusPayloadFactory statusFactory;

    private ControlPlaneEmitter(ControlPlaneTopologyDescriptor topology,
                                RoleContext role,
                                ControlPlanePublisher publisher,
                                ConfirmationPayloadFactory confirmationFactory,
                                StatusPayloadFactory statusFactory) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.role = Objects.requireNonNull(role, "role");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.confirmationFactory = Objects.requireNonNull(confirmationFactory, "confirmationFactory");
        this.statusFactory = Objects.requireNonNull(statusFactory, "statusFactory");
        requireRoleMatch();
    }

    public static ControlPlaneEmitter using(ControlPlaneTopologyDescriptor topology,
                                            RoleContext role,
                                            ControlPlanePublisher publisher) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(publisher, "publisher");
        ConfirmationPayloadFactory confirmationFactory = new ConfirmationPayloadFactory(ScopeContext.fromRole(role));
        StatusPayloadFactory statusFactory = new StatusPayloadFactory(role);
        return new ControlPlaneEmitter(topology, role, publisher, confirmationFactory, statusFactory);
    }

    public static ControlPlaneEmitter worker(ControlPlaneIdentity identity,
                                             ControlPlanePublisher publisher,
                                             ControlPlaneTopologySettings settings) {
        RoleContext role = RoleContext.fromIdentity(identity);
        return using(new WorkerControlPlaneTopologyDescriptor(role.role(), settings), role, publisher);
    }

    public static ControlPlaneEmitter swarmController(ControlPlaneIdentity identity,
                                                      ControlPlanePublisher publisher,
                                                      ControlPlaneTopologySettings settings) {
        RoleContext role = requireIdentity(identity, "swarm-controller");
        return using(new SwarmControllerControlPlaneTopologyDescriptor(settings), role, publisher);
    }

    public void emitReady(ReadyContext context) {
        Objects.requireNonNull(context, "context");
        String routingKey = ControlPlaneRouting.event("ready", context.signal(), role.toScope());
        ConfirmationPayloadFactory.ReadyBuilder builder = confirmationFactory.ready(context.signal())
            .correlationId(context.correlationId())
            .idempotencyKey(context.idempotencyKey())
            .state(context.state());
        if (context.timestamp() != null) {
            builder.timestamp(context.timestamp());
        }
        if (context.result() != null) {
            builder.result(context.result());
        }
        if (!context.details().isEmpty()) {
            builder.details(context.details());
        }
        String payload = builder.build();
        publisher.publishEvent(new EventMessage(routingKey, payload));
    }

    public void emitError(ErrorContext context) {
        Objects.requireNonNull(context, "context");
        String routingKey = ControlPlaneRouting.event("error", context.signal(), role.toScope());
        ConfirmationPayloadFactory.ErrorBuilder builder = confirmationFactory.error(context.signal())
            .correlationId(context.correlationId())
            .idempotencyKey(context.idempotencyKey())
            .state(context.state())
            .phase(context.phase())
            .code(context.code())
            .message(context.message());
        if (context.timestamp() != null) {
            builder.timestamp(context.timestamp());
        }
        if (context.result() != null) {
            builder.result(context.result());
        }
        if (context.retryable() != null) {
            builder.retryable(context.retryable());
        }
        if (!context.details().isEmpty()) {
            builder.details(context.details());
        }
        String payload = builder.build();
        publisher.publishEvent(new EventMessage(routingKey, payload));
    }

    public void emitStatusSnapshot(StatusContext context) {
        publishStatus("status-full", context);
    }

    public void emitStatusDelta(StatusContext context) {
        publishStatus("status-delta", context);
    }

    private void publishStatus(String type, StatusContext context) {
        Objects.requireNonNull(context, "context");
        String routingKey = ControlPlaneRouting.event(type, role.toScope());
        Consumer<StatusEnvelopeBuilder> customiser = builder -> {
            builder.controlOut(routingKey);
            context.customiser().accept(builder);
        };
        String payload = switch (type) {
            case "status-full" -> statusFactory.snapshot(customiser);
            case "status-delta" -> statusFactory.delta(customiser);
            default -> throw new IllegalArgumentException("Unsupported status type: " + type);
        };
        publisher.publishEvent(new EventMessage(routingKey, payload));
    }

    private void requireRoleMatch() {
        String expectedRole = topology.role();
        if (!expectedRole.equals(role.role())) {
            throw new IllegalArgumentException("Role context does not match topology role: expected " + expectedRole
                + " but was " + role.role());
        }
    }

    private static RoleContext requireIdentity(ControlPlaneIdentity identity, String expectedRole) {
        Objects.requireNonNull(identity, "identity");
        RoleContext role = RoleContext.fromIdentity(identity);
        if (!expectedRole.equals(role.role())) {
            throw new IllegalArgumentException("Identity role mismatch: expected " + expectedRole + " but was "
                + role.role());
        }
        return role;
    }

    public record ReadyContext(String signal,
                               String correlationId,
                               String idempotencyKey,
                               CommandState state,
                               String result,
                               Instant timestamp,
                               Map<String, Object> details) {

        public ReadyContext {
            signal = requireNonBlank("signal", signal);
            correlationId = requireNonBlank("correlationId", correlationId);
            idempotencyKey = requireNonBlank("idempotencyKey", idempotencyKey);
            state = Objects.requireNonNull(state, "state");
            details = immutable(details);
        }

        public static Builder builder(String signal, String correlationId, String idempotencyKey, CommandState state) {
            return new Builder(signal, correlationId, idempotencyKey, state);
        }

        public static final class Builder {

            private final String signal;
            private final String correlationId;
            private final String idempotencyKey;
            private final CommandState state;
            private String result;
            private Instant timestamp;
            private Map<String, Object> details = Map.of();

            private Builder(String signal, String correlationId, String idempotencyKey, CommandState state) {
                this.signal = requireNonBlank("signal", signal);
                this.correlationId = requireNonBlank("correlationId", correlationId);
                this.idempotencyKey = requireNonBlank("idempotencyKey", idempotencyKey);
                this.state = Objects.requireNonNull(state, "state");
            }

            public Builder result(String result) {
                this.result = result;
                return this;
            }

            public Builder timestamp(Instant timestamp) {
                this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
                return this;
            }

            public Builder details(Map<String, Object> details) {
                this.details = immutable(details);
                return this;
            }

            public ReadyContext build() {
                return new ReadyContext(signal, correlationId, idempotencyKey, state, result, timestamp, details);
            }
        }
    }

    public record ErrorContext(String signal,
                               String correlationId,
                               String idempotencyKey,
                               CommandState state,
                               String phase,
                               String code,
                               String message,
                               Boolean retryable,
                               String result,
                               Instant timestamp,
                               Map<String, Object> details) {

        public ErrorContext {
            signal = requireNonBlank("signal", signal);
            correlationId = requireNonBlank("correlationId", correlationId);
            idempotencyKey = requireNonBlank("idempotencyKey", idempotencyKey);
            state = Objects.requireNonNull(state, "state");
            phase = requireNonBlank("phase", phase);
            code = requireNonBlank("code", code);
            message = requireNonBlank("message", message);
            details = immutable(details);
        }

        public static Builder builder(String signal,
                                      String correlationId,
                                      String idempotencyKey,
                                      CommandState state,
                                      String phase,
                                      String code,
                                      String message) {
            return new Builder(signal, correlationId, idempotencyKey, state, phase, code, message);
        }

        public static final class Builder {

            private final String signal;
            private final String correlationId;
            private final String idempotencyKey;
            private final CommandState state;
            private final String phase;
            private final String code;
            private final String message;
            private Boolean retryable;
            private String result;
            private Instant timestamp;
            private Map<String, Object> details = Map.of();

            private Builder(String signal,
                            String correlationId,
                            String idempotencyKey,
                            CommandState state,
                            String phase,
                            String code,
                            String message) {
                this.signal = requireNonBlank("signal", signal);
                this.correlationId = requireNonBlank("correlationId", correlationId);
                this.idempotencyKey = requireNonBlank("idempotencyKey", idempotencyKey);
                this.state = Objects.requireNonNull(state, "state");
                this.phase = requireNonBlank("phase", phase);
                this.code = requireNonBlank("code", code);
                this.message = requireNonBlank("message", message);
            }

            public Builder retryable(Boolean retryable) {
                this.retryable = Objects.requireNonNull(retryable, "retryable");
                return this;
            }

            public Builder result(String result) {
                this.result = result;
                return this;
            }

            public Builder timestamp(Instant timestamp) {
                this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
                return this;
            }

            public Builder details(Map<String, Object> details) {
                this.details = immutable(details);
                return this;
            }

            public ErrorContext build() {
                return new ErrorContext(signal, correlationId, idempotencyKey, state, phase, code, message,
                    retryable, result, timestamp, details);
            }
        }
    }

    public record StatusContext(Consumer<StatusEnvelopeBuilder> customiser) {

        public StatusContext {
            customiser = Objects.requireNonNull(customiser, "customiser");
        }

        public static StatusContext of(Consumer<StatusEnvelopeBuilder> customiser) {
            return new StatusContext(customiser);
        }
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(source);
    }
}
