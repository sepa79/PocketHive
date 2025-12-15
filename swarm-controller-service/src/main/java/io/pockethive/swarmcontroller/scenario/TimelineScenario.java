package io.pockethive.swarmcontroller.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.control.ControlScope;
import io.pockethive.manager.runtime.ManagerStatus;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.Scenario;
import io.pockethive.manager.scenario.ScenarioContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple, time-driven scenario implementation that consumes a plan JSON document
 * and emits config-update signals at the configured offsets.
 * <p>
 * v1 only supports bee-scoped steps of type:
 * <ul>
 *   <li>{@code config-update} – payload forwarded as-is</li>
 *   <li>{@code start} – sugar for {@code worker.enabled=true}</li>
 *   <li>{@code stop} – sugar for {@code worker.enabled=false}</li>
 * </ul>
 * Swarm-level steps are ignored for now.
 */
public final class TimelineScenario implements Scenario {

  private static final Logger log = LoggerFactory.getLogger(TimelineScenario.class);

  private final String id;
  private final ObjectMapper mapper;
  private final TimelineScenarioObserver observer;
  private final AtomicReference<Schedule> scheduleRef = new AtomicReference<>();
  private final AtomicReference<Progress> progressRef = new AtomicReference<>();

  private volatile Instant startedAt;
  private volatile Integer runLimit;
  private volatile Integer runsRemaining;
  private volatile boolean completionReported;

  public TimelineScenario(String id, ObjectMapper mapper) {
    this(id, mapper, null);
  }

  public TimelineScenario(String id, ObjectMapper mapper, TimelineScenarioObserver observer) {
    this.id = Objects.requireNonNull(id, "id");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.observer = observer;
  }

  @Override
  public String id() {
    return id;
  }

  /**
   * Replace the active plan with the provided JSON document.
   */
  public void applyPlan(String planJson) {
    if (planJson == null || planJson.isBlank()) {
      log.info("Clearing scenario plan (empty payload)");
      scheduleRef.set(null);
      progressRef.set(null);
      runsRemaining = null;
      startedAt = null;
      completionReported = false;
      if (observer != null) {
        observer.onPlanCleared();
      }
      return;
    }
    try {
      Map<String, Object> root = mapper.readValue(planJson, new TypeReference<>() {});
      Schedule schedule = Schedule.fromMap(root);
      scheduleRef.set(schedule);
      log.info("Loaded scenario plan with {} bee step(s) and {} swarm step(s)",
          schedule.beeSteps.size(), schedule.swarmSteps.size());
      resetSchedule(schedule, true);
      completionReported = false;
      if (observer != null) {
        observer.onPlanLoaded(schedule.beeSteps.size(), schedule.swarmSteps.size());
      }
    } catch (Exception ex) {
      log.warn("Failed to parse scenario plan JSON; clearing schedule", ex);
      scheduleRef.set(null);
      progressRef.set(null);
      runsRemaining = null;
      startedAt = null;
      completionReported = false;
      if (observer != null) {
        observer.onPlanParseFailed(ex.getMessage());
      }
    }
  }

  /**
   * Restart the loaded scenario plan from its first step.
   */
  public void reset() {
    Schedule schedule = scheduleRef.get();
    if (schedule == null) {
      return;
    }
    log.info("Resetting scenario plan; runsRemaining={}", runsRemaining);
    resetSchedule(schedule, true);
    completionReported = false;
    if (observer != null) {
      observer.onPlanReset();
    }
  }

  /**
   * Configure how many times the current plan should execute.
   */
  public void setRunCount(Integer runs) {
    if (runs == null || runs < 1) {
      log.warn("Ignoring scenario run count {}; value must be >= 1", runs);
      return;
    }
    this.runLimit = runs;
    this.runsRemaining = runs;
    Schedule schedule = scheduleRef.get();
    if (schedule != null) {
      resetSchedule(schedule, true);
    }
  }

  /**
   * Reset the current schedule to its initial state, clearing fired markers and
   * optionally resetting the remaining run counter.
   */
  private synchronized void resetSchedule(Schedule schedule, boolean resetRuns) {
    if (schedule == null) {
      return;
    }
    for (StepInstance s : schedule.beeSteps) {
      s.fired = false;
    }
    for (StepInstance s : schedule.swarmSteps) {
      s.fired = false;
    }
    if (resetRuns && runLimit != null) {
      runsRemaining = runLimit;
    }
    startedAt = null;
    progressRef.set(Progress.initial(schedule, runLimit, runsRemaining));
  }

