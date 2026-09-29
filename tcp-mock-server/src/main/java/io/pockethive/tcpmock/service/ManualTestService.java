package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: execute manual tests and record mock results.
 * Must not: map HTTP errors or duplicate mapping execution.
 * Contract: RESP-TCP-MOCK-WEB-TOOLS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-web-tools.
 */
@Service
public class ManualTestService {
    private final RequestStore requestStore;
    private final MappingExecutor mappingExecutor;
    private final RecordingMode recordingMode;
    private final TcpClientService tcpClientService;

    public ManualTestService(RequestStore requestStore, MappingExecutor mappingExecutor, RecordingMode recordingMode, TcpClientService tcpClientService) {
        this.requestStore = requestStore;
        this.mappingExecutor = mappingExecutor;
        this.recordingMode = recordingMode;
        this.tcpClientService = tcpClientService;
    }

    public Map<String, Object> execute(String message, String transport, String host, Integer port,
                                       String delimiter, Integer timeout, Boolean ssl, Boolean sslVerify,
                                       String encoding) throws Exception {
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
        io.pockethive.tcpmock.model.ProcessedResponse processedResponse = mappingExecutor.processMessage(message);
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

      return result;
    }

}
