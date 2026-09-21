package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Responsibility: classify fatal Control Plane decoding failures.
 * Must not: retry malformed envelopes or decide Work delivery policy.
 * Contract: RESP-CP-LISTENER-POLICY — docs/architecture/runtime-responsibilities.md#resp-cp-listener-policy.
 */
final class ControlPlaneFatalExceptionStrategy implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable candidate = throwable;
        if (findCause(candidate, JsonProcessingException.class).isPresent()) {
            return true;
        }
        if (findCause(candidate, IllegalArgumentException.class).isPresent()) {
            return true;
        }
        return false;
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
