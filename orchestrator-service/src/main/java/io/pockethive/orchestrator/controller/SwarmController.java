package io.pockethive.orchestrator.controller;

import io.pockethive.orchestrator.model.SwarmTemplate;
import io.pockethive.orchestrator.model.SwarmStatus;
import io.pockethive.orchestrator.service.SwarmOrchestrator;
import io.pockethive.orchestrator.service.SwarmRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/swarms")
public class SwarmController {

    @Autowired
    private SwarmOrchestrator swarmOrchestrator;

    @Autowired
    private SwarmRegistry swarmRegistry;

    @PostMapping
    public ResponseEntity<SwarmStatus> createSwarm(@RequestBody SwarmTemplate template) {
        try {
            SwarmStatus status = swarmOrchestrator.createSwarm(template);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{swarmId}")
    public ResponseEntity<Void> destroySwarm(@PathVariable String swarmId) {
        try {
            swarmOrchestrator.destroySwarm(swarmId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<SwarmStatus>> getAllSwarms() {
        List<SwarmStatus> swarms = swarmRegistry.getAllSwarms();
        return ResponseEntity.ok(swarms);
    }

    @GetMapping("/{swarmId}")
    public ResponseEntity<SwarmStatus> getSwarm(@PathVariable String swarmId) {
        SwarmStatus swarm = swarmRegistry.getSwarm(swarmId);
        if (swarm != null) {
            return ResponseEntity.ok(swarm);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}