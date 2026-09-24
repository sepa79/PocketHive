package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.TcpRequest;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project existing compatibility diagnostics.
 * Must not: mutate state or implement stub import conversion.
 * Contract: RESP-TCP-MOCK-ADMIN — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-admin.
 */
@Service
public class CompatibilityQueries {
    private final RequestStore requestStore;
    private final MessageTypeRegistry messageTypeRegistry;
    private final ScenarioManager scenarioManager;

    public CompatibilityQueries(RequestStore requestStore, MessageTypeRegistry messageTypeRegistry, ScenarioManager scenarioManager) {
        this.requestStore = requestStore;
        this.messageTypeRegistry = messageTypeRegistry;
        this.scenarioManager = scenarioManager;
    }

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
