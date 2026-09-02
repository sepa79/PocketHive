package io.pockethive.swarmcontroller.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.manager.runtime.ManagerMetrics;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.ScenarioContext;
import io.pockethive.manager.scenario.ScenarioEngine;
import io.pockethive.manager.scenario.ScenarioLifecyclePort;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.SwarmMetrics;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: Configure and execute the swarm timeline scenario against read-only runtime projections.
 * Must not: Own workload/controller state, readiness observations, or decide when heartbeat ticks are allowed.
 * Contract: Every tick reads current owner state and delegates scenario lifecycle commands through its port.
 */
public final class SwarmScenarioCoordinator {

  private static final Logger log = LoggerFactory.getLogger(SwarmScenarioCoordinator.class);

  private final TimelineScenario timeline;
  private final ScenarioEngine engine;
  private final Supplier<WorkloadState> workloadState;
  private final Supplier<SwarmMetrics> metrics;

  public SwarmScenarioCoordinator(
      ObjectMapper mapper,
      String swarmId,
      ConfigFanout configFanout,
      TimelineScenarioObserver observer,
      ScenarioLifecyclePort lifecycle,
      Supplier<WorkloadState> workloadState,
      Supplier<SwarmMetrics> metrics) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(configFanout, "configFanout");
    Objects.requireNonNull(observer, "observer");
    this.workloadState = Objects.requireNonNull(workloadState, "workloadState");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.timeline = new TimelineScenario("default", mapper, observer);
    this.engine = new ScenarioEngine(
        List.of(timeline),
        this::runtimeView,
        new ScenarioContext(swarmId, Objects.requireNonNull(lifecycle, "lifecycle"), configFanout));
  }

  public void applyPlan(String planJson) {
    if (planJson == null || planJson.isBlank()) {
      log.info("Clearing scenario plan");
    } else {
      log.info("Applying scenario plan ({} bytes)", planJson.length());
      if (log.isDebugEnabled()) {
        log.debug("Scenario plan payload: {}", snippet(planJson));
      }
    }
    timeline.applyPlan(planJson);
  }

  public void reset() {
    timeline.reset();
  }

  public void setRunCount(Integer runs) {
    timeline.setRunCount(runs);
  }

  public TimelineScenario.Progress progress() {
    return timeline.snapshotProgress();
  }

  public void tick() {
    engine.tick();
  }

  ManagerRuntimeView runtimeView() {
    SwarmMetrics currentMetrics = Objects.requireNonNull(metrics.get(), "metrics");
    return new ManagerRuntimeView(
        Objects.requireNonNull(workloadState.get(), "workloadState"),
        new ManagerMetrics(
            currentMetrics.desired(),
            currentMetrics.healthy(),
            currentMetrics.running(),
            currentMetrics.enabled(),
            Objects.requireNonNull(currentMetrics.watermark(), "metrics.watermark").toEpochMilli()),
        Collections.emptyMap());
  }

  private static String snippet(String payload) {
    String trimmed = payload.strip();
    return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
  }
}