  @Override
  public void onTick(ManagerRuntimeView view, ScenarioContext context) {
    Schedule schedule = scheduleRef.get();
    if (schedule == null) {
      return;
    }
    if (startedAt == null) {
      // Anchor the timeline to the first tick observed by the controller so
      // offsets are evaluated from "swarm controller is alive" without
      // depending on an explicit swarm-start signal.
      startedAt = Instant.now();
      log.info("Starting scenario timeline '{}' at {}", id, startedAt);
      if (observer != null) {
        observer.onTimelineStarted(startedAt);
      }
    }
    long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
    List<StepInstance> due = schedule.dueSteps(elapsedMillis);
    StepInstance lastExecuted = null;
    for (StepInstance step : due) {
      if (observer != null) {
        observer.onStepStarted(step.stepId, step.name, step.dueMillis, step.type, step.role, step.instanceId, isSwarmLifecycleStep(step));
      }
      try {
        emitStep(step, context);
        if (observer != null) {
          observer.onStepCompleted(step.stepId, step.name, step.dueMillis, step.type, step.role, step.instanceId, isSwarmLifecycleStep(step));
        }
      } catch (Exception ex) {
        log.warn("Failed to execute scenario step {}", step.stepId, ex);
        if (observer != null) {
          observer.onStepFailed(step.stepId, step.name, step.dueMillis, step.type, step.role, step.instanceId, isSwarmLifecycleStep(step), ex.getMessage());
        }
      }
      // Update progress after each successfully emitted step.
      lastExecuted = step;
      progressRef.set(Progress.update(schedule, step, elapsedMillis, runLimit, runsRemaining));
    }
    if (schedule.isComplete()) {
      handleCompletion(schedule, lastExecuted, elapsedMillis);
    }
  }

  private void handleCompletion(Schedule schedule, StepInstance lastStep, long elapsedMillis) {
    Integer totalRuns = runLimit;
    Integer remaining = runsRemaining;
    if (runLimit == null) {
      progressRef.set(Progress.update(schedule, lastStep, elapsedMillis, totalRuns, remaining));
      if (!completionReported && observer != null) {
        completionReported = true;
        observer.onPlanCompleted(null, null);
      }
      return;
    }
    if (completionReported) {
      progressRef.set(Progress.update(schedule, lastStep, elapsedMillis, totalRuns, remaining));
      return;
    }
    int effectiveRemaining = remaining != null ? remaining : runLimit;
    if (effectiveRemaining > 0) {
      effectiveRemaining -= 1;
    }
    runsRemaining = effectiveRemaining;
    if (effectiveRemaining > 0) {
      if (observer != null) {
        observer.onRunCompleted(runLimit, effectiveRemaining);
      }
      resetSchedule(schedule, false);
      progressRef.set(Progress.initial(schedule, totalRuns, runsRemaining));
      return;
    }
    if (observer != null) {
      observer.onRunCompleted(runLimit, 0);
      observer.onPlanCompleted(runLimit, 0);
    }
    completionReported = true;
    StepInstance last = lastStep != null ? lastStep : schedule.lastFired();
    progressRef.set(Progress.update(schedule, last, elapsedMillis, totalRuns, runsRemaining));
  }

  /**
   * Snapshot of high-level scenario progress for status reporting.
   */
  public Progress snapshotProgress() {
    Schedule schedule = scheduleRef.get();
    if (schedule == null) {
      return null;
    }
    Instant started = this.startedAt;
    long elapsedMillis = started != null
        ? Duration.between(started, Instant.now()).toMillis()
        : 0L;
    Progress current = progressRef.get();
    if (started == null && current == null) {
      current = Progress.initial(schedule, runLimit, runsRemaining);
    }
    return Progress.current(schedule, elapsedMillis, current, runLimit, runsRemaining);
  }

