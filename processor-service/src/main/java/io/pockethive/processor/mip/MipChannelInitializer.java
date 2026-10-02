package io.pockethive.processor.mip;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.pockethive.processor.handler.Iso8583WireProfile;

/**
 * Responsibility: compose the explicitly selected length-framed binary MIP pipeline.
 * Must not: autodetect a protocol, parse fields or own session state.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class MipChannelInitializer extends ChannelInitializer<Channel> {
  private final MipSession session;
  private final MipMessageDispatcher dispatcher;
  private final Iso8583WireProfile profile;
  public MipChannelInitializer(MipSession session, MipMessageDispatcher dispatcher,
                               Iso8583WireProfile profile) {
    this.session = session;
    this.dispatcher = dispatcher;
    this.profile = profile;
  }
  @Override protected void initChannel(Channel channel) {
    int prefix = Iso8583WireProfile.LENGTH_PREFIX_BYTES;
    channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(profile.maxPayloadBytes() + prefix, 0, prefix, 0, prefix));
    channel.pipeline().addLast(new MipFrameEncoder(profile));
    channel.pipeline().addLast(new MipInboundHandler(session, dispatcher));
  }
}
