package io.pockethive.processor.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class NettyTransport implements TcpTransport {
    private static final EventLoopGroup SHARED_GROUP = new NioEventLoopGroup(4);
    private final EventLoopGroup group = SHARED_GROUP;
    
    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();
        
        try {
            CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
            
            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        
                        if (behavior == TcpBehavior.REQUEST_RESPONSE) {
                            String endTag = (String) request.options().getOrDefault("endTag", "</Document>");
                            ByteBuf delimiter = Unpooled.copiedBuffer(endTag.getBytes(StandardCharsets.UTF_8));
                            pipeline.addLast(new DelimiterBasedFrameDecoder(8192, delimiter));
                            pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        }
                        
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new NettyClientHandler(behavior, request.payload(), responseFuture));
                    }
                });
            
            ChannelFuture connectFuture = bootstrap.connect(request.host(), request.port());
            connectFuture.sync();
            
            int timeout = (Integer) request.options().getOrDefault("timeout", 30000);
            byte[] response = responseFuture.get(timeout, TimeUnit.MILLISECONDS);
            
            return new TcpResponse(200, response, System.currentTimeMillis() - start);
            
        } catch (Exception e) {
            throw new TcpException("Netty operation failed", e);
        }
    }
    
    @Override
    public void close() {
        // Don't shutdown shared group - managed by JVM shutdown hook
    }
    
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SHARED_GROUP.shutdownGracefully();
        }));
    }
    
    private static class NettyClientHandler extends SimpleChannelInboundHandler<String> {
        private final TcpBehavior behavior;
        private final byte[] payload;
        private final CompletableFuture<byte[]> responseFuture;
        private final StringBuilder responseBuffer = new StringBuilder();
        
        NettyClientHandler(TcpBehavior behavior, byte[] payload, CompletableFuture<byte[]> responseFuture) {
            this.behavior = behavior;
            this.payload = payload;
            this.responseFuture = responseFuture;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            String message = new String(payload, StandardCharsets.UTF_8);
            ctx.writeAndFlush(message);
            
            if (behavior == TcpBehavior.FIRE_FORGET) {
                responseFuture.complete(new byte[0]);
                ctx.close();
            }
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            if (behavior == TcpBehavior.ECHO) {
                responseFuture.complete(msg.getBytes(StandardCharsets.UTF_8));
                ctx.close();
            } else if (behavior == TcpBehavior.STREAMING) {
                responseBuffer.append(msg);
                // Continue reading until maxBytes or timeout
                if (responseBuffer.length() >= 8192) {
                    responseFuture.complete(responseBuffer.toString().getBytes(StandardCharsets.UTF_8));
                    ctx.close();
                }
            } else {
                responseBuffer.append(msg);
                responseFuture.complete(responseBuffer.toString().getBytes(StandardCharsets.UTF_8));
                ctx.close();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            responseFuture.completeExceptionally(cause);
            ctx.close();
        }
    }
}