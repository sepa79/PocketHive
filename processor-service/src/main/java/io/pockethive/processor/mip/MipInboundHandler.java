package io.pockethive.processor.mip;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.IOException;

/**
 * Responsibility: establish accepted-channel identity and dispatch framed payload bytes.
 * Must not: own session transitions, construct 0810 or block the channel event loop.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class MipInboundHandler extends SimpleChannelInboundHandler<ByteBuf> {
  private final MipSession session;
  private final MipMessageDispatcher dispatcher;
  public MipInboundHandler(MipSession session, MipMessageDispatcher dispatcher) {
    this.session = session;
    this.dispatcher = dispatcher;
  }
  @Override public void channelActive(ChannelHandlerContext context) {
    if (session.attach(new NettyMipPeer(context.channel()))) context.fireChannelActive();
  }
  @Override protected void channelRead0(ChannelHandlerContext context, ByteBuf frame) {
    byte[] payload = new byte[frame.readableBytes()];
    frame.readBytes(payload);
    dispatcher.receive(context.channel().id().asLongText(), payload);
  }
  @Override public void channelInactive(ChannelHandlerContext context) {
    session.detach(context.channel().id().asLongText(), new IOException("MIP peer disconnected"));
    context.fireChannelInactive();
  }
  @Override public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
    session.detach(context.channel().id().asLongText(), new IOException("Invalid MIP peer exchange"));
    context.close();
  }
}
