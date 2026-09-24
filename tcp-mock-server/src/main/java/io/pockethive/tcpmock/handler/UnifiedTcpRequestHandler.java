package io.pockethive.tcpmock.handler;

import io.pockethive.tcpmock.service.*;
import io.pockethive.tcpmock.util.TcpMetrics;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Responsibility: schedule text requests and apply channel responses.
 * Must not: execute mappings or record application requests.
 * Contract: RESP-TCP-MOCK-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-execution.
 */
@Component
@io.netty.channel.ChannelHandler.Sharable
public class UnifiedTcpRequestHandler extends SimpleChannelInboundHandler<String> {
    private static final ForkJoinPool ASYNC_POOL = ForkJoinPool.commonPool();
    private final TextRequestProcessor processor;
    private final TcpMetrics metrics;
    private final FaultInjectionHandler faultHandler;
    private final TcpProxyHandler proxyHandler;

    public UnifiedTcpRequestHandler(TextRequestProcessor processor, TcpMetrics metrics,
                                   FaultInjectionHandler faultHandler, TcpProxyHandler proxyHandler) {
        this.processor = processor;
        this.metrics = metrics;
        this.faultHandler = faultHandler;
        this.proxyHandler = proxyHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String message) {
        Timer.Sample sample = metrics.startTimer();
        String requestId = UUID.randomUUID().toString();

        CompletableFuture.supplyAsync(() -> processor.processMessage(message, requestId), ASYNC_POOL)
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

}
