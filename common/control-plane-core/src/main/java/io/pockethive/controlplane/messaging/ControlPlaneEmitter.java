package io.pockethive.controlplane.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandState;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.payload.StatusPayloadFactory;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.WorkerControlPlaneTopologyDescriptor;
import io.pockethive.observability.StatusEnvelopeBuilder;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final StatusPayloadFactory statusFactory;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ControlPlaneEmitter(ControlPlaneTopologyDescriptor topology,
                                RoleContext role,
                                ControlPlanePublisher publisher,
                                StatusPayloadFactory statusFactory) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.role = Objects.requireNonNull(role, "role");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.statusFactory = Objects.requireNonNull(statusFactory, "statusFactory");
        requireRoleMatch();
    }

    public static ControlPlaneEmitter using(ControlPlaneTopologyDescriptor topology,
                                            RoleContext role,
                                            ControlPlanePublisher publisher) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(publisher, "publisher");
        StatusPayloadFactory statusFactory = new StatusPayloadFactory(role);
        return new ControlPlaneEmitter(topology, role, publisher, statusFactory);
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
        publishOutcomeFromReady(context);
    }

    public void emitError(ErrorContext context) {
        Objects.requireNonNull(context, "context");
        publishOutcomeFromError(context);
        publishAlertFromError(context);
    }

    public void emitStatusSnapshot(StatusContext context) {
        publishStatus("status-full", context);
    }

    public void emitStatusDelta(StatusContext context) {
        publishStatus("status-delta", context);
    }

    private void publishStatus(String type, StatusContext context) {
        Objects.requireNonNull(context, "context");
        String routingKey = ControlPlaneRouting.event("metric", type, role.toScope());
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
            idempotencyKey = trimToNull(idempotencyKey);
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
                this.idempotencyKey = trimToNull(idempotencyKey);
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
                               String errorType,
                               String errorDetail,
                               String logRef,
                               Boolean retryable,
                               String result,
                               Instant timestamp,
                               Map<String, Object> details) {

        public ErrorContext {
            signal = requireNonBlank("signal", signal);
            correlationId = requireNonBlank("correlationId", correlationId);
            idempotencyKey = trimToNull(idempotencyKey);
            state = Objects.requireNonNull(state, "state");
            phase = requireNonBlank("phase", phase);
            code = requireNonBlank("code", code);
            message = requireNonBlank("message", message);
            errorType = trimToNull(errorType);
            errorDetail = trimToNull(errorDetail);
            logRef = trimToNull(logRef);
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

        public static ErrorContext fromException(String signal,
                                                 String correlationId,
                                                 String idempotencyKey,
                                                 CommandState state,
                                                 String phase,
                                                 Throwable exception,
                                                 Boolean retryable,
                                                 String logRef,
                                                 Map<String, Object> details,
                                                 Instant timestamp) {
            Objects.requireNonNull(exception, "exception");
            String errorType = exception.getClass().getName();
            String errorDetail = trimToNull(exception.getMessage());
            String message = errorDetail != null ? errorDetail : errorType;
            return new ErrorContext(
                signal,
                correlationId,
                idempotencyKey,
                state,
                phase,
                Alerts.Codes.RUNTIME_EXCEPTION,
                message,
                errorType,
                errorDetail,
                logRef,
                retryable,
                null,
                timestamp,
                details
            );
        }

        public static final class Builder {

            private final String signal;
            private final String correlationId;
            private final String idempotencyKey;
            private final CommandState state;
            private final String phase;
            private final String code;
            private final String message;
            private String errorType;
            private String errorDetail;
            private String logRef;
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
                this.idempotencyKey = trimToNull(idempotencyKey);
                this.state = Objects.requireNonNull(state, "state");
                this.phase = requireNonBlank("phase", phase);
                this.code = requireNonBlank("code", code);
                this.message = requireNonBlank("message", message);
            }

            public Builder errorType(String errorType) {
                this.errorType = trimToNull(errorType);
                return this;
            }

            public Builder errorDetail(String errorDetail) {
                this.errorDetail = trimToNull(errorDetail);
                return this;
            }

            public Builder logRef(String logRef) {
                this.logRef = trimToNull(logRef);
                return this;
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
                    errorType, errorDetail, logRef, retryable, result, timestamp, details);
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

    private void publishOutcomeFromReady(ReadyContext context) {
        publishOutcome(context.signal(),
            CommandOutcomes.fromState(
                context.signal(),
                role.instanceId(),
                toControlScope(role.toScope()),
                context.correlationId(),
                context.idempotencyKey(),
                context.state(),
                null,
                context.details(),
                context.timestamp()
            ));
    }

    private void publishOutcomeFromError(ErrorContext context) {
        publishOutcome(context.signal(),
            CommandOutcomes.fromState(
                context.signal(),
                role.instanceId(),
                toControlScope(role.toScope()),
                context.correlationId(),
                context.idempotencyKey(),
                context.state(),
                context.retryable(),
                context.details(),
                context.timestamp()
            ));
    }

    private void publishOutcome(String signal, CommandOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        ConfirmationScope routingScope = toConfirmationScope(outcome.scope());
        String routingKey = ControlPlaneRouting.event("outcome", signal, routingScope);
        publisher.publishEvent(new EventMessage(routingKey, serializeEnvelope(outcome, "outcome")));
    }

    private void publishAlertFromError(ErrorContext context) {
        Map<String, Object> mergedContext = mergeContext(context.state(), context.details());
        Map<String, Object> alertContext = new LinkedHashMap<>();
        alertContext.put("phase", context.phase());
        if (!mergedContext.isEmpty()) {
            alertContext.putAll(mergedContext);
        }
        AlertMessage alert = Alerts.error(
            role.instanceId(),
            toControlScope(role.toScope()),
            context.correlationId(),
            context.idempotencyKey(),
            context.code(),
            context.message(),
            context.errorType(),
            context.errorDetail(),
            context.logRef(),
            alertContext.isEmpty() ? null : alertContext,
            context.timestamp()
        );
        publishAlert(alert);
    }

    private static Map<String, Object> mergeContext(CommandState state, Map<String, Object> extras) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (state.details() != null && !state.details().isEmpty()) {
            merged.putAll(state.details());
        }
        if (extras != null && !extras.isEmpty()) {
            merged.putAll(extras);
        }
        return merged;
    }

    public void publishAlert(AlertMessage alert) {
        Objects.requireNonNull(alert, "alert");
        ConfirmationScope routingScope = toConfirmationScope(alert.scope());
        String routingKey = ControlPlaneRouting.event("alert", "alert", routingScope);
        publisher.publishEvent(new EventMessage(routingKey, serializeEnvelope(alert, "alert")));
    }

    public void emitException(String signal,
                              String correlationId,
                              String idempotencyKey,
                              CommandState state,
                              String phase,
                              Throwable exception,
                              Boolean retryable,
                              String logRef,
                              Map<String, Object> details) {
        Objects.requireNonNull(state, "state");
        ErrorContext context = ErrorContext.fromException(
            signal,
            correlationId,
            idempotencyKey,
            state,
            phase,
            exception,
            retryable,
            logRef,
            details,
            Instant.now()
        );
        emitError(context);
    }

    private static String serializeEnvelope(Object value, String label) {
        try {
            return ENVELOPE_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise " + label + " envelope", e);
        }
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(source);
    }

    private static ConfirmationScope toConfirmationScope(ControlScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new ConfirmationScope(scope.swarmId(), scope.role(), scope.instance());
    }

    private static ControlScope toControlScope(ConfirmationScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new ControlScope(scope.swarmId(), scope.role(), scope.instance());
    }

}
