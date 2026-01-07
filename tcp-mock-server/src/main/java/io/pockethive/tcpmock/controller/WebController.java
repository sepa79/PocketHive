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
  private final io.pockethive.tcpmock.service.TcpClientService tcpClientService;

  public WebController(RequestStore requestStore, MessageTypeRegistry messageTypeRegistry, RecordingMode recordingMode, io.pockethive.tcpmock.service.TcpClientService tcpClientService) {
    this.requestStore = requestStore;
    this.messageTypeRegistry = messageTypeRegistry;
    this.recordingMode = recordingMode;
    this.tcpClientService = tcpClientService;
  }

  @GetMapping("/")
  public String index() {
    return "forward:/index.html";
  }

  @GetMapping("/docs/{filename}")
  @ResponseBody
  public ResponseEntity<String> getDocumentation(@PathVariable String filename) {
    try {
      if (!filename.endsWith(".md") || filename.contains("..") || filename.contains("/")) {
        return ResponseEntity.badRequest().body("Invalid filename");
      }

      java.nio.file.Path path = java.nio.file.Paths.get("/app/docs", filename);
      if (java.nio.file.Files.exists(path)) {
        String content = java.nio.file.Files.readString(path);
        return ResponseEntity.ok()
            .header("Content-Type", "text/markdown; charset=UTF-8")
            .body(content);
      }

      java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("docs/" + filename);
      if (is == null) {
        is = getClass().getClassLoader().getResourceAsStream(filename);
      }
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
      Map<String, Object> result = new java.util.HashMap<>();
      result.put("success", true);
      result.put("transport", transport);

      if ("socket".equals(transport)) {
        Map<String, Object> metrics = tcpClientService.sendViaSocket(host, port, message, delimiter, timeout, ssl, sslVerify, encoding);
        result.putAll(metrics);
      } else if ("nio".equals(transport)) {
        Map<String, Object> metrics = tcpClientService.sendViaNio(host, port, message, delimiter, timeout, ssl, encoding);
        result.putAll(metrics);
      } else if ("netty".equals(transport)) {
        Map<String, Object> metrics = tcpClientService.sendViaNetty(host, port, message, delimiter, timeout, ssl, sslVerify, encoding);
        result.putAll(metrics);
      } else {
        // Mock mode
        long startTime = System.currentTimeMillis();
        io.pockethive.tcpmock.model.ProcessedResponse processedResponse = messageTypeRegistry.processMessage(message);
        long duration = System.currentTimeMillis() - startTime;

        String responseText;
        if (processedResponse.hasFault()) {
          responseText = "FAULT: " + processedResponse.getFault().name();
        } else if (processedResponse.hasProxy()) {
          responseText = "PROXY: " + processedResponse.getProxyTarget();
        } else {
          responseText = processedResponse.getResponse();
        }

        result.put("response", responseText);
        result.put("totalTime", duration);
        result.put("bytesReceived", responseText.getBytes().length);

        TcpRequest testRequest = new TcpRequest(
          "test-" + System.currentTimeMillis(),
          "127.0.0.1:test",
          message,
          Map.of("test", "true", "transport", "mock"),
          "TEST",
          java.time.Instant.now(),
          responseText
        );
        requestStore.addRequest(testRequest);

        if (recordingMode.isRecording()) {
          recordingMode.incrementRecordedCount();
        }
      }

      return ResponseEntity.ok(result);
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

  private Map<String, Object> requestToMap(TcpRequest request) {
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
}
