package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.AdminMappingService;
import io.pockethive.tcpmock.service.ScenarioManager;
import io.pockethive.tcpmock.model.StubMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Responsibility: expose existing admin mapping and scenario operations.
 * Must not: coordinate mapping mutations, implement conversion or access files.
 * Contract: RESP-TCP-MOCK-ADMIN — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-admin.
 */
@RestController
@RequestMapping("/api/__admin")
public class AdminController {

    private final AdminMappingService mappings;
    private final ScenarioManager scenarioManager;

    public AdminController(AdminMappingService mappings, ScenarioManager scenarioManager) {
        this.mappings = mappings;
        this.scenarioManager = scenarioManager;
    }

    @PostMapping("/mappings")
    public ResponseEntity<Map<String, String>> createStubMapping(@RequestBody StubMapping stub) {
        return ResponseEntity.ok(mappings.create(stub));
    }

    @GetMapping("/mappings")
    public ResponseEntity<Map<String, Object>> getAllStubMappings() {
        return ResponseEntity.ok(mappings.list());
    }

    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<Void> deleteStubMapping(@PathVariable("id") String id) {
        mappings.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mappings/reset")
    public ResponseEntity<Void> resetAllStubMappings() {
        mappings.reset();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/scenarios")
    public ResponseEntity<Map<String, String>> getAllScenarios() {
        return ResponseEntity.ok(scenarioManager.getAllScenarios());
    }

    @PostMapping("/scenarios/reset")
    public ResponseEntity<Void> resetAllScenarios() {
        scenarioManager.resetAllScenarios();
        return ResponseEntity.ok().build();
    }

    @PutMapping("/scenarios/{name}/state")
    public ResponseEntity<Void> setScenarioState(@PathVariable("name") String name, @RequestBody Map<String, String> body) {
        scenarioManager.setScenarioState(name, body.get("state"));
        return ResponseEntity.ok().build();
    }
}
