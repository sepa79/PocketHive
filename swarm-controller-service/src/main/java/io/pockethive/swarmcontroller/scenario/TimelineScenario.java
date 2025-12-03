package io.pockethive.swarmcontroller.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.control.CommandTarget;
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
  private final AtomicReference<Schedule> scheduleRef = new AtomicReference<>();
  private final AtomicReference<Progress> progressRef = new AtomicReference<>();

  private volatile Instant startedAt;

  public TimelineScenario(String id, ObjectMapper mapper) {
    this.id = Objects.requireNonNull(id, "id");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
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
      startedAt = null;
      return;
    }
    try {
      Map<String, Object> root = mapper.readValue(planJson, new TypeReference<>() {});
      Schedule schedule = Schedule.fromMap(root);
      scheduleRef.set(schedule);
      log.info("Loaded scenario plan with {} bee step(s) and {} swarm step(s)",
          schedule.beeSteps.size(), schedule.swarmSteps.size());
      progressRef.set(Progress.initial(schedule));
    } catch (Exception ex) {
      log.warn("Failed to parse scenario plan JSON; clearing schedule", ex);
      scheduleRef.set(null);
      progressRef.set(null);
      startedAt = null;
    }
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
    }
    long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
    List<StepInstance> due = schedule.dueSteps(elapsedMillis);
    if (due.isEmpty()) {
      return;
    }
    for (StepInstance step : due) {
      try {
        emitStep(step, context);
      } catch (Exception ex) {
        log.warn("Failed to execute scenario step {}", step.stepId, ex);
      }
      // Update progress after each successfully emitted step.
      progressRef.set(Progress.update(schedule, step, elapsedMillis));
    }
  }

  /**
   * Snapshot of high-level scenario progress for status reporting.
   */
  public Progress snapshotProgress() {
    Schedule schedule = scheduleRef.get();
    Instant started = this.startedAt;
    if (schedule == null || started == null) {
      return null;
    }
    long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
    return Progress.current(schedule, elapsedMillis, progressRef.get());
  }

  private void emitStep(StepInstance step, ScenarioContext context) {
    String type = step.type.toLowerCase(Locale.ROOT);
    ObjectNode data = mapper.createObjectNode();
    boolean swarmLifecycleStep = step.instanceId == null && (step.role == null || step.role.isBlank());
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

    // Target resolution:
    // - If an explicit instanceId is provided, use commandTarget=INSTANCE.
    // - Otherwise, if only a role is provided, use commandTarget=ROLE so the
    //   update fans out to all workers with that role.
    // - If neither role nor instanceId is provided, leave commandTarget
    //   unset so ConfigFanout defaults to SWARM.
    if (step.instanceId != null && !step.instanceId.isBlank()) {
      data.put("commandTarget", CommandTarget.INSTANCE.name());
      if (step.role != null) {
        data.put("role", step.role);
      }
      data.put("instance", step.instanceId);
    } else if (step.role != null && !step.role.isBlank()) {
      data.put("commandTarget", CommandTarget.ROLE.name());
      data.put("role", step.role);
    }

    log.info("Scenario step {} at {}ms -> role={} instance={} type={}",
        step.stepId, step.dueMillis, step.role, step.instanceId, type);
    context.configFanout().publishConfigUpdate(data, "scenario");
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
      // swarmSteps currently ignored; reserved for future swarm-level commands
      return due;
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
    public final long elapsedMillis;
    public final String nextStepId;
    public final String nextStepName;
    public final Long nextDueMillis;

    private Progress(String lastStepId,
                     String lastStepName,
                     long elapsedMillis,
                     String nextStepId,
                     String nextStepName,
                     Long nextDueMillis) {
      this.lastStepId = lastStepId;
      this.lastStepName = lastStepName;
      this.elapsedMillis = elapsedMillis;
      this.nextStepId = nextStepId;
      this.nextStepName = nextStepName;
      this.nextDueMillis = nextDueMillis;
    }

    static Progress initial(Schedule schedule) {
      StepInstance next = earliest(schedule);
      return new Progress(null, null, 0L,
          next != null ? next.stepId : null,
          next != null ? next.name : null,
          next != null ? next.dueMillis : null);
    }

    static Progress update(Schedule schedule, StepInstance last, long elapsedMillis) {
      StepInstance next = earliestAfter(schedule, elapsedMillis);
      return new Progress(
          last != null ? last.stepId : null,
          last != null ? last.name : null,
          elapsedMillis,
          next != null ? next.stepId : null,
          next != null ? next.name : null,
          next != null ? next.dueMillis : null);
    }

    static Progress current(Schedule schedule, long elapsedMillis, Progress previous) {
      // If we have a previous progress instance whose elapsedMillis is ahead of
      // the current snapshot, keep it to avoid moving backwards; otherwise
      // recompute based on the elapsed time.
      if (previous != null && previous.elapsedMillis >= elapsedMillis) {
        return previous;
      }
      StepInstance last = latestBefore(schedule, elapsedMillis);
      StepInstance next = earliestAfter(schedule, elapsedMillis);
      return new Progress(
          last != null ? last.stepId : null,
          last != null ? last.name : null,
          elapsedMillis,
          next != null ? next.stepId : null,
          next != null ? next.name : null,
          next != null ? next.dueMillis : null);
    }

    private static StepInstance earliest(Schedule schedule) {
      StepInstance candidate = null;
      for (StepInstance s : schedule.beeSteps) {
        if (candidate == null || s.dueMillis < candidate.dueMillis) {
          candidate = s;
        }
      }
      for (StepInstance s : schedule.swarmSteps) {
        if (candidate == null || s.dueMillis < candidate.dueMillis) {
          candidate = s;
        }
      }
      return candidate;
    }

    private static StepInstance earliestAfter(Schedule schedule, long elapsedMillis) {
      StepInstance candidate = null;
      for (StepInstance s : schedule.beeSteps) {
        if (s.dueMillis <= elapsedMillis) {
          continue;
        }
        if (candidate == null || s.dueMillis < candidate.dueMillis) {
          candidate = s;
        }
      }
      for (StepInstance s : schedule.swarmSteps) {
        if (s.dueMillis <= elapsedMillis) {
          continue;
        }
        if (candidate == null || s.dueMillis < candidate.dueMillis) {
          candidate = s;
        }
      }
      return candidate;
    }

    private static StepInstance latestBefore(Schedule schedule, long elapsedMillis) {
      StepInstance candidate = null;
      for (StepInstance s : schedule.beeSteps) {
        if (s.dueMillis > elapsedMillis) {
          continue;
        }
        if (candidate == null || s.dueMillis > candidate.dueMillis) {
          candidate = s;
        }
      }
      for (StepInstance s : schedule.swarmSteps) {
        if (s.dueMillis > elapsedMillis) {
          continue;
        }
        if (candidate == null || s.dueMillis > candidate.dueMillis) {
          candidate = s;
        }
      }
      return candidate;
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
        return null;
      }
      String type = map.getOrDefault("type", "config-update").toString();
      Map<String, Object> cfg = Map.of();
      Object cfgObj = map.get("config");
      if (cfgObj instanceof Map<?, ?> cfgRaw) {
        cfg = (Map<String, Object>) cfgRaw;
      }
      return new StepInstance(stepId, name, type, cfg, instanceId, role, millis);
    }
  }
}
