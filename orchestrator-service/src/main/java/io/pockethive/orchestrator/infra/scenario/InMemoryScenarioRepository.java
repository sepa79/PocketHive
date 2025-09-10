package io.pockethive.orchestrator.infra.scenario;

import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.ScenarioRepository;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryScenarioRepository implements ScenarioRepository {
    private final Map<String, ScenarioPlan> scenarios = new ConcurrentHashMap<>();

    public InMemoryScenarioRepository(SwarmTemplate template) {
        scenarios.put("default", new ScenarioPlan(template));
    }

    @Override
    public Optional<ScenarioPlan> find(String scenarioId) {
        return Optional.ofNullable(scenarios.get(scenarioId));
    }
}

