package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.config.TcpMockConfig;
import io.pockethive.tcpmock.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextRequestProcessorTest {
    private final RequestStore requests = new RequestStore();
    private final RecordingMode recording = new RecordingMode();
    private final TcpMetrics metrics = new TcpMetrics(new SimpleMeterRegistry());
    private final MappingExecutor executor = new MappingExecutor(TestMappingCatalogues.fresh(), new PatternCache(),
        new AdvancedRequestMatcher(), null, new EnhancedTemplateEngine(), new RequestVerificationService());
    private final TextRequestProcessor processor = new TextRequestProcessor(executor,
        new ValidationService(new TcpMockConfig()), metrics, requests, new LatencySimulator(), recording);

    @Test
    void invalidInputNeverEntersRecordingOrMappingExecution() {
        recording.startRecording();
        assertEquals("INVALID_MESSAGE", processor.processMessage(" ", "invalid").getResponse());
        assertEquals(1, metrics.getInvalidRequests());
        assertTrue(requests.getAllRequests().isEmpty());
        assertEquals(0, recording.getRecordedCount());
    }

    @Test
    void recordsAcceptedRequestsAndPreservesUnmatchedClassification() {
        recording.startRecording();
        assertEquals("OK", processor.processMessage("hello", "one").getResponse());
        assertEquals("ECHO hello", processor.processMessage("ECHO hello", "two").getResponse());
        assertEquals(2, requests.getAllRequests().size());
        assertEquals("one", requests.getUnmatchedRequests().getFirst().getId());
        assertEquals(1, requests.getUnmatchedRequests().size());
        assertEquals(2, recording.getRecordedCount());
        assertEquals(1, metrics.getEchoRequests());
    }
}
