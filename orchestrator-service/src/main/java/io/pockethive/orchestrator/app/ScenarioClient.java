package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.SwarmTemplate;

/**
 * Client for retrieving swarm templates.
 */
public interface ScenarioClient {
    SwarmTemplate fetchTemplate(String templateId) throws Exception;
}
