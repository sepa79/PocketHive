package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.service.RequestStore;
import io.pockethive.tcpmock.service.ManualTestService;
import io.pockethive.tcpmock.service.RecordingMode;
import io.pockethive.tcpmock.service.RequestLogProjection;
import io.pockethive.tcpmock.service.DocumentationReader;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Responsibility: bind HTTP for UI diagnostics, manual tests and recording controls.
 * Must not: execute tests, project logs or read files.
 * Contract: RESP-TCP-MOCK-WEB-TOOLS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-web-tools.
 */
@Controller
public class WebController {

  private final RequestStore requestStore;
  private final ManualTestService manualTests;
  private final RequestLogProjection requestProjection;
  private final DocumentationReader documentation;
  private final RecordingMode recordingMode;

  public WebController(RequestStore requestStore, ManualTestService manualTests, RecordingMode recordingMode, RequestLogProjection requestProjection, DocumentationReader documentation) {
    this.requestStore = requestStore;
    this.manualTests = manualTests;
    this.requestProjection = requestProjection;
    this.documentation = documentation;
    this.recordingMode = recordingMode;
  }

  @GetMapping("/")
  public String index() {
    return "forward:/index.html";
  }

  @GetMapping("/docs/{filename}")
  @ResponseBody
  public ResponseEntity<String> getDocumentation(@PathVariable("filename") String filename) {
    try {
      if (!filename.endsWith(".md") || filename.contains("..") || filename.contains("/")) {
        return ResponseEntity.badRequest().body("Invalid filename");
      }

      String content = documentation.read(filename);
      if (content != null) {
        return ResponseEntity.ok().header("Content-Type", "text/markdown; charset=UTF-8").body(content);
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
        .map(requestProjection::toMap)
        .collect(Collectors.toList());
  }

  @DeleteMapping("/api/requests")
  @ResponseBody
  public ResponseEntity<Map<String, String>> clearRequests() {
    requestStore.clearRequests();
    return ResponseEntity.ok(Map.of("status", "cleared"));
  }

  @PostMapping("/api/test")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> sendTestMessage(@RequestBody Map<String, Object> request) {
    String message = (String) request.get("message");
    String transport = (String) request.getOrDefault("transport", "mock");
    String host = (String) request.getOrDefault("host", "localhost");
    Integer port = (Integer) request.getOrDefault("port", 8080);
    String delimiter = (String) request.getOrDefault("delimiter", "\n");
    Integer timeout = (Integer) request.getOrDefault("timeout", 5000);
    Boolean ssl = (Boolean) request.getOrDefault("ssl", false);
    Boolean sslVerify = (Boolean) request.getOrDefault("sslVerify", false);
    String encoding = (String) request.getOrDefault("encoding", "utf-8");

    if (message == null || message.trim().isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Message is required"));
    }

    try {
      return ResponseEntity.ok(manualTests.execute(message, transport, host, port, delimiter, timeout, ssl, sslVerify, encoding));

    } catch (java.net.ConnectException e) {
      return ResponseEntity.status(500).body(Map.of(
        "success", false,
        "error", "Connection refused: " + e.getMessage(),
        "errorType", "CONNECTION_REFUSED",
        "transport", transport
      ));
    } catch (java.net.SocketTimeoutException e) {
      return ResponseEntity.status(500).body(Map.of(
        "success", false,
        "error", "Connection timeout after " + timeout + "ms",
        "errorType", "TIMEOUT",
        "transport", transport
      ));
    } catch (Exception e) {
      return ResponseEntity.status(500).body(Map.of(
        "success", false,
        "error", e.getMessage(),
        "errorType", "GENERAL_ERROR",
        "transport", transport
      ));
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

}
