package io.pockethive.processor.mip;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Responsibility: own the MIP listener bind and event-loop resource lifetime.
 * Must not: decide session correlation, response layout or business outcomes.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class NettyMipServer implements MipListener {
  private final EventLoopGroup acceptors = new NioEventLoopGroup(1);
  private final EventLoopGroup readers = new NioEventLoopGroup(1);
  private Channel listener;

  public NettyMipServer(MipServerConfig config, MipChannelInitializer pipeline) throws InterruptedException {
    try {
      listener = new ServerBootstrap().group(acceptors, readers).channel(NioServerSocketChannel.class)
          .childOption(ChannelOption.TCP_NODELAY, true)
          .childOption(ChannelOption.SO_KEEPALIVE, true)
          .childHandler(pipeline).bind(config.endpoint().host(), config.endpoint().port()).sync().channel();
    } catch (RuntimeException | InterruptedException failure) {
      close();
      throw failure;
    }
  }

  @Override public void close() {
    if (listener != null) listener.close().syncUninterruptibly();
    acceptors.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
    readers.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
  }
}
