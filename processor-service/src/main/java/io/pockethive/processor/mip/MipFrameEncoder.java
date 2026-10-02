package io.pockethive.processor.mip;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.pockethive.processor.handler.Iso8583WireProfile;

/**
 * Responsibility: apply the canonical explicit ISO wire profile to outgoing payload bytes.
 * Must not: parse ISO fields, correlate requests or decide peer readiness.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class MipFrameEncoder extends MessageToByteEncoder<byte[]> {
  private final Iso8583WireProfile profile;
  public MipFrameEncoder(Iso8583WireProfile profile) { this.profile = profile; }
  @Override protected void encode(ChannelHandlerContext context, byte[] payload, ByteBuf output) {
    output.writeBytes(profile.frame(payload));
  }
}
