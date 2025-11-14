package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.CommandState;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
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
            signal.signal(),
            signal.role(),
            signal.instance(),
            formatEnabledChange(previousEnabled, finalEnabled),
            prettyChanges,
            prettyFinal
        );
    }

    void emitConfigReady(
        ControlSignal signal,
        WorkerState state,
        Map<String, Object> rawConfig,
        Boolean enabled
    ) {
        Map<String, Object> commandDetails = new LinkedHashMap<>();
        if (!rawConfig.isEmpty()) {
            commandDetails.put("config", rawConfig);
        }
        CommandState commandState = new CommandState(
            "applied",
            enabled,
            commandDetails.isEmpty() ? null : commandDetails
        );
        Map<String, Object> confirmationDetails = new LinkedHashMap<>();
        confirmationDetails.put("worker", state.definition().beanName());
        Map<String, Object> statusData = state.statusData();
        if (!statusData.isEmpty()) {
            confirmationDetails.put("data", statusData);
        }
        if (!rawConfig.isEmpty()) {
            confirmationDetails.put("config", rawConfig);
        }
        ControlPlaneEmitter.ReadyContext.Builder ready = ControlPlaneEmitter.ReadyContext.builder(
            signal.signal(),
            signal.correlationId(),
            signal.idempotencyKey(),
            commandState
        );
        if (!confirmationDetails.isEmpty()) {
            ready.details(confirmationDetails);
        }
        emitter.emitReady(ready.build());
    }

    void emitConfigError(ControlSignal signal, WorkerState state, Exception error) {
        String code = error.getClass().getSimpleName();
        String message = error.getMessage() == null || error.getMessage().isBlank() ? code : error.getMessage();
        CommandState commandState = new CommandState("failed", state.enabled(), null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("worker", state.definition().beanName());
        details.put("exception", code);
        Map<String, Object> statusData = state.statusData();
        if (!statusData.isEmpty()) {
            details.put("data", statusData);
        }
        ControlPlaneEmitter.ErrorContext.Builder builder = ControlPlaneEmitter.ErrorContext.builder(
            signal.signal(),
            signal.correlationId(),
            signal.idempotencyKey(),
            commandState,
            "apply",
            code,
            message
        ).retryable(Boolean.FALSE);
        builder.details(details);
        emitter.emitError(builder.build());
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
