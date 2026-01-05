package io.pockethive.tcpmock.core;

import io.pockethive.tcpmock.handler.ProtocolDetectionHandler;
import io.pockethive.tcpmock.handler.UnifiedTcpRequestHandler;
import io.pockethive.tcpmock.handler.BinaryMessageHandler;
import io.pockethive.tcpmock.handler.FaultInjectionHandler;
import io.pockethive.tcpmock.handler.TcpProxyHandler;
import io.pockethive.tcpmock.config.TcpMockConfig;
import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.service.ValidationService;
import io.pockethive.tcpmock.util.TcpMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

@Component
public class TcpMockServer implements CommandLineRunner {
    private final TcpMockConfig config;
    private final UnifiedTcpRequestHandler requestHandler;
    private final MessageTypeRegistry messageTypeRegistry;
    private final ValidationService validationService;
    private final TcpMetrics tcpMetrics;
    private final FaultInjectionHandler faultInjectionHandler;
    private final TcpProxyHandler tcpProxyHandler;
    private SslContext sslContext;

    public TcpMockServer(TcpMockConfig config, 
                        UnifiedTcpRequestHandler requestHandler,
                        MessageTypeRegistry messageTypeRegistry,
                        ValidationService validationService,
                        TcpMetrics tcpMetrics,
                        FaultInjectionHandler faultInjectionHandler,
                        TcpProxyHandler tcpProxyHandler) {
        this.config = config;
        this.requestHandler = requestHandler;
        this.messageTypeRegistry = messageTypeRegistry;
        this.validationService = validationService;
        this.tcpMetrics = tcpMetrics;
        this.faultInjectionHandler = faultInjectionHandler;
        this.tcpProxyHandler = tcpProxyHandler;
        initializeSsl();
    }

    private void initializeSsl() {
        if (config.getSsl().isEnabled()) {
            try {
                SelfSignedCertificate cert = new SelfSignedCertificate();
                sslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();
            } catch (CertificateException | SSLException e) {
                throw new RuntimeException("Failed to initialize SSL", e);
            }
        }
    }

    @Override
    public void run(String... args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // SSL/TLS support
                        if (sslContext != null) {
                            pipeline.addLast(sslContext.newHandler(ch.alloc()));
                        }

                        // Connection management
                        pipeline.addLast(new IdleStateHandler(config.getConnection().getIdleTimeout(), 0, 0));

                        // Protocol detection and framing
                        pipeline.addLast(new ProtocolDetectionHandler(config));

                        // Dual handler: String for text, ByteBuf for binary
                        pipeline.addLast("textHandler", requestHandler);
                        pipeline.addLast("binaryHandler", new BinaryMessageHandler(
                            messageTypeRegistry, validationService, tcpMetrics, 
                            faultInjectionHandler, tcpProxyHandler));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, config.getConnection().getBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_RCVBUF, 32 * 1024)
                .childOption(ChannelOption.SO_SNDBUF, 32 * 1024);

            ChannelFuture future = bootstrap.bind(config.getPort()).sync();
            System.out.println("TCP Mock Server started on port " + config.getPort());

            future.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
