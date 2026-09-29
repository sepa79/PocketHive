package io.pockethive.orchestrator.app;

import io.pockethive.journal.api.JournalPageResponse;
import io.pockethive.journal.api.JournalRunSummary;
import io.pockethive.journal.api.PinRunRequest;
import io.pockethive.journal.api.PinRunResponse;
import io.pockethive.journal.api.JournalCaptureConflictException;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: authorize and expose swarm journal HTTP operations; delegate journal queries and pinning.
 * Must not: execute SQL, construct capture outcomes, reconstruct paths or mutate swarm state.
 * Contract: RESP-SWARM-FILE-JOURNAL — docs/architecture/runtime-responsibilities.md#resp-swarm-file-journal;
 * RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 * RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes;
 * wire contract: docs/ORCHESTRATOR-REST.md.
 */
@RestController
@RequestMapping("/api/swarms")
public class SwarmJournalController {
    private static final Logger log = LoggerFactory.getLogger(SwarmJournalController.class);

    private final SwarmJournalPinning pinning;
    private final OrchestratorEndpointAuthorization endpointAuthorization;
    private final SwarmFileJournalQuery fileQuery;
    private final SwarmStoredJournalQuery storedQuery;

    @Value("${pockethive.journal.sink:postgres}")
    private String journalSink;

    public SwarmJournalController(SwarmJournalPinning pinning,
                                  OrchestratorEndpointAuthorization endpointAuthorization,
                                  SwarmFileJournalQuery fileQuery,
                                  SwarmStoredJournalQuery storedQuery) {
        this.pinning = pinning;
        this.endpointAuthorization = endpointAuthorization;
        this.fileQuery = fileQuery;
        this.storedQuery = storedQuery;
    }

