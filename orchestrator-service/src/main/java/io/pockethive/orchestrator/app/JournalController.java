package io.pockethive.orchestrator.app;

import io.pockethive.journal.api.JournalEventQueries;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.SwarmRunMetadataUpdate;
import io.pockethive.journal.api.SwarmRunSummary;
import io.pockethive.journal.api.JournalPageResponse;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: authorize and expose Hive journal pages and run-management HTTP operations.
 * Must not: execute SQL, normalize metadata or construct journal outcomes.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes;
 * RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries;
 * RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
@RestController
@RequestMapping("/api/journal")
public class JournalController {


  private static final Logger log = LoggerFactory.getLogger(JournalController.class);

  private final JournalEventQueries events;
  private final JournalRunQueries runs;
  private final JournalRunMetadata metadata;
  private final OrchestratorEndpointAuthorization endpointAuthorization;

  @Value("${pockethive.journal.sink:postgres}")
  private String journalSink;

  public JournalController(OrchestratorEndpointAuthorization endpointAuthorization,
                           JournalEventQueries events, JournalRunQueries runs, JournalRunMetadata metadata) {
    this.runs = runs;
    this.events = events;
    this.metadata = metadata;
    this.endpointAuthorization = Objects.requireNonNull(endpointAuthorization, "endpointAuthorization");
  }

  /**
   * GET {@code /api/journal/hive/page} — paginated Hive journal query (Postgres only).
   * <p>
   * Results are returned newest-first (descending by {@code (ts, id)}). Use {@code nextCursor} as the
   * {@code beforeTs}/{@code beforeId} pair for the next page (older entries).
   */
  @GetMapping("/hive/page")
  public ResponseEntity<JournalPageResponse> hiveJournalPage(
      @RequestParam(required = false) String swarmId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) String correlationId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeTs,
      @RequestParam(required = false) Long beforeId,
      @RequestParam(required = false) Integer limit) {
    String path = "/api/journal/hive/page";
    log.info("[REST] GET {}", path);
    if (swarmId == null || swarmId.isBlank()) {
      endpointAuthorization.requireReadDeployment();
    } else {
      endpointAuthorization.requireReadSwarm(swarmId);
    }
    if (!"postgres".equalsIgnoreCase(journalSink)) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    if ((beforeTs == null) != (beforeId == null)) {
      return ResponseEntity.badRequest().build();
    }
    int pageSize = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
    String cleanedSwarmId = swarmId == null ? null : sanitizeSegment(swarmId.trim());
    String resolvedRunId = runId == null ? null : runId.trim();
    if (resolvedRunId != null && resolvedRunId.isBlank()) {
      resolvedRunId = null;
    }
    String corr = correlationId == null ? null : correlationId.trim();
    if (corr != null && corr.isBlank()) {
      corr = null;
    }

    return ResponseEntity.ok(events.hivePage(cleanedSwarmId, resolvedRunId, corr, beforeTs, beforeId, pageSize));
  }

  /**
   * GET {@code /api/journal/swarm/runs} — list all known swarm journal runs (Postgres only).
   * <p>
   * Runs are returned newest-first (by last entry time). Pinned runs (archives) are included even when the
   * main timeline has been truncated by retention.
   */
  @GetMapping("/swarm/runs")
  public ResponseEntity<List<SwarmRunSummary>> swarmJournalRuns(@RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) Boolean pinned,
                                                                @RequestParam(required = false)
                                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                                Instant afterTs) {
    String path = "/api/journal/swarm/runs";
    log.info("[REST] GET {}", path);
    endpointAuthorization.requireReadDeployment();
    if (!"postgres".equalsIgnoreCase(journalSink)) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    int pageSize = limit == null ? 500 : Math.max(1, Math.min(5000, limit));
    boolean pinnedOnly = pinned != null && pinned;

    try {
      return ResponseEntity.ok(runs.allSwarmRuns(pageSize, pinnedOnly, afterTs));
    } catch (Exception ex) {
      log.warn("Failed to list swarm journal runs: {}", ex.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * POST {@code /api/journal/swarm/runs/{runId}/meta} — set/clear metadata for a run (Postgres only).
   * <p>
   * This exists so operators/tools can label runs (test plan, tags, notes) without issuing SQL manually.
   */
  @PostMapping("/swarm/runs/{runId}/meta")
  public ResponseEntity<SwarmRunSummary> updateRunMetadata(@PathVariable String runId,
                                                           @RequestBody(required = false) SwarmRunMetadataUpdate update) {
    String path = "/api/journal/swarm/runs/" + runId + "/meta";
    log.info("[REST] POST {}", path);
    endpointAuthorization.requireManageDeployment();
    if (!"postgres".equalsIgnoreCase(journalSink)) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    String resolvedRunId = runId == null ? null : runId.trim();
    if (resolvedRunId == null || resolvedRunId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      SwarmRunSummary summary = metadata.update(resolvedRunId, update);
      return summary == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(summary);
    } catch (IllegalStateException conflict) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (Exception ex) {
      log.warn("Failed to update run metadata for runId={}: {}", resolvedRunId, ex.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  private static String sanitizeSegment(String id) {
    try {
      return RuntimeFilesystemLayout.requireSegment(id, "swarmId");
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

}
