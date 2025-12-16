package io.pockethive.swarmcontroller.scenario;

import java.time.Instant;

/**
 * Optional observer for {@link TimelineScenario} lifecycle events.
 * <p>
 * Intended for high-signal projections (e.g. journaling) without coupling the scenario engine
 * to any specific sink or transport.
 */
public interface TimelineScenarioObserver {

  void onPlanCleared();

  void onPlanLoaded(int beeSteps, int swarmSteps);

  void onPlanParseFailed(String message);

  void onPlanReset();

  void onTimelineStarted(Instant startedAt);

  void onStepStarted(String stepId,
                     String name,
                     long dueMillis,
                     String type,
                     String role,
                     String instanceId,
                     boolean swarmLifecycleStep);

  void onStepCompleted(String stepId,
                       String name,
                       long dueMillis,
                       String type,
                       String role,
                       String instanceId,
                       boolean swarmLifecycleStep);

  void onStepFailed(String stepId,
                    String name,
                    long dueMillis,
                    String type,
                    String role,
                    String instanceId,
                    boolean swarmLifecycleStep,
                    String message);

  void onRunCompleted(Integer totalRuns, Integer runsRemaining);

  void onPlanCompleted(Integer totalRuns, Integer runsRemaining);
}

