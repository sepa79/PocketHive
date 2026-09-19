package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.StatusMetric;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Apply accepted worker status observations to lifecycle state and read-only worker projections.
 * Must not: Decode transport messages, publish controller status, or decide lifecycle command outcomes.
 * Contract: Preserve observation ordering while keeping {@link SwarmLifecycle} the sole readiness/state owner.
 */
@Component
public class SwarmWorkerStatusHandler {

  static final long WORKER_STATUS_STALE_AFTER_MS = 15_000L;

  private final SwarmLifecycle lifecycle;
  private final ObjectMapper mapper;
  private final SwarmDiagnosticsAggregator diagnostics;
  private final SwarmWorkersAggregator workers;
  private final SwarmWorkerErrorJournal workerErrors;

  public SwarmWorkerStatusHandler(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmWorkerErrorJournal workerErrors) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.diagnostics = new SwarmDiagnosticsAggregator(this.mapper);
    this.workers = new SwarmWorkersAggregator(WORKER_STATUS_STALE_AFTER_MS);
    this.workerErrors = Objects.requireNonNull(workerErrors, "workerErrors");
  }

  boolean observe(String role, String instance, StatusMetric status, boolean statusFull) {
    JsonNode envelope = mapper.valueToTree(Objects.requireNonNull(status, "status"));
    lifecycle.updateHeartbeat(role, instance);
    JsonNode enabledNode = envelope.path("data").get("enabled");
    if (enabledNode == null || !enabledNode.isBoolean()) {
      throw new IllegalArgumentException("worker status data.enabled must be a boolean");
    }
    boolean enabled = enabledNode.asBoolean();
    diagnostics.updateFromWorkerStatus(role, instance, envelope.path("data"));
    workers.updateFromWorkerStatus(role, instance, envelope.path("data"), envelope.path("runtime"));
    workerErrors.observe(role, instance, envelope);
    if (statusFull) {
      lifecycle.recordStatusSnapshot(role, instance, enabled);
    } else {
      lifecycle.updateEnabled(role, instance, enabled);
    }
    return !enabled && lifecycle.markReady(role, instance);
  }

  Map<String, Map<String, Object>> diagnosticsSnapshot() {
    return diagnostics.snapshot();
  }

  List<Map<String, Object>> workersSnapshot() {
    return workers.snapshot();
  }
}
