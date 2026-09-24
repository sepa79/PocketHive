package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.controller.WebController;
import io.pockethive.tcpmock.util.AdvancedRequestMatcher;
import io.pockethive.tcpmock.util.PatternCache;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualTestServiceTest {
    private final RequestStore requests = new RequestStore();
    private final RecordingMode recording = new RecordingMode();
    private final MappingExecutor executor = new MappingExecutor(TestMappingCatalogues.fresh(), new PatternCache(),
        new AdvancedRequestMatcher(), null, new EnhancedTemplateEngine(), new RequestVerificationService());

    @Test
    void manualMockRecordsRenderedResponseWithoutTextUnmatchedJournal() throws Exception {
        recording.startRecording();
        var service = new ManualTestService(requests, executor, recording, null);
        var result = service.execute("hello", "mock", "localhost", 8080, "\n", 5000, false, false, "utf-8");
        assertEquals("OK", result.get("response"));
        assertEquals(2, result.get("bytesReceived"));
        assertEquals(true, result.get("success"));
        assertEquals("TEST", requests.getAllRequests().getFirst().getBehavior());
        assertEquals("hello", requests.getAllRequests().getFirst().getMessage());
        assertEquals(1, recording.getRecordedCount());
        assertTrue(requests.getUnmatchedRequests().isEmpty());
    }

    @Test
    void socketErrorRemainsHttpFailureAndDoesNotRecordAMockRequest() {
        var client = new TcpClientService() {
            @Override public Map<String, Object> sendViaSocket(String host, int port, String message, String delimiter,
                    int timeout, boolean ssl, boolean verify, String encoding) throws Exception {
                throw new ConnectException("test refusal");
            }
        };
        var controller = controller(client);
        var response = controller.sendTestMessage(Map.of("message", "hello", "transport", "socket"));
        assertEquals(500, response.getStatusCode().value());
        assertEquals("CONNECTION_REFUSED", response.getBody().get("errorType"));
        assertEquals("Connection refused: test refusal", response.getBody().get("error"));
        assertTrue(requests.getAllRequests().isEmpty());
    }

    @Test
    void timeoutPreservesConfiguredDurationAndMissingMessageIsRejectedBeforeExecution() {
        var client = new TcpClientService() {
            @Override public Map<String, Object> sendViaNio(String host, int port, String message, String delimiter,
                    int timeout, boolean ssl, String encoding) throws Exception {
                throw new SocketTimeoutException();
            }
        };
        var controller = controller(client);
        assertEquals(400, controller.sendTestMessage(Map.of()).getStatusCode().value());
        var response = controller.sendTestMessage(Map.of("message", "hello", "transport", "nio", "timeout", 321));
        assertEquals(500, response.getStatusCode().value());
        assertEquals("TIMEOUT", response.getBody().get("errorType"));
        assertEquals("Connection timeout after 321ms", response.getBody().get("error"));
    }

    private WebController controller(TcpClientService client) {
        return new WebController(requests, new ManualTestService(requests, executor, recording, client), recording,
            new RequestLogProjection(), new DocumentationReader());
    }
}
