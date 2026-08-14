package io.pockethive.swarmcontroller;

import java.util.Map;

/** Required read-only scenario progress projection. */
public interface SwarmScenarioProjection {

  Map<String, Object> scenarioProgress();
}
