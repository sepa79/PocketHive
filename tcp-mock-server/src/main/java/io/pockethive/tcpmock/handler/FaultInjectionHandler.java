package io.pockethive.tcpmock.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.pockethive.tcpmock.model.ProcessedResponse;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Random;

@Component
public class FaultInjectionHandler {
    private final Random random = new Random();
    
    public void handleFault(ChannelHandlerContext ctx, ProcessedResponse.FaultType fault) {
        switch (fault) {
            case CONNECTION_RESET:
                ctx.close();
                break;
                
            case EMPTY_RESPONSE:
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
                break;
                
            case MALFORMED_RESPONSE:
                byte[] malformed = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
                ctx.writeAndFlush(Unpooled.wrappedBuffer(malformed));
                break;
                
            case RANDOM_DATA:
                byte[] randomData = new byte[64];
                random.nextBytes(randomData);
                ctx.writeAndFlush(Unpooled.wrappedBuffer(randomData));
                break;
        }
    }
}
