package io.pockethive.orchestrator.api;

import io.pockethive.orchestrator.app.ContainerLifecycleManager;
import io.pockethive.orchestrator.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/swarms")
public class SwarmController {
    private final ContainerLifecycleManager lifecycleManager;
    private final ScenarioRepository scenarioRepository;
    private final SwarmPlanRegistry planRegistry;
    private final SwarmRegistry swarmRegistry;

    public SwarmController(ContainerLifecycleManager lifecycleManager,
                           ScenarioRepository scenarioRepository,
                           SwarmPlanRegistry planRegistry,
                           SwarmRegistry swarmRegistry) {
        this.lifecycleManager = lifecycleManager;
        this.scenarioRepository = scenarioRepository;
        this.planRegistry = planRegistry;
        this.swarmRegistry = swarmRegistry;
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateSwarmRequest req) {
        ScenarioPlan scenario = scenarioRepository.find(req.scenarioId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario not found"));
        SwarmPlan plan = new SwarmPlan(req.id(), scenario.template());
        Swarm swarm = lifecycleManager.startSwarm(req.id(), plan.template().getImage());
        planRegistry.register(swarm.getContainerId(), plan);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        lifecycleManager.stopSwarm(id);
        swarmRegistry.find(id).ifPresent(s -> planRegistry.remove(s.getContainerId()));
        swarmRegistry.remove(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateSwarmRequest(String id, String scenarioId) {}
}

