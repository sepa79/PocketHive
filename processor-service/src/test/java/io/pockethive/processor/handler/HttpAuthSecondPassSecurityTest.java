package io.pockethive.processor.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.work.api.HttpRequestEnvelope;
import io.pockethive.work.api.HttpRequest;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the production processor logging boundary, not OAuth acquisition or deployed logs. */
class HttpAuthSecondPassSecurityTest {
    private static final String SYNTHETIC_TOKEN = "second-pass-disposable-log-token-A9_+/.=";
    private static final String AUTHORIZATION_VALUE = "Bearer " + SYNTHETIC_TOKEN;

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "aUtHoRiZaTiOn"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void debugLogsNeverExposeDownstreamBearerToken(String authorizationName) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HttpClient transport = mock(HttpClient.class);
        AtomicReference<ClassicHttpRequest> transmitted = new AtomicReference<>();
        when(transport.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                transmitted.set(invocation.getArgument(0, ClassicHttpRequest.class));
                HttpClientResponseHandler<?> responseHandler = invocation.getArgument(1, HttpClientResponseHandler.class);
                try (BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK")) {
                    response.setEntity(new StringEntity("{\"accepted\":true}", StandardCharsets.UTF_8));
                    return responseHandler.handleResponse(response);
                }
            });

        // A private logger context captures enabled DEBUG output without changing application logging.
        LoggerContext logContext = new LoggerContext();
        Logger logger = logContext.getLogger(HttpAuthSecondPassSecurityTest.class.getName());
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.setContext(logContext);
        captured.start();
        logger.addAppender(captured);
        try {
            WorkerInfo worker = new WorkerInfo("processor", "second-pass-log-swarm", "log-audit-worker", null, null);
            WorkerContext context = mock(WorkerContext.class);
            when(context.info()).thenReturn(worker);
            when(context.logger()).thenReturn(logger);

            CallMetricsRecorder metrics = new CallMetricsRecorder();
            HttpProtocolHandler handler = new HttpProtocolHandler(mapper,
                Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC), metrics,
                transport, transport, ThreadLocal.withInitial(() -> transport),
                transport, transport, ThreadLocal.withInitial(() -> transport), new AtomicLong());
            ProcessorWorkerConfig config = new ProcessorWorkerConfig("https://audit-resource.invalid",
                ProcessorWorkerConfig.Mode.THREAD_COUNT, 1, null,
                ProcessorWorkerConfig.ConnectionReuse.GLOBAL, true, 5000, true, null);
            HttpRequestEnvelope envelope = HttpRequestEnvelope.of(new HttpRequest(
                "GET", "/protected", Map.of(authorizationName, AUTHORIZATION_VALUE, "X-Audit-Probe", "safe-probe"), ""));
            WorkItem inbound = WorkItem.json(worker, envelope).build();

            WorkItem result = handler.invoke(inbound, mapper.valueToTree(envelope), config, context);

            assertThat(result).isNotNull();
            assertThat(metrics.totalCalls()).isEqualTo(1);
            assertThat(transmitted.get()).as("production handler executed the outbound HTTP-client path").isNotNull();
            assertThat(transmitted.get().getFirstHeader("Authorization").getValue()).isEqualTo(AUTHORIZATION_VALUE);
            List<String> debugMessages = captured.list.stream()
                .filter(event -> event.getLevel() == Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(debugMessages).as("DEBUG capture is active for harmless headers")
                .anyMatch(message -> message.contains("X-Audit-Probe") && message.contains("safe-probe"));

            // Keep this expectation failing until credentials are redacted at the production log boundary.
            assertThat(debugMessages).as("downstream Bearer credentials must be absent from every DEBUG message")
                .noneMatch(message -> message.contains(SYNTHETIC_TOKEN));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
            logContext.stop();
        }
    }
}
