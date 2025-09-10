package io.pockethive.orchestrator.domain;

import java.util.Optional;

/**
 * Repository for retrieving Scenario plans.
 */
public interface ScenarioRepository {
    Optional<ScenarioPlan> find(String scenarioId);
}

