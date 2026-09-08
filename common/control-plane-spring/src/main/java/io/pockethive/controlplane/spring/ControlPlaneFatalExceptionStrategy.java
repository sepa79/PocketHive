package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

/**
 * Responsibility: classify fatal Control Plane decoding failures.
 * Must not: retry malformed envelopes or decide Work delivery policy.
 * Contract: RESP-CP-LISTENER-POLICY — docs/architecture/runtime-responsibilities.md#resp-cp-listener-policy.
 */
final class ControlPlaneFatalExceptionStrategy extends ConditionalRejectingErrorHandler.DefaultExceptionStrategy {

    @Override
    public boolean isFatal(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable candidate = unwrapListenerException(throwable);
        if (findCause(candidate, JsonProcessingException.class).isPresent()) {
            return true;
        }
        if (findCause(candidate, IllegalArgumentException.class).isPresent()) {
            return true;
        }
        return super.isFatal(throwable);
    }

    private static Throwable unwrapListenerException(Throwable throwable) {
        if (throwable instanceof ListenerExecutionFailedException failed && failed.getCause() != null) {
            return failed.getCause();
        }
        return throwable;
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
