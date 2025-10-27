package io.pockethive.scenarios;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AvailableScenarioRegistry {

    private final ScenarioService scenarioService;

    public AvailableScenarioRegistry(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    public List<ScenarioSummary> list() {
        return scenarioService.listAvailableSummaries();
    }

    public Optional<Scenario> find(String id) {
        return scenarioService.findAvailable(id);
    }
}
