package io.pockethive.controlplane.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.pockethive.control.CommandState;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Factory that materialises ready and error confirmations with shared scope metadata.
 */
public final class ConfirmationPayloadFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ScopeContext context;
    private final Clock clock;

    public ConfirmationPayloadFactory(ScopeContext context) {
        this(context, Clock.systemUTC());
    }

    ConfirmationPayloadFactory(ScopeContext context, Clock clock) {
        this.context = Objects.requireNonNull(context, "context");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReadyBuilder ready(String signal) {
        return new ReadyBuilder(signal);
    }

    public ErrorBuilder error(String signal) {
        return new ErrorBuilder(signal);
    }

    private abstract class AbstractBuilder<T extends AbstractBuilder<T>> {

        private final String signal;
        private String correlationId;
        private String idempotencyKey;
        private CommandState state;
        private Instant timestamp;

        private AbstractBuilder(String signal) {
            this.signal = requireNonBlank("signal", signal);
        }

        public T correlationId(String correlationId) {
            this.correlationId = requireNonBlank("correlationId", correlationId);
            return self();
        }

        public T idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = requireNonBlank("idempotencyKey", idempotencyKey);
            return self();
        }

        public T state(CommandState state) {
            this.state = Objects.requireNonNull(state, "state");
            return self();
        }

        public T timestamp(Instant timestamp) {
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            return self();
        }

        protected Instant resolveTimestamp() {
            return timestamp != null ? timestamp : clock.instant();
        }

        protected String signal() {
            return signal;
        }

        protected String correlationId() {
            if (correlationId == null) {
                throw new IllegalStateException("correlationId must be provided");
            }
            return correlationId;
        }

        protected String idempotencyKey() {
            if (idempotencyKey == null) {
                throw new IllegalStateException("idempotencyKey must be provided");
            }
            return idempotencyKey;
        }

        protected CommandState state() {
            if (state == null) {
                throw new IllegalStateException("state must be provided");
            }
            return state;
        }

        protected ScopeContext context() {
            return context;
        }

        protected abstract T self();
    }

    public final class ReadyBuilder extends AbstractBuilder<ReadyBuilder> {

        private String result;
        private Map<String, Object> details = Collections.emptyMap();

        private ReadyBuilder(String signal) {
            super(signal);
        }

        public ReadyBuilder result(String result) {
            this.result = result;
            return this;
        }

        public ReadyBuilder details(Map<String, Object> details) {
            this.details = Objects.requireNonNull(details, "details");
            return this;
        }

        public String build() {
            ReadyConfirmation confirmation = new ReadyConfirmation(
                resolveTimestamp(),
                correlationId(),
                idempotencyKey(),
                signal(),
                context().scope(),
                result,
                state(),
                details.isEmpty() ? null : details
            );
            return toJson(confirmation);
        }

        @Override
        protected ReadyBuilder self() {
            return this;
        }
    }

    public final class ErrorBuilder extends AbstractBuilder<ErrorBuilder> {

        private String result;
        private String phase;
        private String code;
        private String message;
        private Boolean retryable;
        private Map<String, Object> details = Collections.emptyMap();

        private ErrorBuilder(String signal) {
            super(signal);
        }

        public ErrorBuilder result(String result) {
            this.result = result;
            return this;
        }

        public ErrorBuilder phase(String phase) {
            this.phase = requireNonBlank("phase", phase);
            return this;
        }

        public ErrorBuilder code(String code) {
            this.code = requireNonBlank("code", code);
            return this;
        }

        public ErrorBuilder message(String message) {
            this.message = requireNonBlank("message", message);
            return this;
        }

        public ErrorBuilder retryable(Boolean retryable) {
            this.retryable = Objects.requireNonNull(retryable, "retryable");
            return this;
        }

        public ErrorBuilder details(Map<String, Object> details) {
            this.details = Objects.requireNonNull(details, "details");
            return this;
        }

        public String build() {
            if (phase == null) {
                throw new IllegalStateException("phase must be provided");
            }
            if (code == null) {
                throw new IllegalStateException("code must be provided");
            }
            if (message == null) {
                throw new IllegalStateException("message must be provided");
            }
            ErrorConfirmation confirmation = new ErrorConfirmation(
                resolveTimestamp(),
                correlationId(),
                idempotencyKey(),
                signal(),
                context().scope(),
                result,
                state(),
                phase,
                code,
                message,
                retryable,
                details.isEmpty() ? null : details
            );
            return toJson(confirmation);
        }

        @Override
        protected ErrorBuilder self() {
            return this;
        }
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise confirmation", e);
        }
    }
}
