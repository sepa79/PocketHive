package io.pockethive.processor.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import java.io.ByteArrayOutputStream;
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
            int timeout = (Integer) request.options().getOrDefault("timeout", 30000);
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            String endTag = (String) request.options().getOrDefault("endTag", "</Document>");

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        if (behavior == TcpBehavior.REQUEST_RESPONSE) {
                            ByteBuf delimiter = Unpooled.copiedBuffer(endTag.getBytes(StandardCharsets.UTF_8));
                            pipeline.addLast(new DelimiterBasedFrameDecoder(maxBytes, delimiter));
                        }

                        pipeline.addLast(new NettyClientHandler(behavior, request.payload(), endTag, maxBytes, responseFuture));
                    }
                });

            ChannelFuture connectFuture = bootstrap.connect(request.host(), request.port());
            connectFuture.sync();

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

    private static class NettyClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final TcpBehavior behavior;
        private final byte[] payload;
        private final byte[] endTagBytes;
        private final int maxBytes;
        private final CompletableFuture<byte[]> responseFuture;
        private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();

        NettyClientHandler(TcpBehavior behavior,
                           byte[] payload,
                           String endTag,
                           int maxBytes,
                           CompletableFuture<byte[]> responseFuture) {
            this.behavior = behavior;
            this.payload = payload;
            this.endTagBytes = endTag.getBytes(StandardCharsets.UTF_8);
            this.maxBytes = maxBytes;
            this.responseFuture = responseFuture;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(payload));

            if (behavior == TcpBehavior.FIRE_FORGET) {
                responseFuture.complete(new byte[0]);
                ctx.close();
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            if (responseFuture.isDone()) {
                return;
            }

            if (behavior == TcpBehavior.ECHO) {
                appendToBuffer(msg);
                if (responseBuffer.size() >= payload.length) {
                    responseFuture.complete(responseBuffer.toByteArray());
                    ctx.close();
                }
                return;
            }

            if (behavior == TcpBehavior.STREAMING) {
                appendToBuffer(msg);
                if (responseBuffer.size() >= maxBytes) {
                    byte[] bytes = responseBuffer.toByteArray();
                    if (bytes.length > maxBytes) {
                        byte[] trimmed = new byte[maxBytes];
                        System.arraycopy(bytes, 0, trimmed, 0, maxBytes);
                        bytes = trimmed;
                    }
                    responseFuture.complete(bytes);
                    ctx.close();
                }
                return;
            }

            // REQUEST_RESPONSE behavior
            appendToBuffer(msg);
            byte[] bytes = responseBuffer.toByteArray();
            // DelimiterBasedFrameDecoder typically strips the delimiter; ensure parity with Socket/NIO transports.
            if (!endsWith(bytes, endTagBytes)) {
                byte[] withDelimiter = new byte[bytes.length + endTagBytes.length];
                System.arraycopy(bytes, 0, withDelimiter, 0, bytes.length);
                System.arraycopy(endTagBytes, 0, withDelimiter, bytes.length, endTagBytes.length);
                bytes = withDelimiter;
            }
            responseFuture.complete(bytes);
            ctx.close();
        }

        private void appendToBuffer(ByteBuf msg) throws Exception {
            int readable = msg.readableBytes();
            if (readable <= 0) {
                return;
            }
            byte[] chunk = new byte[readable];
            msg.getBytes(msg.readerIndex(), chunk);
            responseBuffer.write(chunk);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!responseFuture.isDone()) {
                responseFuture.complete(responseBuffer.toByteArray());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            responseFuture.completeExceptionally(cause);
            ctx.close();
        }

        private static boolean endsWith(byte[] bytes, byte[] suffix) {
            if (bytes == null || suffix == null) {
                return false;
            }
            if (bytes.length < suffix.length) {
                return false;
            }
            int offset = bytes.length - suffix.length;
            for (int i = 0; i < suffix.length; i++) {
                if (bytes[offset + i] != suffix[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
