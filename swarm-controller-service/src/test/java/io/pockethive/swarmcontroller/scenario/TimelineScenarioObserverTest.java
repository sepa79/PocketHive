package io.pockethive.swarmcontroller.scenario;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.manager.runtime.ManagerLifecycle;
import io.pockethive.manager.runtime.ManagerMetrics;
import io.pockethive.manager.runtime.ManagerStatus;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.ScenarioContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TimelineScenarioObserverTest {

  @Test
  void emitsJournalLifecycleCallbacksForSteps() {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    TimelineScenarioObserver observer = mock(TimelineScenarioObserver.class);
    TimelineScenario scenario = new TimelineScenario("test", mapper, observer);

    String planJson = """
        {
          "bees": [
            {
              "instanceId": "gen-1",
              "role": "generator",
              "steps": [
                {
                  "stepId": "s-1",
                  "name": "cfg",
                  "time": "PT0S",
                  "type": "config-update",
                  "config": {"hello": "world"}
                }
              ]
            }
          ]
        }
        """;
    scenario.applyPlan(planJson);

    ManagerLifecycle lifecycle = mock(ManagerLifecycle.class);
    ConfigFanout configFanout = mock(ConfigFanout.class);
    ScenarioContext ctx = new ScenarioContext("sw1", lifecycle, configFanout);
    ManagerRuntimeView view = new ManagerRuntimeView(
        ManagerStatus.RUNNING,
        new ManagerMetrics(0, 0, 0, 0, 0),
        Map.of());

    scenario.onTick(view, ctx);

    InOrder ordered = inOrder(observer);
    ordered.verify(observer).onPlanLoaded(1, 0);
    ordered.verify(observer).onTimelineStarted(org.mockito.ArgumentMatchers.any());
    ordered.verify(observer).onStepStarted("s-1", "cfg", 0L, "config-update", "generator", "gen-1", false);
    ordered.verify(observer).onStepCompleted("s-1", "cfg", 0L, "config-update", "generator", "gen-1", false);
    ordered.verify(observer).onPlanCompleted(null, null);

    verify(configFanout).publishConfigUpdate(
        org.mockito.ArgumentMatchers.eq(ControlScope.forInstance("sw1", "generator", "gen-1")),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("scenario"));
  }

  @Test
  void planCompletedIsEmittedOnceWhenScheduleDoesNotLoop() {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    TimelineScenarioObserver observer = mock(TimelineScenarioObserver.class);
    TimelineScenario scenario = new TimelineScenario("test", mapper, observer);

    String planJson = """
        {
          "swarm": [
            {"stepId": "s-1", "time": "PT0S", "type": "start"}
          ]
        }
        """;
    scenario.applyPlan(planJson);

    ManagerLifecycle lifecycle = mock(ManagerLifecycle.class);
    ConfigFanout configFanout = mock(ConfigFanout.class);
    ScenarioContext ctx = new ScenarioContext("sw1", lifecycle, configFanout);
    ManagerRuntimeView view = new ManagerRuntimeView(
        ManagerStatus.RUNNING,
        new ManagerMetrics(0, 0, 0, 0, 0),
        Map.of());

    scenario.onTick(view, ctx);
    scenario.onTick(view, ctx);

    verify(observer, times(1)).onPlanCompleted(null, null);
  }
}
