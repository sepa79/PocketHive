package io.pockethive.controlplane.messaging;

import io.pockethive.controlplane.ControlPlaneSignals;
import java.util.Map;
import java.util.Objects;

/**
 * Centralized outcome policy for status + retryable mappings.
 *
 * <p>No implicit defaults: every supported signal must be mapped explicitly.</p>
 */
public final class CommandOutcomePolicy {

    public static final String STATUS_NOT_READY = "NotReady";

    public enum RetryablePolicy {
        TRUE,
        FALSE
    }

    public record OutcomeRules(String successStatus,
                               String errorStatus,
                               RetryablePolicy errorRetryableDefault) {
        public OutcomeRules {
            successStatus = requireNonBlank("successStatus", successStatus);
            errorStatus = requireNonBlank("errorStatus", errorStatus);
            errorRetryableDefault = Objects.requireNonNull(errorRetryableDefault, "errorRetryableDefault");
        }
    }

    private static final Map<String, OutcomeRules> RULES = Map.of(
        ControlPlaneSignals.SWARM_CREATE, new OutcomeRules("Ready", "Failed", RetryablePolicy.TRUE),
        ControlPlaneSignals.SWARM_TEMPLATE, new OutcomeRules("Ready", "Failed", RetryablePolicy.FALSE),
        ControlPlaneSignals.SWARM_PLAN, new OutcomeRules("Ready", "Failed", RetryablePolicy.FALSE),
        ControlPlaneSignals.SWARM_START, new OutcomeRules("Running", "Failed", RetryablePolicy.TRUE),
        ControlPlaneSignals.SWARM_STOP, new OutcomeRules("Stopped", "Failed", RetryablePolicy.TRUE),
        ControlPlaneSignals.SWARM_REMOVE, new OutcomeRules("Removed", "Failed", RetryablePolicy.TRUE),
        ControlPlaneSignals.CONFIG_UPDATE, new OutcomeRules("Applied", "Failed", RetryablePolicy.FALSE),
        ControlPlaneSignals.WORK_JOURNAL, new OutcomeRules("recorded", "failed", RetryablePolicy.FALSE)
    );

    private CommandOutcomePolicy() {
    }

    public static OutcomeRules rulesFor(String signal) {
        String key = requireNonBlank("signal", signal);
        OutcomeRules rules = RULES.get(key);
        if (rules == null) {
            throw new IllegalArgumentException("No outcome policy defined for signal: " + key);
        }
        return rules;
    }

    public static String resolveSuccessStatus(String signal, String overrideStatus) {
        if (overrideStatus != null) {
            if (!STATUS_NOT_READY.equals(overrideStatus)) {
                throw new IllegalArgumentException(
                    "Unsupported status override for signal " + signal + ": " + overrideStatus);
            }
            return STATUS_NOT_READY;
        }
        return rulesFor(signal).successStatus();
    }

    public static boolean isNotReadyStatus(String status) {
        return STATUS_NOT_READY.equals(status);
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
