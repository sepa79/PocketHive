package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.ProcessedResponse;
import io.pockethive.tcpmock.model.TcpRequest;
import io.pockethive.tcpmock.util.TcpMetrics;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: validate, execute and record text mock requests.
 * Must not: own channels or duplicate mapping selection.
 * Contract: RESP-TCP-MOCK-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-execution.
 */
@Service
public class TextRequestProcessor {
    private final MappingExecutor mappingExecutor;
    private final ValidationService validationService;
    private final TcpMetrics metrics;
    private final RequestStore requestStore;
    private final LatencySimulator latencySimulator;
    private final RecordingMode recordingMode;

    public TextRequestProcessor(MappingExecutor mappingExecutor, ValidationService validationService, TcpMetrics metrics, RequestStore requestStore, LatencySimulator latencySimulator, RecordingMode recordingMode) {
        this.mappingExecutor = mappingExecutor;
        this.validationService = validationService;
        this.metrics = metrics;
        this.requestStore = requestStore;
        this.latencySimulator = latencySimulator;
        this.recordingMode = recordingMode;
    }

    public ProcessedResponse processMessage(String message, String requestId) {
        metrics.incrementTotal();

        // Validate message
        if (!validationService.isValid(message)) {
            metrics.incrementInvalid();
            return new ProcessedResponse("INVALID_MESSAGE", "\n");
        }

        // Simulate latency
        latencySimulator.simulateLatency();

        // Process through mapping executor
        ProcessedResponse response = mappingExecutor.processMessage(message);

        // Update metrics based on response
        updateMetrics(response.getResponse());

        // Store request for UI
        storeRequest(requestId, message, response.getResponse(), "MAPPING");

        return response;
    }

    private void updateMetrics(String response) {
        // Classify by response pattern
        if (response != null && response.contains("ECHO")) {
            metrics.incrementEcho();
        } else if (response != null && response.contains("{")) {
            metrics.incrementJson();
        } else {
            metrics.incrementRequestResponse();
        }
    }

    private void storeRequest(String requestId, String message, String response, String behavior) {
        TcpRequest request = new TcpRequest(
            requestId,
            "unknown",
            message,
            Map.of("behavior", behavior, "recorded", String.valueOf(recordingMode.isRecording())),
            behavior,
            Instant.now(),
            response
        );
        requestStore.addRequest(request);

        // Mark as unmatched if it's the default catch-all response or unknown type
        if ("UNKNOWN_MESSAGE_TYPE".equals(response) || "OK".equals(response)) {
            requestStore.addUnmatchedRequest(request);
        }

        if (recordingMode.isRecording()) {
            recordingMode.incrementRecordedCount();
        }
    }

}
