package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import io.pockethive.swarmcontroller.runtime.SwarmJournalEntries;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Journal increases in worker-reported error counters from accepted status observations.
 * Must not: Decode transport messages, update readiness, or decide lifecycle convergence.
 * Contract: Derive diagnostic journal entries from worker status context without mutating lifecycle state.
 */
@Component
public class SwarmWorkerErrorJournal {

  private static final String ERROR_SEVERITY = "ERROR";
  private static final String WORKER_ERROR_TYPE = "worker-error";
  private static final String SOURCE_FIELD = "source";

  private final SwarmJournal journal;
  private final String swarmId;
  private final String controllerInstance;
  private final ConcurrentMap<String, Long> lastWorkerErrorCounts = new ConcurrentHashMap<>();

  public SwarmWorkerErrorJournal(
      SwarmJournal journal,
      SwarmControllerProperties properties,
      @Qualifier("instanceId") String controllerInstance) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.swarmId = Objects.requireNonNull(properties, "properties").getSwarmId();
    this.controllerInstance = requireText("controllerInstance", controllerInstance);
  }

  void observe(String workerRole, String workerInstance, JsonNode statusEnvelope) {
    if (workerRole == null || workerRole.isBlank() || workerInstance == null || workerInstance.isBlank()) {
      return;
    }
    if (statusEnvelope == null || !statusEnvelope.isObject()) {
      return;
    }
    JsonNode data = statusEnvelope.path("data");
    if (!data.isObject()) {
      return;
    }
    JsonNode context = data.path("context");
    if (!context.isObject()) {
      return;
    }
    JsonNode errorCountNode = context.get("errorCount");
    if (errorCountNode == null || !errorCountNode.isNumber()) {
      return;
    }
    long current = errorCountNode.asLong(0L);
    if (current <= 0L) {
      return;
    }
    String key = workerRole + ":" + workerInstance;
    long previous = lastWorkerErrorCounts.getOrDefault(key, 0L);
    if (current <= previous) {
      lastWorkerErrorCounts.put(key, current);
      return;
    }
    lastWorkerErrorCounts.put(key, current);

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("role", workerRole);
    entry.put("instance", workerInstance);
    entry.put("errorCount", current);
    entry.put("errorDelta", current - previous);
    JsonNode errorTpsNode = context.get("errorTps");
    if (errorTpsNode != null && errorTpsNode.isNumber()) {
      entry.put("errorTps", errorTpsNode.numberValue());
    }
    JsonNode serviceIdNode = context.get("serviceId");
    if (serviceIdNode != null && serviceIdNode.isTextual() && !serviceIdNode.asText().isBlank()) {
      entry.put("serviceId", serviceIdNode.asText());
    }
    JsonNode templateRootNode = context.get("templateRoot");
    if (templateRootNode != null && templateRootNode.isTextual() && !templateRootNode.asText().isBlank()) {
      entry.put("templateRoot", templateRootNode.asText());
    }

    journal.append(SwarmJournalEntries.local(
        swarmId,
        ERROR_SEVERITY,
        WORKER_ERROR_TYPE,
        controllerInstance,
        ControlScope.forInstance(swarmId, workerRole, workerInstance),
        entry,
        Map.of(SOURCE_FIELD, StatusMetric.STATUS_DELTA)));
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
