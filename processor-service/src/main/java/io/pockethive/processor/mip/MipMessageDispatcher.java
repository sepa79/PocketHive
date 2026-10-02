package io.pockethive.processor.mip;

import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.work.api.IsoSchemaRef;

/**
 * Responsibility: dispatch decoded MIP message families to their existing owners.
 * Must not: construct business fields, mutate pending calls or block on Work execution.
 * Contract: RESP-PROCESSOR-MIP-NETWORK — docs/architecture/runtime-responsibilities.md#resp-processor-mip-network.
 */
public final class MipMessageDispatcher {
  private final Iso8583Codec codec;
  private final IsoSchemaRef schema;
  private final MipSession session;
  private final MipNetworkManagement network;

  public MipMessageDispatcher(Iso8583Codec codec, IsoSchemaRef schema, MipSession session,
                              MipNetworkManagement network) {
    this.codec = codec;
    this.schema = schema;
    this.session = session;
    this.network = network;
  }

  public void receive(String peerId, byte[] payload) {
    MipPeer peer = session.peerFor(peerId);
    if (peer == null) return;
    var decoded = codec.decode(payload, schema);
    MipMessageType type = MipMessageType.fromValue(decoded.mti());
    switch (type) {
      case NETWORK_REQUEST -> peer.send(network.reply(decoded)).whenComplete((ignored, failure) -> {
        if (failure != null) {
          session.detach(peerId, failure);
          peer.close();
        }
      });
      case AUTHORIZATION_RESPONSE, NETWORK_RESPONSE -> session.receive(peerId, decoded, payload);
      case AUTHORIZATION_REQUEST -> throw new IllegalArgumentException("MIP peer must not originate 0100");
    }
  }
}
