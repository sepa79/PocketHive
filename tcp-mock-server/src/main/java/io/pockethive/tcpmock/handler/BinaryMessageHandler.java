package io.pockethive.tcpmock.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.service.ValidationService;
import io.pockethive.tcpmock.model.ProcessedResponse;
import io.pockethive.tcpmock.util.TcpMetrics;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class BinaryMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final MessageTypeRegistry messageTypeRegistry;
    private final ValidationService validationService;
    private final TcpMetrics metrics;
    private final FaultInjectionHandler faultHandler;
    private final TcpProxyHandler proxyHandler;

    public BinaryMessageHandler(MessageTypeRegistry messageTypeRegistry,
                                ValidationService validationService,
                                TcpMetrics metrics,
                                FaultInjectionHandler faultHandler,
                                TcpProxyHandler proxyHandler) {
        this.messageTypeRegistry = messageTypeRegistry;
        this.validationService = validationService;
        this.metrics = metrics;
        this.faultHandler = faultHandler;
        this.proxyHandler = proxyHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        metrics.incrementTotal();
        
        // Convert binary to hex string for pattern matching
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        String hexMessage = HexFormat.of().formatHex(data);
        
        // Validate
        if (!validationService.isValid(hexMessage)) {
            metrics.incrementInvalid();
            ctx.writeAndFlush(Unpooled.wrappedBuffer("INVALID".getBytes(StandardCharsets.UTF_8)));
            return;
        }
        
        // Process through mapping registry
        ProcessedResponse response = messageTypeRegistry.processMessage(hexMessage);
        
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
            return;
        }
        
        // Handle proxy
        if (response.hasProxy()) {
            proxyHandler.proxyRequest(ctx, hexMessage, response.getProxyTarget());
            return;
        }
        
        // Convert hex response back to binary
        byte[] responseBytes = HexFormat.of().parseHex(response.getResponse());
        ByteBuf responseBuf = Unpooled.wrappedBuffer(responseBytes);
        
        // Add delimiter if specified
        if (!response.getDelimiter().isEmpty()) {
            ByteBuf delimiterBuf = Unpooled.wrappedBuffer(response.getDelimiter().getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(responseBuf, delimiterBuf));
        } else {
            ctx.writeAndFlush(responseBuf);
        }
    }
}
