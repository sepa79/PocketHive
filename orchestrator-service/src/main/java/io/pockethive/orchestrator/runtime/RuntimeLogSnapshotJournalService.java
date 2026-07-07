package io.pockethive.orchestrator.runtime;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.ControlScope;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.config.RuntimeLogSnapshotMode;
import io.pockethive.orchestrator.config.RuntimeLogSnapshotProperties;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RuntimeLogSnapshotJournalService {
    public static final String JOURNAL_KIND = "runtime-debug";
    public static final String JOURNAL_TYPE_CAPTURED = "runtime-log-snapshot";
    public static final String JOURNAL_TYPE_UNAVAILABLE = "runtime-log-snapshot-unavailable";

    private static final Logger log = LoggerFactory.getLogger(RuntimeLogSnapshotJournalService.class);
    private static final String ORIGIN = "orchestrator";
    private static final String LEVEL_ERROR = "error";
    private static final String RUNTIME_RUN_ID = "runId";
    private static final String STATUS_CAPTURED = "captured";
    private static final String STATUS_UNAVAILABLE = "unavailable";

    private final RuntimeDebugService runtimeDebug;
    private final HiveJournal hiveJournal;
    private final RuntimeLogSnapshotProperties properties;

    public RuntimeLogSnapshotJournalService(RuntimeDebugService runtimeDebug,
                                            HiveJournal hiveJournal,
                                            RuntimeLogSnapshotProperties properties) {
        this.runtimeDebug = Objects.requireNonNull(runtimeDebug, "runtimeDebug");
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void captureForAlert(String routingKey, AlertMessage alert) {
        Objects.requireNonNull(alert, "alert");
        if (properties.getMode() == RuntimeLogSnapshotMode.DISABLED) {
            return;
        }
        if (properties.getMode() != RuntimeLogSnapshotMode.ERROR_ALERTS) {
            log.warn("Unsupported runtime log snapshot mode {}; skipping alert snapshot", properties.getMode());
            return;
        }
        if (!isError(alert)) {
            return;
        }

        ControlScope scope = alert.scope();
        String swarmId = text(scope.swarmId());
        if (swarmId == null || ControlScope.isAll(swarmId)) {
            log.debug("Skipping runtime log snapshot for alert without concrete swarm scope; routingKey={}", routingKey);
            return;
        }

        RuntimeLogsRequest request = request(alert);
        if (request == null) {
            appendUnavailable(swarmId, routingKey, alert, "alert scope does not identify a runtime target", null);
            return;
        }

        try {
            RuntimeLogsResponse response = runtimeDebug.logs(request);
            appendCaptured(swarmId, routingKey, alert, response);
        } catch (Exception ex) {
            appendUnavailable(swarmId, routingKey, alert, failureReason(ex), ex);
        }
    }

    private RuntimeLogsRequest request(AlertMessage alert) {
        ControlScope scope = alert.scope();
        String role = text(scope.role());
        String instance = text(scope.instance());
        if (role == null || ControlScope.isAll(role) || instance == null || ControlScope.isAll(instance)) {
            return null;
        }
        String since = alert.timestamp()
            .minus(properties.getSinceBeforeAlert())
            .toString();
        return new RuntimeLogsRequest(
            text(scope.swarmId()),
            runId(alert.runtime()),
            null,
            instance,
            role,
            resourceKind(role),
            properties.getTailLines(),
            since);
    }

    private void appendCaptured(String swarmId,
                                String routingKey,
                                AlertMessage alert,
                                RuntimeLogsResponse response) {
        TrimmedLog trimmed = trim(response.logs());
        Map<String, Object> data = baseData(alert, STATUS_CAPTURED);
        data.put("targetRuntimeId", response.target().runtimeId());
        data.put("targetRuntimeType", response.target().runtimeType());
        data.put("targetName", response.target().name());
        data.put("resourceKind", response.target().resourceKind());
        data.put("role", response.target().role());
        data.put("instance", response.target().instance());
        data.put("runId", response.target().runId());
        data.put("tailLines", response.tailLines());
        data.put("since", response.since());
        data.put("lineCount", response.lineCount());
        data.put("redacted", response.redacted());
        data.put("truncated", trimmed.truncated());
        data.put("omittedChars", trimmed.omittedChars());
        append(new HiveJournalEntry(
            Instant.now(),
            swarmId,
            "ERROR",
            HiveJournal.Direction.LOCAL,
            JOURNAL_KIND,
            JOURNAL_TYPE_CAPTURED,
            ORIGIN,
            alert.scope(),
            alert.correlationId(),
            alert.idempotencyKey(),
            routingKey,
            data,
            null,
            Map.of("logs", trimmed.value())));
    }

    private void appendUnavailable(String swarmId,
                                   String routingKey,
                                   AlertMessage alert,
                                   String reason,
                                   Exception exception) {
        Map<String, Object> data = baseData(alert, STATUS_UNAVAILABLE);
        data.put("reason", reason);
        if (exception != null) {
            data.put("exceptionType", exception.getClass().getName());
        }
        append(new HiveJournalEntry(
            Instant.now(),
            swarmId,
            "ERROR",
            HiveJournal.Direction.LOCAL,
            JOURNAL_KIND,
            JOURNAL_TYPE_UNAVAILABLE,
            ORIGIN,
            alert.scope(),
            alert.correlationId(),
            alert.idempotencyKey(),
            routingKey,
            data,
            null,
            null));
    }

    private void append(HiveJournalEntry entry) {
        try {
            hiveJournal.append(entry);
        } catch (Exception ex) {
            log.warn("Failed to append runtime log snapshot journal entry; swarmId={} type={}",
                entry.swarmId(), entry.type(), ex);
        }
    }

    private Map<String, Object> baseData(AlertMessage alert, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("alertLevel", alert.data().level());
        data.put("alertCode", alert.data().code());
        data.put("alertMessage", alert.data().message());
        data.put("alertOrigin", alert.origin());
        data.put("alertTimestamp", alert.timestamp().toString());
        data.put("logRef", alert.data().logRef());
        data.put("configuredMode", properties.getMode().name());
        return data;
    }

    private TrimmedLog trim(String value) {
        String logs = value == null ? "" : value;
        int maxChars = properties.getMaxChars();
        if (logs.length() <= maxChars) {
            return new TrimmedLog(logs, false, 0);
        }
        int omitted = logs.length() - maxChars;
        return new TrimmedLog(logs.substring(omitted), true, omitted);
    }

    private static boolean isError(AlertMessage alert) {
        return alert.data() != null && LEVEL_ERROR.equalsIgnoreCase(text(alert.data().level()));
    }

    private static String resourceKind(String role) {
        return PocketHiveDockerLabels.OWNER_SWARM_CONTROLLER.equals(role)
            ? PocketHiveDockerLabels.RESOURCE_KIND_MANAGER
            : PocketHiveDockerLabels.RESOURCE_KIND_WORKER;
    }

    private static String runId(Map<String, Object> runtime) {
        if (runtime == null) {
            return null;
        }
        Object value = runtime.get(RUNTIME_RUN_ID);
        return value == null ? null : text(String.valueOf(value));
    }

    private static String failureReason(Exception exception) {
        String message = text(exception.getMessage());
        if (message == null) {
            return exception.getClass().getName();
        }
        return exception.getClass().getName() + ": " + message;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record TrimmedLog(String value, boolean truncated, int omittedChars) {
    }
}
