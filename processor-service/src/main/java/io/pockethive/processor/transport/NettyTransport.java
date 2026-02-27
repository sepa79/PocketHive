package io.pockethive.processor.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.pockethive.processor.TcpTransportConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyTransport implements TcpTransport {
    private static final Logger logger = LoggerFactory.getLogger(NettyTransport.class);
    private static final ConcurrentHashMap<Integer, SharedGroup> SHARED_GROUPS = new ConcurrentHashMap<>();
    private final int workerThreads;
    private final SharedGroup sharedGroup;
    private final EventLoopGroup group;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public NettyTransport() {
        this(TcpTransportConfig.defaults());
    }

    public NettyTransport(TcpTransportConfig config) {
        int workerThreads = config == null ? TcpTransportConfig.defaults().workerThreads() : config.workerThreads();
        this.workerThreads = workerThreads;
        this.sharedGroup = SHARED_GROUPS.compute(workerThreads, (key, existing) -> {
            if (existing == null) {
                existing = new SharedGroup(new NioEventLoopGroup(workerThreads));
            }
            existing.retain();
            return existing;
        });
        this.group = sharedGroup.group();
    }

    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();

        try {
            if (logger.isDebugEnabled()) {
                logger.debug("TCP_SEND host={} port={} bytes={} payload={}",
                    request.host(), request.port(), request.payload().length,
                    new String(request.payload(), StandardCharsets.UTF_8));
            }

            CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
            int connectTimeout = (Integer) request.options().getOrDefault("connectTimeoutMs", 5000);
            int readTimeout = (Integer) request.options().getOrDefault("readTimeoutMs", 30000);
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            String endTag = (String) request.options().getOrDefault("endTag", "</Document>");
            boolean useSsl = Boolean.TRUE.equals(request.options().get("ssl"));
            boolean sslVerify = Boolean.TRUE.equals(request.options().getOrDefault("sslVerify", false));
            SslContext sslContext = useSsl ? buildClientSslContext(sslVerify) : null;

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        if (sslContext != null) {
                            pipeline.addLast(sslContext.newHandler(ch.alloc(), request.host(), request.port()));
                        }
                        pipeline.addLast(new NettyClientHandler(behavior, request.payload(), endTag, maxBytes, responseFuture, useSsl));
                    }
                });

            ChannelFuture connectFuture = bootstrap.connect(request.host(), request.port());
            connectFuture.sync();

            byte[] response = responseFuture.get(readTimeout, TimeUnit.MILLISECONDS);
            long latency = System.currentTimeMillis() - start;

            if (logger.isDebugEnabled()) {
                logger.debug("TCP_RECV host={} port={} bytes={} latency={}ms payload={}",
                    request.host(), request.port(), response.length, latency,
                    new String(response, StandardCharsets.UTF_8));
            }

            return new TcpResponse(200, response, latency);

        } catch (Exception e) {
            throw new TcpException("Netty operation failed", e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (sharedGroup == null) {
            return;
        }
        SHARED_GROUPS.computeIfPresent(workerThreads, (key, existing) -> {
            if (existing != sharedGroup) {
                return existing;
            }
            int remaining = existing.release();
            if (remaining <= 0) {
                existing.group().shutdownGracefully();
                return null;
            }
            return existing;
        });
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SHARED_GROUPS.values().forEach(group -> group.group().shutdownGracefully());
        }));
    }

    private static SslContext buildClientSslContext(boolean sslVerify) throws Exception {
        SslContextBuilder builder = SslContextBuilder.forClient();
        if (!sslVerify) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }
        return builder.build();
    }

    private static class NettyClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final TcpBehavior behavior;
        private final byte[] payload;
        private final byte[] endTagBytes;
        private final int maxBytes;
        private final CompletableFuture<byte[]> responseFuture;
        private final boolean useSsl;
        private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();

        NettyClientHandler(TcpBehavior behavior,
                           byte[] payload,
                           String endTag,
                           int maxBytes,
                           CompletableFuture<byte[]> responseFuture,
                           boolean useSsl) {
            this.behavior = behavior;
            this.payload = payload;
            this.endTagBytes = endTag.getBytes(StandardCharsets.UTF_8);
            this.maxBytes = maxBytes;
            this.responseFuture = responseFuture;
            this.useSsl = useSsl;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (useSsl) {
                SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
                if (sslHandler == null) {
                    fail(ctx, new IllegalStateException("SSL requested but SslHandler missing"));
                    return;
                }
                sslHandler.handshakeFuture().addListener(future -> {
                    if (!future.isSuccess()) {
                        fail(ctx, future.cause());
                        return;
                    }
                    sendPayload(ctx);
                });
                return;
            }
            sendPayload(ctx);
        }

        private void sendPayload(ChannelHandlerContext ctx) {
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

            appendToBuffer(msg);
            byte[] currentData = responseBuffer.toByteArray();

            // Check completion conditions based on behavior
            boolean complete = false;
            if (behavior == TcpBehavior.ECHO && currentData.length >= payload.length) {
                complete = true;
            } else if (behavior == TcpBehavior.STREAMING && currentData.length >= maxBytes) {
                complete = true;
            } else if (behavior == TcpBehavior.LENGTH_PREFIX_2B && isLengthPrefixedFrameComplete(currentData)) {
                complete = true;
            } else if (behavior == TcpBehavior.REQUEST_RESPONSE && endsWithTag(currentData)) {
                complete = true;
            }

            if (complete) {
                completeResponse(ctx, currentData);
            }
        }

        private boolean endsWithTag(byte[] data) {
            if (data.length < endTagBytes.length) {
                return false;
            }
            for (int i = 0; i < endTagBytes.length; i++) {
                if (data[data.length - endTagBytes.length + i] != endTagBytes[i]) {
                    return false;
                }
            }
            return true;
        }

        private void completeResponse(ChannelHandlerContext ctx, byte[] data) {
            try {
                // Truncate to maxBytes for STREAMING
                if (behavior == TcpBehavior.STREAMING && data.length > maxBytes) {
                    byte[] truncated = new byte[maxBytes];
                    System.arraycopy(data, 0, truncated, 0, maxBytes);
                    data = truncated;
                }
                // Truncate to payload length for ECHO
                else if (behavior == TcpBehavior.ECHO && data.length > payload.length) {
                    byte[] truncated = new byte[payload.length];
                    System.arraycopy(data, 0, truncated, 0, payload.length);
                    data = truncated;
                } else if (behavior == TcpBehavior.LENGTH_PREFIX_2B) {
                    if (data.length < 2) {
                        throw new IllegalStateException("Length-prefixed frame missing 2-byte length header");
                    }
                    int frameLength = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                    if (frameLength > maxBytes) {
                        throw new IllegalStateException("Length-prefixed response exceeds maxBytes: " + frameLength);
                    }
                    if (data.length < 2 + frameLength) {
                        throw new IllegalStateException("Incomplete length-prefixed frame");
                    }
                    byte[] framed = new byte[frameLength];
                    System.arraycopy(data, 2, framed, 0, frameLength);
                    data = framed;
                }
                responseFuture.complete(data);
                ctx.close();
            } catch (Exception e) {
                fail(ctx, e);
            }
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
                if (behavior == TcpBehavior.LENGTH_PREFIX_2B) {
                    responseFuture.completeExceptionally(new IllegalStateException("Connection closed before full length-prefixed response"));
                } else {
                    responseFuture.complete(responseBuffer.toByteArray());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(ctx, cause);
        }

        private void fail(ChannelHandlerContext ctx, Throwable cause) {
            responseFuture.completeExceptionally(cause);
            ctx.close();
        }

        private boolean isLengthPrefixedFrameComplete(byte[] data) {
            if (data.length < 2) {
                return false;
            }
            int frameLength = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            if (frameLength > maxBytes) {
                throw new IllegalStateException("Length-prefixed response exceeds maxBytes: " + frameLength);
            }
            return data.length >= 2 + frameLength;
        }
    }

    private static final class SharedGroup {
        private final EventLoopGroup group;
        private final AtomicInteger refs = new AtomicInteger(0);

        private SharedGroup(EventLoopGroup group) {
            this.group = group;
        }

        private void retain() {
            refs.incrementAndGet();
        }

        private int release() {
            return refs.decrementAndGet();
        }

        private EventLoopGroup group() {
            return group;
        }
    }
}
