package io.pockethive.tcpmock.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public class TcpProxyHandler {
    
    public void proxyRequest(ChannelHandlerContext clientCtx, String message, String proxyTarget) {
        String[] parts = proxyTarget.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientCtx.channel().eventLoop())
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                            // Forward response back to client
                            clientCtx.writeAndFlush(msg.retain());
                        }
                        
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            clientCtx.writeAndFlush(Unpooled.wrappedBuffer(
                                ("PROXY_ERROR: " + cause.getMessage()).getBytes(StandardCharsets.UTF_8)
                            ));
                            ctx.close();
                        }
                    });
                }
            });
        
        ChannelFuture connectFuture = bootstrap.connect(host, port);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel proxyChannel = future.channel();
                proxyChannel.writeAndFlush(Unpooled.wrappedBuffer(message.getBytes(StandardCharsets.UTF_8)));
            } else {
                clientCtx.writeAndFlush(Unpooled.wrappedBuffer(
                    ("PROXY_CONNECT_FAILED: " + future.cause().getMessage()).getBytes(StandardCharsets.UTF_8)
                ));
            }
        });
    }
}
