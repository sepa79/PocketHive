package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.swarm.model.SutEnvironment;

/**
 * Client for retrieving swarm templates and related Scenario metadata.
 */
public interface ScenarioClient {
    ScenarioPlan fetchScenario(String templateId) throws Exception;

    /**
     * Resolve a System Under Test (SUT) environment by id.
     */
    SutEnvironment fetchSutEnvironment(String environmentId) throws Exception;
}
