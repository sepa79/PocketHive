package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.swarm.model.SutEnvironment;
import java.util.Map;
import java.util.List;

/**
 * Client for retrieving swarm templates and related Scenario metadata.
 */
public interface ScenarioClient {
    ScenarioPlan fetchScenario(String templateId) throws Exception;

    /**
     * Prepare a per-swarm runtime directory for the given scenario template and swarm id.
     * The returned path is the host directory that should be mounted into workers.
     */
    String prepareScenarioRuntime(String templateId, String swarmId) throws Exception;

    /**
     * Resolve a bundle-local System Under Test (SUT) environment by id for a specific scenario.
     */
    SutEnvironment fetchScenarioSut(String templateId,
                                    String sutId,
                                    String correlationId,
                                    String idempotencyKey) throws Exception;

    /**
     * Resolve scenario variables for a given (profileId, sutId) selection.
     * <p>
     * If the scenario bundle does not contain {@code variables.yaml}, implementations must return
     * an empty map and no warnings.
     */
    ResolvedVariables resolveScenarioVariables(String templateId,
                                               String profileId,
                                               String sutId,
                                               String correlationId,
                                               String idempotencyKey) throws Exception;

    record ResolvedVariables(String profileId, String sutId, Map<String, Object> vars, List<String> warnings) {
    }
}
