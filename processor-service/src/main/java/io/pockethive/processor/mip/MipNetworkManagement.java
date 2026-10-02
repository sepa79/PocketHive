package io.pockethive.processor.mip;

import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.iso8583.Iso8583Message;
import io.pockethive.work.api.IsoSchemaRef;
import java.util.LinkedHashMap;

/**
 * Responsibility: construct 0810 from the explicitly selected copy/static-field layout.
 * Must not: infer Mastercard layouts, settle authorization calls or own peer state.
 * Contract: RESP-PROCESSOR-MIP-NETWORK — docs/architecture/runtime-responsibilities.md#resp-processor-mip-network.
 */
public final class MipNetworkManagement {
  private final Iso8583Codec codec;
  private final IsoSchemaRef schema;
  private final MipNetworkLayout layout;

  public MipNetworkManagement(Iso8583Codec codec, IsoSchemaRef schema, MipNetworkLayout layout) {
    this.codec = codec;
    this.schema = schema;
    this.layout = layout;
    codec.validateFields(schema, MipMessageType.NETWORK_REQUEST.value(), layout.copyFields());
    codec.validateFields(schema, MipMessageType.NETWORK_RESPONSE.value(), layout.copyFields());
    codec.encode(MipMessageType.NETWORK_RESPONSE.value(), layout.responseFields(), schema);
  }

  public void validateRequest(Iso8583Message request) {
    if (request.mti() != MipMessageType.NETWORK_REQUEST.value()) {
      throw new IllegalArgumentException("network-management request must be 0800");
    }
    if (!layout.supportedCodes().contains(request.field(MipFields.NETWORK_CODE))) {
      throw new IllegalArgumentException("Unsupported network-management code");
    }
    MipSession.requireStan(request);
  }

  public byte[] reply(Iso8583Message request) {
    validateRequest(request);
    var fields = new LinkedHashMap<>(layout.responseFields());
    for (int number : layout.copyFields()) {
      String value = request.field(number);
      if (value == null) throw new IllegalArgumentException("Missing copied network field: " + number);
      fields.put(number, value);
    }
    return codec.encode(MipMessageType.NETWORK_RESPONSE.value(), fields, schema);
  }
}
