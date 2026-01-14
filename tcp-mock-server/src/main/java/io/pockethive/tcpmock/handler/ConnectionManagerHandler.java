package io.pockethive.tcpmock.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.pockethive.tcpmock.config.TcpMockConfig;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ConnectionManagerHandler extends ChannelInboundHandlerAdapter {
  private static final AtomicInteger activeConnections = new AtomicInteger(0);
  private final int maxConnections;

  public ConnectionManagerHandler(TcpMockConfig config) {
    this.maxConnections = config.getConnection().getMaxConnections();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    if (activeConnections.incrementAndGet() > maxConnections) {
      ctx.close();
      activeConnections.decrementAndGet();
      return;
    }
    super.channelActive(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    activeConnections.decrementAndGet();
    super.channelInactive(ctx);
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent) {
      IdleStateEvent e = (IdleStateEvent) evt;
      if (e.state() == IdleState.READER_IDLE) {
        ctx.close();
      }
    }
    super.userEventTriggered(ctx, evt);
  }

  public static int getActiveConnections() {
    return activeConnections.get();
  }
}
