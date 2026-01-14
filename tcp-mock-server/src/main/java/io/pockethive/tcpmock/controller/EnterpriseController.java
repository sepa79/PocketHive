package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.StateManager;
import io.pockethive.tcpmock.service.RequestVerificationService;
import io.pockethive.tcpmock.model.MockState;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/enterprise")
public class EnterpriseController {

    private final StateManager stateManager;
    private final RequestVerificationService verificationService;

    public EnterpriseController(StateManager stateManager, RequestVerificationService verificationService) {
        this.stateManager = stateManager;
        this.verificationService = verificationService;
    }

    @GetMapping("/scenarios")
    public Map<String, MockState> getScenarios() {
        return stateManager.getAllScenarios();
    }

    @PostMapping("/scenarios/{scenarioName}/start")
    public ResponseEntity<Map<String, String>> startScenario(@PathVariable("scenarioName") String scenarioName) {
        stateManager.getOrCreateScenarioState(scenarioName);
        return ResponseEntity.ok(Map.of("status", "started", "scenario", scenarioName));
    }

    @PostMapping("/scenarios/{scenarioName}/reset")
    public ResponseEntity<Map<String, String>> resetScenario(@PathVariable("scenarioName") String scenarioName) {
        stateManager.resetScenario(scenarioName);
        return ResponseEntity.ok(Map.of("status", "reset", "scenario", scenarioName));
    }

    @PostMapping("/scenarios/reset-all")
    public ResponseEntity<Map<String, String>> resetAllScenarios() {
        stateManager.resetAllScenarios();
        return ResponseEntity.ok(Map.of("status", "all scenarios reset"));
    }

    @PutMapping("/scenarios/{scenarioName}/state")
    public ResponseEntity<Map<String, String>> updateScenarioState(
            @PathVariable("scenarioName") String scenarioName,
            @RequestBody Map<String, String> request) {
        String newState = request.get("state");
        stateManager.updateScenarioState(scenarioName, newState);
        return ResponseEntity.ok(Map.of("status", "updated", "scenario", scenarioName, "state", newState));
    }

    @PutMapping("/scenarios/{scenarioName}/variables")
    public ResponseEntity<Map<String, String>> setScenarioVariable(
            @PathVariable("scenarioName") String scenarioName,
            @RequestBody Map<String, Object> variables) {
        variables.forEach((key, value) ->
            stateManager.setScenarioVariable(scenarioName, key, value));
        return ResponseEntity.ok(Map.of("status", "variables updated", "scenario", scenarioName));
    }

    @GetMapping("/verification/summary")
    public Map<String, Object> getVerificationSummary() {
        return verificationService.getVerificationSummary();
    }

    @PostMapping("/verification/expect")
    public ResponseEntity<Map<String, String>> addExpectation(@RequestBody Map<String, Object> expectation) {
        String pattern = (String) expectation.get("pattern");
        String countType = (String) expectation.get("countType"); // exactly, atLeast, atMost
        Integer count = (Integer) expectation.get("count");

        verificationService.addExpectation(pattern, countType, count);
        return ResponseEntity.ok(Map.of("status", "expectation added"));
    }

    @GetMapping("/verification/results")
    public List<Map<String, Object>> getVerificationResults() {
        return verificationService.getVerificationResults();
    }

    @PostMapping("/verification/reset")
    public ResponseEntity<Map<String, String>> resetVerification() {
        verificationService.reset();
        return ResponseEntity.ok(Map.of("status", "verification reset"));
    }
}
