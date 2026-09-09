package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.CanonicalPayloadDigest;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: publish control results derived from accepted worker configuration or its rejection.
 * Must not: apply configuration, log raw configuration or replace accepted values with diagnostic projections.
 * Contract: RESP-WORK-STATE — docs/architecture/runtime-responsibilities.md#resp-work-state.
 */
final class ControlPlaneNotifier {

    private final ObjectMapper objectMapper;
    private final ControlPlaneEmitter emitter;
    private final String role;
    private final String instanceId;

    ControlPlaneNotifier(
        ObjectMapper objectMapper,
        ControlPlaneEmitter emitter,
        String role,
        String instanceId
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.role = Objects.requireNonNull(role, "role");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    void emitConfigReady(
        ControlSignal signal,
        WorkerState state,
        Map<String, Object> rawConfig
    ) {
        Boolean enabled = state.enabled();
        Map<String, Object> confirmationDetails = new LinkedHashMap<>();
        confirmationDetails.put("target", target());
        confirmationDetails.put("requestedEnabled", requestedEnabled(signal));
        confirmationDetails.put("observedEnabled", enabled);
        confirmationDetails.put("appliedConfigSha256", CanonicalPayloadDigest.sha256(objectMapper, rawConfig));
        emitter.emitResult(new ControlPlaneEmitter.ResultContext(
            signal.type(),
            signal.correlationId(),
            signal.idempotencyKey(),
            new TerminalResult(TerminalStatus.SUCCEEDED, false, confirmationDetails),
            null));
    }

    void emitConfigError(ControlSignal signal, WorkerState state, Exception error) {
        String code = error.getClass().getSimpleName();
        String message = error.getMessage() == null || error.getMessage().isBlank() ? code : error.getMessage();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("target", target());
        details.put("requestedEnabled", requestedEnabled(signal));
        details.put("observedEnabled", state.enabled());
        details.put("appliedConfigSha256", null);
        emitter.emitFailure(new ControlPlaneEmitter.FailureContext(
            signal.type(),
            signal.correlationId(),
            signal.idempotencyKey(),
            new TerminalResult(TerminalStatus.FAILED, false, details),
            "apply",
            code,
            message,
            error.getClass().getName(),
            error.getMessage(),
            null,
            null));
    }

    private Target target() {
        return new Target(role, instanceId);
    }

    private static Boolean requestedEnabled(ControlSignal signal) {
        Object value = signal.data().get("enabled");
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        throw new IllegalArgumentException("config-update enabled must be boolean");
    }

}
