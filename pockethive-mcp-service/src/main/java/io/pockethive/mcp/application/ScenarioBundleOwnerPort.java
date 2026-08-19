package io.pockethive.mcp.application;

import java.nio.file.Path;

public interface ScenarioBundleOwnerPort {
    OwnerValidationResult validate(Path archive);
    Object create(Path archive);
    Object replace(String scenarioId, Path archive);
    OwnerScenarioProjection get(String scenarioId);
}
