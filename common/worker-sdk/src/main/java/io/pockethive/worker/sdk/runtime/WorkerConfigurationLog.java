package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.work.config.projection.WorkConfigurationRedactor;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Responsibility: log worker configuration updates using the shared diagnostic projection.
 * Must not: log raw configuration, decide acceptance or publish control outcomes.
 * Contract: RESP-WORK-CONFIGURATION-DIAGNOSTICS — docs/architecture/runtime-responsibilities.md#resp-work-configuration-diagnostics.
 */
final class WorkerConfigurationLog {
    private final Logger log;
    private final ObjectMapper mapper;

    WorkerConfigurationLog(Logger log, ObjectMapper mapper) {
        this.log = Objects.requireNonNull(log, "log");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    void applying(WorkerState state, Boolean requestedEnabled, Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Applying config-update for worker={} role={} previousEnabled={} requestedEnabled={} data={}",
                state.definition().beanName(), state.definition().role(), state.enabled(), requestedEnabled,
                WorkConfigurationRedactor.redact(config));
        }
    }

    void applied(ControlSignal signal, WorkerState state, Map<String, Object> diff,
                 Map<String, Object> finalConfig, Boolean previousEnabled, Boolean finalEnabled) {
        String enabled = Objects.equals(previousEnabled, finalEnabled)
            ? formatEnabled(finalEnabled) + " (unchanged)"
            : formatEnabled(finalEnabled) + " (was " + formatEnabled(previousEnabled) + ")";
        log.info("Applied config update for worker {} (signal={} role={} instance={}):\n  enabled: {}\n  changes:\n{}\n  finalConfig:\n{}",
            state.definition().beanName(), signal.type(),
            signal.scope() != null ? signal.scope().role() : null,
            signal.scope() != null ? signal.scope().instance() : null,
            enabled, prettyPrint(diff), prettyPrint(finalConfig));
    }

    private String prettyPrint(Map<String, Object> config) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(WorkConfigurationRedactor.redact(config));
        } catch (JsonProcessingException error) {
            log.warn("Unable to serialize redacted worker configuration");
            return "[configuration serialization failed]";
        }
    }

    private static String formatEnabled(Boolean value) {
        return value == null ? "unspecified" : value.toString();
    }
}