  private void emitStep(StepInstance step, ScenarioContext context) {
    String type = step.type.toLowerCase(Locale.ROOT);
    ObjectNode data = mapper.createObjectNode();
    boolean swarmLifecycleStep = isSwarmLifecycleStep(step);
    // Shape the payload that ConfigFanout expects under args.data.
    switch (type) {
      case "start" -> {
        if (swarmLifecycleStep) {
          // Drive swarm-wide enablement through the same lifecycle path that
          // REST /api/swarms/{id}/start uses. This reuses SwarmRuntimeCore's
          // enableAll()/setSwarmEnabled logic, including config-update fan-out.
          log.info("Scenario step {} enabling entire swarm via lifecycle", step.stepId);
          context.manager().enableAll();
          return;
        }
        // Bee-scoped enable (role/instance) – emit a top-level enabled flag
        // that WorkerControlPlaneRuntime will interpret as worker.enabled.
        data.put("enabled", true);
      }
      case "stop" -> {
        if (swarmLifecycleStep) {
          log.info("Scenario step {} disabling entire swarm via lifecycle", step.stepId);
          context.manager().setWorkEnabled(false);
          return;
        }
        // Bee-scoped disable.
        data.put("enabled", false);
      }
      case "config-update", "" -> {
        if (step.config != null && !step.config.isEmpty()) {
          ObjectNode cfgNode = mapper.valueToTree(step.config);
          data.setAll(cfgNode);
        }
      }
      default -> {
        log.debug("Ignoring unsupported scenario step type {}", type);
        return;
      }
    }

    ControlScope targetScope;
    if (step.instanceId != null && !step.instanceId.isBlank()) {
      if (step.role == null || step.role.isBlank()) {
        log.warn("Scenario step {} targets instance {} but has no role; skipping", step.stepId, step.instanceId);
        return;
      }
      targetScope = ControlScope.forInstance(context.swarmId(), step.role, step.instanceId);
    } else if (step.role != null && !step.role.isBlank()) {
      targetScope = ControlScope.forRole(context.swarmId(), step.role);
    } else {
      targetScope = ControlScope.forSwarm(context.swarmId());
    }

    log.info("Scenario step {} at {}ms -> role={} instance={} type={}",
        step.stepId, step.dueMillis, step.role, step.instanceId, type);
    context.configFanout().publishConfigUpdate(targetScope, data, "scenario");
  }

  private static boolean isSwarmLifecycleStep(StepInstance step) {
    return step != null
        && step.instanceId == null
        && (step.role == null || step.role.isBlank());
  }

  private static final class Schedule {
    final List<StepInstance> beeSteps;
    final List<StepInstance> swarmSteps;

    Schedule(List<StepInstance> beeSteps, List<StepInstance> swarmSteps) {
      this.beeSteps = beeSteps;
      this.swarmSteps = swarmSteps;
    }

    static Schedule fromMap(Map<String, Object> root) {
      List<StepInstance> beeSteps = new ArrayList<>();
      List<StepInstance> swarmSteps = new ArrayList<>();

      Object beesObj = root.get("bees");
      if (beesObj instanceof List<?> beeList) {
        for (Object b : beeList) {
          if (!(b instanceof Map<?, ?> beeMapRaw)) {
            continue;
          }
          @SuppressWarnings("unchecked")
          Map<String, Object> beeMap = (Map<String, Object>) beeMapRaw;
          String instanceId = asText(beeMap.get("instanceId"));
          String role = asText(beeMap.get("role")); // optional; may be null
          Object stepsObj = beeMap.get("steps");
          if (!(stepsObj instanceof List<?> stepsList)) {
            continue;
          }
          for (Object s : stepsList) {
            if (!(s instanceof Map<?, ?> stepRaw)) {
              continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> stepMap = (Map<String, Object>) stepRaw;
            StepInstance inst = StepInstance.from(stepMap, instanceId, role);
            if (inst != null) {
              beeSteps.add(inst);
            }
          }
        }
      }

      Object swarmObj = root.get("swarm");
      if (swarmObj instanceof List<?> swarmList) {
        for (Object s : swarmList) {
          if (!(s instanceof Map<?, ?> stepRaw)) {
            continue;
          }
          @SuppressWarnings("unchecked")
          Map<String, Object> stepMap = (Map<String, Object>) stepRaw;
          StepInstance inst = StepInstance.from(stepMap, null, null);
          if (inst != null) {
            swarmSteps.add(inst);
          }
        }
      }

      beeSteps.sort(Comparator.comparingLong(si -> si.dueMillis));
      swarmSteps.sort(Comparator.comparingLong(si -> si.dueMillis));
      return new Schedule(beeSteps, swarmSteps);
    }

    List<StepInstance> dueSteps(long elapsedMillis) {
      List<StepInstance> due = new ArrayList<>();
      for (StepInstance s : beeSteps) {
        if (!s.fired && s.dueMillis <= elapsedMillis) {
          s.fired = true;
          due.add(s);
        }
      }
      for (StepInstance s : swarmSteps) {
        if (!s.fired && s.dueMillis <= elapsedMillis) {
          s.fired = true;
          due.add(s);
        }
      }
      if (due.size() > 1) {
        due.sort(Comparator.comparingLong(si -> si.dueMillis));
      }
      return due;
    }

    boolean isComplete() {
      for (StepInstance s : beeSteps) {
        if (!s.fired) {
          return false;
        }
      }
      for (StepInstance s : swarmSteps) {
        if (!s.fired) {
          return false;
        }
      }
      return true;
    }

    StepInstance lastFired() {
      StepInstance candidate = null;
      for (StepInstance s : beeSteps) {
        if (!s.fired) {
          continue;
        }
        if (candidate == null || s.dueMillis > candidate.dueMillis) {
          candidate = s;
        }
      }
      for (StepInstance s : swarmSteps) {
        if (!s.fired) {
          continue;
        }
        if (candidate == null || s.dueMillis > candidate.dueMillis) {
          candidate = s;
        }
      }
      return candidate;
    }

    private static String asText(Object value) {
      if (value == null) {
        return null;
      }
      String s = value.toString().trim();
      return s.isEmpty() ? null : s;
    }
  }

