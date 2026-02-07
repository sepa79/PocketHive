package io.pockethive.tcpmock.handler;

import io.pockethive.tcpmock.service.*;
import io.pockethive.tcpmock.util.TcpMetrics;
import io.pockethive.tcpmock.model.TcpRequest;
import io.pockethive.tcpmock.model.ProcessedResponse;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

@Component
@io.netty.channel.ChannelHandler.Sharable
public class UnifiedTcpRequestHandler extends SimpleChannelInboundHandler<String> {
    private static final ForkJoinPool ASYNC_POOL = ForkJoinPool.commonPool();
    private final MessageTypeRegistry messageTypeRegistry;
    private final ValidationService validationService;
    private final TcpMetrics metrics;
    private final RequestStore requestStore;
    private final LatencySimulator latencySimulator;
    private final RecordingMode recordingMode;
    private final FaultInjectionHandler faultHandler;
    private final TcpProxyHandler proxyHandler;

    public UnifiedTcpRequestHandler(MessageTypeRegistry messageTypeRegistry,
                                  ValidationService validationService,
                                  TcpMetrics metrics,
                                  RequestStore requestStore,
                                  LatencySimulator latencySimulator,
                                  RecordingMode recordingMode,
                                  FaultInjectionHandler faultHandler,
                                  TcpProxyHandler proxyHandler) {
        this.messageTypeRegistry = messageTypeRegistry;
        this.validationService = validationService;
        this.metrics = metrics;
        this.requestStore = requestStore;
        this.latencySimulator = latencySimulator;
        this.recordingMode = recordingMode;
        this.faultHandler = faultHandler;
        this.proxyHandler = proxyHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String message) {
        Timer.Sample sample = metrics.startTimer();
        String requestId = UUID.randomUUID().toString();

        CompletableFuture.supplyAsync(() -> processMessage(message, requestId), ASYNC_POOL)
            .thenAccept(response -> {
                if (response != null) {
                    // Handle delay
                    if (response.hasDelay()) {
                        try {
                            Thread.sleep(response.getDelayMs());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    // Handle fault injection
                    if (response.hasFault()) {
                        faultHandler.handleFault(ctx, response.getFault());
                        metrics.recordDuration(sample);
                        return;
                    }

                    // Handle proxy
                    if (response.hasProxy()) {
                        proxyHandler.proxyRequest(ctx, message, response.getProxyTarget());
                        metrics.recordDuration(sample);
                        return;
                    }

                    // Normal response
                    ctx.writeAndFlush(response.getResponse() + response.getDelimiter());
                }
                metrics.recordDuration(sample);
            })
            .exceptionally(throwable -> {
                metrics.incrementInvalid();
                ctx.writeAndFlush("ERROR: " + throwable.getMessage() + "\n");
                metrics.recordDuration(sample);
                return null;
            });
    }

    private ProcessedResponse processMessage(String message, String requestId) {
        metrics.incrementTotal();

        // Validate message
        if (!validationService.isValid(message)) {
            metrics.incrementInvalid();
            return new ProcessedResponse("INVALID_MESSAGE", "\n");
        }

        // Simulate latency
        latencySimulator.simulateLatency();

        // Process through mapping registry
        ProcessedResponse response = messageTypeRegistry.processMessage(message);

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
