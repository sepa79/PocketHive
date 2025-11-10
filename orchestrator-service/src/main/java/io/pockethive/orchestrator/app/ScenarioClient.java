package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.ScenarioPlan;

/**
 * Client for retrieving swarm templates.
 */
public interface ScenarioClient {
    ScenarioPlan fetchScenario(String templateId) throws Exception;
}
