package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.TcpRequest;
import io.pockethive.tcpmock.service.RequestStore;
import io.pockethive.tcpmock.service.ScenarioManager;
import io.pockethive.tcpmock.service.MessageTypeRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/__admin")
public class WireMockCompatController {

    @Autowired
    private RequestStore requestStore;

    @Autowired
    private MessageTypeRegistry messageTypeRegistry;

    @Autowired
    private ScenarioManager scenarioManager;

    @GetMapping("/requests")
    public Map<String, Object> getRequests() {
        List<TcpRequest> requests = requestStore.getAllRequests();

        List<Map<String, Object>> wireMockRequests = requests.stream()
            .map(this::convertToWireMockFormat)
            .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("requests", wireMockRequests);
        response.put("meta", Map.of("total", requests.size()));
        return response;
    }

    @GetMapping("/mappings")
    public Map<String, Object> getMappings() {
        Collection<MessageTypeMapping> mappings = messageTypeRegistry.getAllMappings();

        List<Map<String, Object>> wireMockMappings = mappings.stream()
            .map(this::convertMappingToWireMockFormat)
            .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("mappings", wireMockMappings);
        response.put("meta", Map.of("total", mappings.size()));
        return response;
    }

    @GetMapping("/health")
    public Map<String, String> getHealth() {
        return Map.of("status", "UP", "version", "tcp-mock-1.0");
    }

    @GetMapping("/scenarios")
    public Map<String, Object> getScenarios() {
        Map<String, String> scenarios = scenarioManager.getAllScenarios();
        List<Map<String, String>> scenarioList = scenarios.entrySet().stream()
            .map(entry -> Map.of(
                "name", entry.getKey(),
                "state", entry.getValue()
            ))
            .collect(Collectors.toList());
        return Map.of("scenarios", scenarioList);
    }

    @GetMapping("/requests/unmatched")
    public Map<String, Object> getUnmatchedRequests() {
        List<TcpRequest> unmatchedRequests = requestStore.getUnmatchedRequests();

        List<Map<String, Object>> wireMockRequests = unmatchedRequests.stream()
            .map(this::convertToWireMockFormat)
            .collect(Collectors.toList());

        return Map.of(
            "requests", wireMockRequests,
            "meta", Map.of("total", unmatchedRequests.size())
        );
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        requestStore.clearRequests();
        scenarioManager.resetAllScenarios();
        return Map.of("status", "Reset completed");
    }

    @PostMapping("/scenarios/{name}/reset")
    public Map<String, String> resetScenario(@PathVariable String name) {
        scenarioManager.setScenarioState(name, null);
        return Map.of("status", "Scenario reset", "scenario", name);
    }

    @PutMapping("/scenarios/{name}/state")
    public Map<String, String> setScenarioState(@PathVariable String name, @RequestBody Map<String, String> body) {
        String state = body.get("state");
        scenarioManager.setScenarioState(name, state);
        return Map.of("status", "updated", "scenario", name, "state", state);
    }

    @DeleteMapping("/scenarios/{name}")
    public Map<String, String> deleteScenario(@PathVariable String name) {
        scenarioManager.removeScenario(name);
        return Map.of("status", "deleted", "scenario", name);
    }

    private Map<String, Object> convertToWireMockFormat(TcpRequest request) {
        Map<String, Object> wireMockRequest = new HashMap<>();
        wireMockRequest.put("id", request.getId());
        wireMockRequest.put("request", Map.of(
            "method", "TCP",
            "url", "/tcp-stream",
            "body", request.getMessage()
        ));
        wireMockRequest.put("response", Map.of(
            "status", 200,
            "body", request.getResponse()
        ));
        wireMockRequest.put("loggedDate", request.getTimestamp());
        return wireMockRequest;
    }

    private Map<String, Object> convertMappingToWireMockFormat(MessageTypeMapping mapping) {
        Map<String, Object> wireMockMapping = new HashMap<>();
        wireMockMapping.put("id", mapping.getId());
        wireMockMapping.put("request", Map.of(
            "method", "TCP",
            "urlPattern", mapping.getRequestPattern()
        ));
        wireMockMapping.put("response", Map.of(
            "status", 200,
            "body", mapping.getResponseTemplate()
        ));
        wireMockMapping.put("priority", mapping.getPriority());
        return wireMockMapping;
    }
}