  /**
   * Minimal progress view for status/diagnostics.
   */
  public static final class Progress {
    public final String lastStepId;
    public final String lastStepName;
    public final List<String> firedStepIds;
    public final long elapsedMillis;
    public final String nextStepId;
    public final String nextStepName;
    public final Long nextDueMillis;
    public final Integer totalRuns;
    public final Integer runsRemaining;

    private Progress(String lastStepId,
                     String lastStepName,
                     List<String> firedStepIds,
                     long elapsedMillis,
                     String nextStepId,
                     String nextStepName,
                     Long nextDueMillis,
                     Integer totalRuns,
                     Integer runsRemaining) {
      this.lastStepId = lastStepId;
      this.lastStepName = lastStepName;
      this.firedStepIds = firedStepIds != null ? List.copyOf(firedStepIds) : List.of();
      this.elapsedMillis = elapsedMillis;
      this.nextStepId = nextStepId;
      this.nextStepName = nextStepName;
      this.nextDueMillis = nextDueMillis;
      this.totalRuns = totalRuns;
      this.runsRemaining = runsRemaining;
    }

    static Progress initial(Schedule schedule, Integer totalRuns, Integer runsRemaining) {
      StepInstance next = earliestPending(schedule);
      return new Progress(null, null, List.of(), 0L,
          next != null ? next.stepId : null,
          next != null ? next.name : null,
          next != null ? next.dueMillis : null,
          totalRuns,
          runsRemaining);
    }

    static Progress update(Schedule schedule,
                           StepInstance last,
                           long elapsedMillis,
                           Integer totalRuns,
                           Integer runsRemaining) {
      StepInstance next = earliestPending(schedule);
      return new Progress(
          last != null ? last.stepId : null,
          last != null ? last.name : null,
          firedStepIds(schedule),
          elapsedMillis,
          next != null ? next.stepId : null,
          next != null ? next.name : null,
          next != null ? next.dueMillis : null,
          totalRuns,
          runsRemaining);
    }

    static Progress current(Schedule schedule,
                            long elapsedMillis,
                            Progress previous,
                            Integer totalRuns,
                            Integer runsRemaining) {
      if (previous != null && previous.elapsedMillis >= elapsedMillis) {
        return previous;
      }
      StepInstance last = latestFired(schedule);
      StepInstance next = earliestPending(schedule);
      return new Progress(
          last != null ? last.stepId : null,
          last != null ? last.name : null,
          firedStepIds(schedule),
          elapsedMillis,
          next != null ? next.stepId : null,
          next != null ? next.name : null,
          next != null ? next.dueMillis : null,
          totalRuns,
          runsRemaining);
    }

