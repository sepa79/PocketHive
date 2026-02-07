package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.service.ScenarioManager;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.StubMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/__admin")
public class AdminController {

    private final MessageTypeRegistry registry;
    private final ScenarioManager scenarioManager;

    public AdminController(MessageTypeRegistry registry, ScenarioManager scenarioManager) {
        this.registry = registry;
        this.scenarioManager = scenarioManager;
    }

    @PostMapping("/mappings")
    public ResponseEntity<Map<String, String>> createStubMapping(@RequestBody StubMapping stub) {
        MessageTypeMapping mapping = new MessageTypeMapping(
            stub.getId(),
            stub.getRequest().getBodyPattern(),
            stub.getResponse().getBody(),
            "WireMock-style stub"
        );
        registry.addMapping(mapping);
        return ResponseEntity.ok(Map.of("status", "Created", "id", stub.getId()));
    }

    @GetMapping("/mappings")
    public ResponseEntity<Map<String, Object>> getAllStubMappings() {
        return ResponseEntity.ok(Map.of(
            "mappings", registry.getAllMappings(),
            "meta", Map.of("total", registry.getAllMappings().size())
        ));
    }

    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<Void> deleteStubMapping(@PathVariable("id") String id) {
        try {
            registry.removeMapping(id);
        } catch (Exception ignored) {
            // Idempotent deletion; ignore missing entries.
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mappings/reset")
    public ResponseEntity<Void> resetAllStubMappings() {
        try {
            registry.getAllMappings().forEach(m -> registry.removeMapping(m.getId()));
        } catch (Exception ignored) {
            // Idempotent reset; ignore missing entries.
        }
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
