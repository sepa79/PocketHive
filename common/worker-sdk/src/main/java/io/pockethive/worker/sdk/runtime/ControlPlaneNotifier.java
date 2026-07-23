package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.slf4j.Logger;

final class ControlPlaneNotifier {

    private final Logger log;
    private final ObjectMapper objectMapper;
    private final ControlPlaneEmitter emitter;
    private final String role;
    private final String instanceId;

    ControlPlaneNotifier(
        Logger log,
        ObjectMapper objectMapper,
        ControlPlaneEmitter emitter,
        String role,
        String instanceId
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.role = Objects.requireNonNull(role, "role");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    void logInitialConfig(WorkerState state, Map<String, Object> config, Boolean enabled) {
        String prettyConfig = prettyPrint(config.isEmpty() ? Map.of() : config);
        log.info(
            "Initial config for worker {} (role={} instance={}):\n  enabled: {}\n  config:\n{}",
            state.definition().beanName(),
            role,
            instanceId,
            formatEnabledValue(enabled),
            prettyConfig
        );
    }

    void logConfigUpdate(
        ControlSignal signal,
        WorkerState state,
        Map<String, Object> diff,
        Map<String, Object> finalConfig,
        Boolean previousEnabled,
        Boolean finalEnabled
    ) {
        String prettyChanges = prettyPrint(diff);
        String prettyFinal = prettyPrint(finalConfig.isEmpty() ? Map.of() : finalConfig);
        log.info(
            "Applied config update for worker {} (signal={} role={} instance={}):\n  enabled: {}\n  changes:\n{}\n  finalConfig:\n{}",
            state.definition().beanName(),
            signal.type(),
            signal.scope() != null ? signal.scope().role() : null,
            signal.scope() != null ? signal.scope().instance() : null,
            formatEnabledChange(previousEnabled, finalEnabled),
            prettyChanges,
            prettyFinal
        );
    }

    void emitConfigReady(
        ControlSignal signal,
        WorkerState state,
        Map<String, Object> rawConfig
    ) {
        Boolean enabled = state.enabled();
        Map<String, Object> confirmationDetails = new LinkedHashMap<>();
        confirmationDetails.put("target", target(signal));
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
        details.put("target", target(signal));
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

    private Target target(ControlSignal signal) {
        if (signal.scope() == null) {
            throw new IllegalArgumentException("config-update scope is required");
        }
        return new Target(signal.scope().role(), signal.scope().instance());
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

    private String prettyPrint(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to pretty print config value", ex);
            return String.valueOf(value);
        }
    }

    private String formatEnabledChange(Boolean previousEnabled, Boolean finalEnabled) {
        if (Objects.equals(previousEnabled, finalEnabled)) {
            return formatEnabledValue(finalEnabled) + " (unchanged)";
        }
        return formatEnabledValue(finalEnabled) + " (was " + formatEnabledValue(previousEnabled) + ")";
    }

    private String formatEnabledValue(Boolean value) {
        return value == null ? "unspecified" : value.toString();
    }
}