    private static StepInstance earliestPending(Schedule schedule) {
      StepInstance candidate = null;
      for (StepInstance s : schedule.beeSteps) {
        if (s.fired) {
          continue;
        }
        if (candidate == null || s.dueMillis < candidate.dueMillis) {
          candidate = s;
        }
      }
      for (StepInstance s : schedule.swarmSteps) {
        if (s.fired) {
          continue;
        }
        if (candidate == null || s.dueMillis < candidate.dueMillis) {
          candidate = s;
        }
      }
      return candidate;
    }

    private static StepInstance latestFired(Schedule schedule) {
      StepInstance candidate = null;
      for (StepInstance s : schedule.beeSteps) {
        if (!s.fired) {
          continue;
        }
        if (candidate == null || s.dueMillis > candidate.dueMillis) {
          candidate = s;
        }
      }
      for (StepInstance s : schedule.swarmSteps) {
        if (!s.fired) {
          continue;
        }
        if (candidate == null || s.dueMillis > candidate.dueMillis) {
          candidate = s;
        }
      }
      return candidate;
    }

    private static List<String> firedStepIds(Schedule schedule) {
      List<StepInstance> fired = new ArrayList<>();
      for (StepInstance s : schedule.beeSteps) {
        if (s.fired) {
          fired.add(s);
        }
      }
      for (StepInstance s : schedule.swarmSteps) {
        if (s.fired) {
          fired.add(s);
        }
      }
      fired.sort(Comparator.comparingLong(si -> si.dueMillis));
      List<String> ids = new ArrayList<>(fired.size());
      for (StepInstance s : fired) {
        if (s.stepId != null && !s.stepId.isBlank()) {
          ids.add(s.stepId);
        }
      }
      return ids;
    }
  }

  private static final class StepInstance {
    final String stepId;
    final String name;
    final String type;
    final Map<String, Object> config;
    final String instanceId;
    final String role;
    final long dueMillis;
    volatile boolean fired;

    private StepInstance(String stepId,
                         String name,
                         String type,
                         Map<String, Object> config,
                         String instanceId,
                         String role,
                         long dueMillis) {
      this.stepId = stepId;
      this.name = name;
      this.type = type;
      this.config = config;
      this.instanceId = instanceId;
      this.role = role;
      this.dueMillis = dueMillis;
    }

    @SuppressWarnings("unchecked")
    static StepInstance from(Map<String, Object> map, String instanceId, String role) {
      if (map == null || map.isEmpty()) {
        return null;
      }
      Object idObj = map.get("stepId");
      Object timeObj = map.get("time");
      if (idObj == null || timeObj == null) {
        return null;
      }
      String stepId = idObj.toString();
      Object nameObj = map.get("name");
      String name = nameObj != null ? nameObj.toString() : null;
      String timeStr = timeObj.toString();
      long millis;
      try {
        millis = Duration.parse(timeStr).toMillis();
      } catch (Exception ex) {
        Long fallback = parseSimpleSeconds(timeStr);
        if (fallback == null) {
          return null;
        }
        millis = fallback * 1000L;
      }
      String type = map.getOrDefault("type", "config-update").toString();
      Map<String, Object> cfg = Map.of();
      Object cfgObj = map.get("config");
      if (cfgObj instanceof Map<?, ?> cfgRaw) {
        cfg = (Map<String, Object>) cfgRaw;
      }
      return new StepInstance(stepId, name, type, cfg, instanceId, role, millis);
    }

    private static Long parseSimpleSeconds(String value) {
      if (value == null) {
        return null;
      }
      String text = value.trim();
      if (text.isEmpty()) {
        return null;
      }
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)([smh])$", java.util.regex.Pattern.CASE_INSENSITIVE)
          .matcher(text);
      if (!m.matches()) {
        return null;
      }
      double amount;
      try {
        amount = Double.parseDouble(m.group(1));
      } catch (NumberFormatException ex) {
        return null;
      }
      String unit = m.group(2).toLowerCase(Locale.ROOT);
      double seconds;
      switch (unit) {
        case "s" -> seconds = amount;
        case "m" -> seconds = amount * 60d;
        case "h" -> seconds = amount * 3600d;
        default -> {
          return null;
        }
      }
      if (!Double.isFinite(seconds) || seconds < 0d) {
        return null;
      }
      long rounded = Math.round(seconds);
      return rounded >= 0L ? rounded : null;
    }
  }
}