    /**
     * GET {@code /api/swarms/{swarmId}/journal} — return swarm-level journal events when available.
     * <p>
     * This projects the per-swarm journal file into a simple JSON array so Hive UI can render
     * a debug timeline without introducing new control-plane contracts or touching the swarm-controller.
     */
    @GetMapping("/{swarmId}/journal")
    public ResponseEntity<List<Map<String, Object>>> journal(@PathVariable String swarmId,
                                                             @RequestParam(required = false) String runId,
                                                             @RequestParam(required = false) String severity) {
        String path = "/api/swarms/" + swarmId + "/journal";
        logRestRequest("GET", path, null);
        endpointAuthorization.requireReadSwarm(swarmId);
        String severityFilter;
        try {
            severityFilter = normalizeSeverityFilter(severity);
        } catch (IllegalArgumentException ex) {
            ResponseEntity<List<Map<String, Object>>> response = ResponseEntity.badRequest().build();
            logRestResponse("GET", path, response);
            return response;
        }
        ResponseEntity<List<Map<String, Object>>> response;
        try {
            List<Map<String, Object>> entries = readJournalEntries(swarmId, runId, severityFilter);
            if (entries == null) {
                response = ResponseEntity.notFound().build();
            } else {
                response = ResponseEntity.ok(entries);
            }
        } catch (Exception ex) {
            log.warn("Failed to read journal for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}/journal/page} — paginated swarm-level journal query (Postgres only).
     * <p>
     * Results are returned newest-first (descending by {@code (ts, id)}). Use {@code nextCursor} as the
     * {@code beforeTs}/{@code beforeId} pair for the next page (older entries).
     */
    @GetMapping("/{swarmId}/journal/page")
    public ResponseEntity<JournalPageResponse> journalPage(@PathVariable String swarmId,
                                                           @RequestParam(required = false)
                                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                           Instant beforeTs,
                                                           @RequestParam(required = false) Long beforeId,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) String runId,
                                                           @RequestParam(required = false) String correlationId,
                                                           @RequestParam(required = false) String severity) {
        String path = "/api/swarms/" + swarmId + "/journal/page";
        logRestRequest("GET", path, null);
        endpointAuthorization.requireReadSwarm(swarmId);
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            ResponseEntity<JournalPageResponse> response = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            logRestResponse("GET", path, response);
            return response;
        }
        if ((beforeTs == null) != (beforeId == null)) {
            ResponseEntity<JournalPageResponse> response = ResponseEntity.badRequest().build();
            logRestResponse("GET", path, response);
            return response;
        }
        String severityFilter;
        try {
            severityFilter = normalizeSeverityFilter(severity);
        } catch (IllegalArgumentException ex) {
            ResponseEntity<JournalPageResponse> response = ResponseEntity.badRequest().build();
            logRestResponse("GET", path, response);
            return response;
        }
        int pageSize = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
        String corr = correlationId == null ? null : correlationId.trim();
        if (corr != null && corr.isBlank()) {
            corr = null;
        }
        ResponseEntity<JournalPageResponse> response;
        try {
            JournalPageResponse page = storedQuery.page(swarmId, runId, corr, severityFilter, beforeTs, beforeId, pageSize);
            if (page == null) {
                response = ResponseEntity.notFound().build();
            } else {
                response = ResponseEntity.ok(page);
            }
        } catch (Exception ex) {
            log.warn("Failed to query journal page for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}/journal/runs} — list known journal runs for a swarm (Postgres only).
     * <p>
     * Runs are returned newest-first (by last entry time).
     */
    @GetMapping("/{swarmId}/journal/runs")
    public ResponseEntity<List<JournalRunSummary>> journalRuns(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId + "/journal/runs";
        logRestRequest("GET", path, null);
        endpointAuthorization.requireReadSwarm(swarmId);
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            ResponseEntity<List<JournalRunSummary>> response = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            logRestResponse("GET", path, response);
            return response;
        }
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            ResponseEntity<List<JournalRunSummary>> response = ResponseEntity.notFound().build();
            logRestResponse("GET", path, response);
            return response;
        }
        ResponseEntity<List<JournalRunSummary>> response;
        try {
            List<JournalRunSummary> runs = storedQuery.runs(cleanedId);
            if (runs == null) {
                response = ResponseEntity.notFound().build();
            } else {
                response = ResponseEntity.ok(List.copyOf(runs));
            }
        } catch (Exception ex) {
            log.warn("Failed to list journal runs for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("GET", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/journal/pin")
    public ResponseEntity<PinRunResponse> pinSwarmJournalRun(@PathVariable String swarmId,
                                                             @RequestBody(required = false) PinRunRequest request) {
        String path = "/api/swarms/" + swarmId + "/journal/pin";
        logRestRequest("POST", path, request);
        endpointAuthorization.requireManageSwarm(swarmId);
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            ResponseEntity<PinRunResponse> response = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            logRestResponse("POST", path, response);
            return response;
        }
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            ResponseEntity<PinRunResponse> response = ResponseEntity.notFound().build();
            logRestResponse("POST", path, response);
            return response;
        }

        ResponseEntity<PinRunResponse> response;
        try {
            PinRunResponse result = pinning.pin(cleanedId, request);
            response = result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
        } catch (JournalCaptureConflictException conflict) {
            response = ResponseEntity.status(HttpStatus.CONFLICT).body(conflict.response());
        } catch (Exception ex) {
            log.warn("Failed to pin journal run for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("POST", path, response);
        return response;
    }

    private List<Map<String, Object>> readJournalEntries(String swarmId, String requestedRunId, String severityFilter) {
        if ("postgres".equalsIgnoreCase(journalSink)) {
            return storedQuery.timeline(swarmId, requestedRunId, severityFilter);
        }
        return fileQuery.read(swarmId, requestedRunId, severityFilter);
    }

    private static String sanitizeSegment(String id) {
        try {
            return RuntimeFilesystemLayout.requireSegment(id, "swarmId");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String normalizeSeverityFilter(String severity) {
        if (severity == null) {
            return null;
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        return switch (normalized) {
            case "ERROR", "WARN", "INFO" -> normalized;
            default -> throw new IllegalArgumentException("severity must be one of ERROR, WARN, INFO");
        };
    }

    private void logRestRequest(String method, String path, Object body) {
        log.info("[REST] {} {}", method, path);
        if (log.isDebugEnabled()) {
            log.debug("[REST] {} {} request={}", method, path, toSafeString(body));
        }
    }

    private void logRestResponse(String method, String path, ResponseEntity<?> response) {
        if (response == null) {
            return;
        }
        log.info("[REST] {} {} -> status={}", method, path, response.getStatusCode());
        if (log.isDebugEnabled()) {
            log.debug("[REST] {} {} -> status={} body={}", method, path, response.getStatusCode(), toSafeString(response.getBody()));
        }
    }

    private static String toSafeString(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.length() > 300) {
            return text.substring(0, 300) + "…";
        }
        return text;
    }
}
