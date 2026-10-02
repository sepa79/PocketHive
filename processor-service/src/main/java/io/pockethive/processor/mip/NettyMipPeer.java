package io.pockethive.processor.mip;

import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;

/**
 * Responsibility: adapt one accepted Netty channel to the asynchronous MIP peer port.
 * Must not: own request reservations, correlation or network-response fields.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class NettyMipPeer implements MipPeer {
  private final Channel channel;
  public NettyMipPeer(Channel channel) { this.channel = channel; }
  @Override public String id() { return channel.id().asLongText(); }
  @Override public CompletableFuture<Void> send(byte[] payload) {
    var completion = new CompletableFuture<Void>();
    channel.writeAndFlush(payload.clone()).addListener(write -> {
      if (write.isSuccess()) completion.complete(null);
      else completion.completeExceptionally(write.cause());
    });
    return completion;
  }
  @Override public void close() { channel.close(); }
}
