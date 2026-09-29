package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.CompatibilityQueries;
import io.pockethive.tcpmock.service.CompatibilityCommands;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Responsibility: bind compatibility HTTP routes to commands and queries.
 * Must not: project state or implement reset sequencing.
 * Contract: RESP-TCP-MOCK-ADMIN — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-admin.
 */
@RestController
@RequestMapping("/__admin")
public class WireMockCompatController {

    private final CompatibilityQueries queries;
    private final CompatibilityCommands commands;

    public WireMockCompatController(CompatibilityQueries queries, CompatibilityCommands commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping("/requests")
    public Map<String, Object> getRequests() {
        return queries.getRequests();
    }

    @GetMapping("/mappings")
    public Map<String, Object> getMappings() {
        return queries.getMappings();
    }

    @GetMapping("/health")
    public Map<String, String> getHealth() {
        return Map.of("status", "UP", "version", "tcp-mock-1.0");
    }

    @GetMapping("/scenarios")
    public Map<String, Object> getScenarios() {
        return queries.getScenarios();
    }

    @GetMapping("/requests/unmatched")
    public Map<String, Object> getUnmatchedRequests() {
        return queries.getUnmatchedRequests();
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        return commands.reset();
    }

    @PostMapping("/scenarios/{name}/reset")
    public Map<String, String> resetScenario(@PathVariable("name") String name) {
        return commands.resetScenario(name);
    }

    @PutMapping("/scenarios/{name}/state")
    public Map<String, String> setScenarioState(@PathVariable("name") String name, @RequestBody Map<String, String> body) {
        return commands.setScenarioState(name, body);
    }

    @DeleteMapping("/scenarios/{name}")
    public Map<String, String> deleteScenario(@PathVariable("name") String name) {
        return commands.deleteScenario(name);
    }




}
