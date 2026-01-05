package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.RequestStore;
import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.service.RecordingMode;
import io.pockethive.tcpmock.model.TcpRequest;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class WebController {

  private final RequestStore requestStore;
  private final MessageTypeRegistry messageTypeRegistry;
  private final RecordingMode recordingMode;

  public WebController(RequestStore requestStore, MessageTypeRegistry messageTypeRegistry, RecordingMode recordingMode) {
    this.requestStore = requestStore;
    this.messageTypeRegistry = messageTypeRegistry;
    this.recordingMode = recordingMode;
  }

  @GetMapping("/")
  public String index() {
    return "forward:/index-complete.html";
  }

  @GetMapping("/basic")
  public String basic() {
    return "forward:/index.html";
  }

  @GetMapping("/advanced")
  public String advanced() {
    return "forward:/index-advanced.html";
  }

  @GetMapping("/docs/{filename}")
  @ResponseBody
  public ResponseEntity<String> getDocumentation(@PathVariable String filename) {
    try {
      if (!filename.endsWith(".md") || filename.contains("..") || filename.contains("/")) {
        return ResponseEntity.badRequest().body("Invalid filename");
      }
      
      java.nio.file.Path path = java.nio.file.Paths.get("/app", filename);
      if (java.nio.file.Files.exists(path)) {
        String content = java.nio.file.Files.readString(path);
        return ResponseEntity.ok()
            .header("Content-Type", "text/markdown; charset=UTF-8")
            .body(content);
      }
      
      java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("docs/" + filename);
      if (is != null) {
        String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header("Content-Type", "text/markdown; charset=UTF-8")
            .body(content);
      }
      
      return ResponseEntity.notFound().build();
    } catch (Exception e) {
      return ResponseEntity.status(500).body("Error reading documentation: " + e.getMessage());
    }
  }

  @GetMapping("/api/requests")
  @ResponseBody
  public List<Map<String, Object>> getRequests() {
    return requestStore.getAllRequests().stream()
        .map(this::requestToMap)
        .collect(Collectors.toList());
  }

  @DeleteMapping("/api/requests")
  @ResponseBody
  public ResponseEntity<Map<String, String>> clearRequests() {
    requestStore.clearRequests();
    return ResponseEntity.ok(Map.of("status", "cleared"));
  }

  @GetMapping("/api/ui/mappings")
  @ResponseBody
  public List<Map<String, Object>> getMappings() {
    return messageTypeRegistry.getAllMappings().stream()
        .map(this::mappingToMap)
        .collect(Collectors.toList());
  }

  @PostMapping("/api/ui/mappings")
  @ResponseBody
  public ResponseEntity<Map<String, String>> addMapping(@RequestBody Map<String, Object> mappingData) {
    try {
      String id = (String) mappingData.get("id");
      String pattern = (String) mappingData.get("pattern");
      String response = (String) mappingData.get("response");
      String description = (String) mappingData.get("description");
      Integer priority = mappingData.get("priority") != null ? (Integer) mappingData.get("priority") : 1;
      String delimiter = (String) mappingData.getOrDefault("delimiter", "\n");
      Integer fixedDelayMs = mappingData.get("fixedDelayMs") != null ? (Integer) mappingData.get("fixedDelayMs") : null;
      String scenarioName = (String) mappingData.get("scenarioName");
      String requiredState = (String) mappingData.get("requiredState");
      String newState = (String) mappingData.get("newState");

      if (id == null || id.trim().isEmpty()) {
        id = "mapping-" + System.currentTimeMillis();
      }

      MessageTypeMapping mapping = new MessageTypeMapping(id, pattern, response, description);
      mapping.setPriority(priority);
      mapping.setResponseDelimiter(delimiter);
      mapping.setFixedDelayMs(fixedDelayMs);

      // Advanced matching
      if (mappingData.containsKey("advancedMatching")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> advMatching = (Map<String, Object>) mappingData.get("advancedMatching");
        mapping.setAdvancedMatching(advMatching);
      }

      // Scenario
      if (scenarioName != null && !scenarioName.trim().isEmpty()) {
        mapping.setScenarioName(scenarioName);
        mapping.setRequiredScenarioState(requiredState);
        mapping.setNewScenarioState(newState);
      }

      messageTypeRegistry.addMapping(mapping);
      return ResponseEntity.ok(Map.of("status", "created", "id", id));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @DeleteMapping("/api/ui/mappings/{id}")
  @ResponseBody
  public ResponseEntity<Map<String, String>> deleteMapping(@PathVariable String id) {
    messageTypeRegistry.removeMapping(id);
    return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
  }

  @PostMapping("/api/test")
  @ResponseBody
  public ResponseEntity<Map<String, String>> sendTestMessage(@RequestBody Map<String, Object> request) {
    String message = (String) request.get("message");
    if (message == null || message.trim().isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("success", "false", "error", "Message is required"));
    }

    try {
      long startTime = System.currentTimeMillis();
      // Process the message through the registry to get a real response
      io.pockethive.tcpmock.model.ProcessedResponse processedResponse = messageTypeRegistry.processMessage(message);
      long duration = System.currentTimeMillis() - startTime;

      String responseText = processedResponse.getResponse();

      // Handle special responses
      if (processedResponse.hasFault()) {
        responseText = "FAULT: " + processedResponse.getFault().name();
      } else if (processedResponse.hasProxy()) {
        responseText = "PROXY: " + processedResponse.getProxyTarget();
      }

      // Create a test request record
      TcpRequest testRequest = new TcpRequest(
        "test-" + System.currentTimeMillis(),
        "127.0.0.1:test",
        message,
        Map.of("test", "true"),
        "TEST",
        java.time.Instant.now(),
        responseText
      );

      requestStore.addRequest(testRequest);

      // If recording is enabled, increment the recorded count
      if (recordingMode.isRecording()) {
        recordingMode.incrementRecordedCount();
      }

      return ResponseEntity.ok(Map.of(
        "success", "true",
        "response", responseText,
        "duration", String.valueOf(duration),
        "delimiter", processedResponse.getDelimiter(),
        "delay", processedResponse.hasDelay() ? String.valueOf(processedResponse.getDelayMs()) : "0"
      ));
    } catch (Exception e) {
      Map<String, String> errorMap = Map.of(
        "success", "false",
        "error", e.getMessage()
      );
      return ResponseEntity.status(500).body(errorMap);
    }
  }

  @GetMapping("/api/enterprise/recording/status")
  @ResponseBody
  public Map<String, Object> getRecordingStatus() {
    return Map.of(
      "recording", recordingMode.isRecording(),
      "recordedCount", recordingMode.getRecordedCount()
    );
  }

  @PostMapping("/api/enterprise/recording/start")
  @ResponseBody
  public Map<String, Object> startRecording() {
    recordingMode.startRecording();
    return Map.of("recording", true, "status", "started");
  }

  @PostMapping("/api/enterprise/recording/stop")
  @ResponseBody
  public Map<String, Object> stopRecording() {
    recordingMode.stopRecording();
    return Map.of("recording", false, "status", "stopped");
  }

  private Map<String, Object> requestToMap(TcpRequest request) {
    // A request is "matched" if it has a specific mapping response (not default catch-all)
    boolean matched = request.getResponse() != null &&
                     !request.getResponse().isEmpty() &&
                     !request.getResponse().startsWith("ERROR") &&
                     !request.getResponse().equals("INVALID_MESSAGE") &&
                     !request.getResponse().equals("OK") &&
                     !request.getResponse().equals("UNKNOWN_MESSAGE_TYPE");

    return Map.of(
        "id", request.getId(),
        "message", request.getMessage() != null ? request.getMessage() : "",
        "response", request.getResponse() != null ? request.getResponse() : "",
        "timestamp", request.getTimestamp().toString(),
        "matched", matched,
        "clientAddress", request.getClientAddress() != null ? request.getClientAddress() : "",
        "behavior", request.getBehavior() != null ? request.getBehavior() : ""
    );
  }

  private Map<String, Object> mappingToMap(MessageTypeMapping mapping) {
    java.util.HashMap<String, Object> result = new java.util.HashMap<>();
    result.put("id", mapping.getId());
    result.put("pattern", mapping.getRequestPattern() != null ? mapping.getRequestPattern() : "");
    result.put("response", mapping.getResponseTemplate() != null ? mapping.getResponseTemplate() : "");
    result.put("description", mapping.getDescription() != null ? mapping.getDescription() : "");
    result.put("matchCount", mapping.getMatchCount());
    result.put("priority", mapping.getPriority());
    result.put("delimiter", mapping.getResponseDelimiter() != null ? mapping.getResponseDelimiter() : "\n");
    result.put("fixedDelayMs", mapping.getFixedDelayMs() != null ? mapping.getFixedDelayMs() : 0);
    result.put("scenarioName", mapping.getScenarioName() != null ? mapping.getScenarioName() : "");
    result.put("requiredState", mapping.getRequiredScenarioState() != null ? mapping.getRequiredScenarioState() : "");
    result.put("newState", mapping.getNewScenarioState() != null ? mapping.getNewScenarioState() : "");
    result.put("hasAdvancedMatching", mapping.getAdvancedMatching() != null);
    return result;
  }
}
