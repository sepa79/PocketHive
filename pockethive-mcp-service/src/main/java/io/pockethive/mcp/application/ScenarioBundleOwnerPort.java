package io.pockethive.mcp.application;

import java.nio.file.Path;

/**
 * Responsibility: Define the scenario bundle owner application port.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public interface ScenarioBundleOwnerPort {
    OwnerValidationResult validate(Path archive);
    Object create(Path archive);
    Object replace(String scenarioId, Path archive);
    OwnerScenarioProjection get(String scenarioId);
}
