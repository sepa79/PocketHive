package io.pockethive.swarmcontroller.runtime;

import io.pockethive.control.ControlScope;
import io.pockethive.swarmcontroller.scenario.TimelineScenarioObserver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * Responsibility: Project local runtime and timeline events into the swarm journal.
 * Must not: Drive scenario execution, mutate runtime state, or publish control-plane messages.
 * Contract: Append one local journal entry per reported event using the controller's instance scope.
 */
final class SwarmRuntimeJournal implements TimelineScenarioObserver {

  private static final String KIND_PLAN = "plan";
  private static final String KIND_WORKER = "worker";
  private static final String ORIGIN = "swarm-controller";
  private static final String SEVERITY_ERROR = "ERROR";
  private static final String SEVERITY_INFO = "INFO";

  private final SwarmJournal journal;
  private final String swarmId;
  private final ControlScope scope;

  SwarmRuntimeJournal(SwarmJournal journal, String swarmId, String role, String instanceId) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.swarmId = Objects.requireNonNull(swarmId, "swarmId");
    this.scope = ControlScope.forInstance(swarmId, role, instanceId);
  }

  void workersPlanned(int workerCount, List<String> roles) {
    appendWithContext(
        KIND_WORKER,
        SEVERITY_INFO,
        "workers-planned",
        Map.of("workers", workerCount, "roles", List.copyOf(roles)));
  }

  void workersProvisioned(int workerCount) {
    appendWithContext(
        KIND_WORKER,
        SEVERITY_INFO,
        "workers-provisioned",
        Map.of("workers", workerCount));
  }

  void templateInvalid(Throwable failure) {
    appendWithContext(
        KIND_PLAN,
        SEVERITY_ERROR,
        "template-invalid",
        Map.of("message", safeMessage(failure)));
  }

  @Override
  public void onPlanCleared() {
    appendWithContext(KIND_PLAN, SEVERITY_INFO, "scenario-plan-cleared", null);
  }

  @Override
  public void onPlanLoaded(int beeSteps, int swarmSteps) {
    appendWithContext(
        KIND_PLAN,
        SEVERITY_INFO,
        "scenario-plan-loaded",
        Map.of("beeSteps", beeSteps, "swarmSteps", swarmSteps));
  }

  @Override
  public void onPlanParseFailed(String message) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", message != null ? message : "");
    appendWithContext(KIND_PLAN, SEVERITY_ERROR, "scenario-plan-parse-failed", data);
  }

  @Override
  public void onPlanReset() {
    appendWithContext(KIND_PLAN, SEVERITY_INFO, "scenario-plan-reset", null);
  }

  @Override
  public void onTimelineStarted(Instant startedAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("startedAt", startedAt != null ? startedAt.toString() : null);
    appendWithoutContext(KIND_PLAN, SEVERITY_INFO, "scenario-timeline-started", data);
  }

  @Override
  public void onStepStarted(String stepId,
                            String name,
                            long dueMillis,
                            String type,
                            String role,
                            String instanceId,
                            boolean swarmLifecycleStep) {
    appendWithoutContext(
        KIND_PLAN,
        SEVERITY_INFO,
        "scenario-step-started",
        stepData(stepId, name, dueMillis, type, role, instanceId, swarmLifecycleStep));
  }

  @Override
  public void onStepCompleted(String stepId,
                              String name,
                              long dueMillis,
                              String type,
                              String role,
                              String instanceId,
                              boolean swarmLifecycleStep) {
    appendWithoutContext(
        KIND_PLAN,
        SEVERITY_INFO,
        "scenario-step-completed",
        stepData(stepId, name, dueMillis, type, role, instanceId, swarmLifecycleStep));
  }

  @Override
  public void onStepFailed(String stepId,
                           String name,
                           long dueMillis,
                           String type,
                           String role,
                           String instanceId,
                           boolean swarmLifecycleStep,
                           String message) {
    Map<String, Object> data = stepData(
        stepId, name, dueMillis, type, role, instanceId, swarmLifecycleStep);
    data.put("message", message != null ? message : "");
    appendWithoutContext(KIND_PLAN, SEVERITY_ERROR, "scenario-step-failed", data);
  }

  @Override
  public void onRunCompleted(Integer totalRuns, Integer runsRemaining) {
    appendWithoutContext(
        KIND_PLAN,
        SEVERITY_INFO,
        "scenario-run-completed",
        runData(totalRuns, runsRemaining));
  }

  @Override
  public void onPlanCompleted(Integer totalRuns, Integer runsRemaining) {
    appendWithoutContext(
        KIND_PLAN,
        SEVERITY_INFO,
        "scenario-plan-completed",
        runData(totalRuns, runsRemaining));
  }

  private void appendWithContext(String kind,
                                 String severity,
                                 String type,
                                 Map<String, Object> data) {
    append(kind, severity, type, data, mdcCorrelationId(), mdcIdempotencyKey());
  }

  private void appendWithoutContext(String kind,
                                    String severity,
                                    String type,
                                    Map<String, Object> data) {
    append(kind, severity, type, data, null, null);
  }

  private void append(String kind,
                      String severity,
                      String type,
                      Map<String, Object> data,
                      String correlationId,
                      String idempotencyKey) {
    journal.append(new SwarmJournal.SwarmJournalEntry(
        Instant.now(),
        swarmId,
        severity,
        SwarmJournal.Direction.LOCAL,
        kind,
        type,
        ORIGIN,
        scope,
        correlationId,
        idempotencyKey,
        null,
        data,
        null,
        null));
  }

  private static Map<String, Object> stepData(String stepId,
                                              String name,
                                              long dueMillis,
                                              String type,
                                              String role,
                                              String instanceId,
                                              boolean swarmLifecycleStep) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("stepId", stepId);
    data.put("name", name);
    data.put("dueMillis", dueMillis);
    data.put("stepType", type);
    data.put("targetRole", role);
    data.put("targetInstance", instanceId);
    data.put("swarmLifecycleStep", swarmLifecycleStep);
    return data;
  }

  private static Map<String, Object> runData(Integer totalRuns, Integer runsRemaining) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("totalRuns", totalRuns);
    data.put("runsRemaining", runsRemaining);
    return data;
  }

  private static String safeMessage(Throwable failure) {
    if (failure == null) {
      return "unknown failure";
    }
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return failure.getClass().getSimpleName();
    }
    return message.length() > 200 ? message.substring(0, 200) + "…" : message;
  }

  private static String mdcCorrelationId() {
    return mdcValue("correlation_id");
  }

  private static String mdcIdempotencyKey() {
    return mdcValue("idempotency_key");
  }

  private static String mdcValue(String key) {
    String value = MDC.get(key);
    return value != null && !value.isBlank() ? value : null;
  }
}
