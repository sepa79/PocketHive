package io.pockethive.functionalswarm.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FunctionalSwarmJsonCodecTest {
  private final FunctionalSwarmJsonCodec codec = new FunctionalSwarmJsonCodec();

  @Test
  void roundTripsVersionedRequestWithoutChangingInvocationPayload() {
    String requestId = UUID.randomUUID().toString();
    FunctionalSwarmRpcRequest request = new FunctionalSwarmRpcRequest(
        FunctionalSwarmProtocol.VERSION,
        requestId,
        "pockethive.functional.reply." + requestId,
        new FunctionalSwarmInvocation("  body with whitespace  ", Map.of("x-tenant", "acme")));

    FunctionalSwarmRpcRequest decoded = codec.readRequest(codec.writeRequest(request));

    assertThat(decoded).isEqualTo(request);
    assertThat(decoded.invocation().payload()).isEqualTo("  body with whitespace  ");
  }

  @Test
  void rejectsUnknownWireFieldsAndCallerSuppliedTransportHeaders() {
    assertThatThrownBy(() -> codec.readRequest("""
        {"protocolVersion":"1.0","requestId":"00000000-0000-0000-0000-000000000001",
         "replyList":"pockethive.functional.reply.00000000-0000-0000-0000-000000000001",
         "invocation":{"payload":"body","headers":{}},"unexpected":true}
        """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Functional Swarm RPC request");

    assertThatThrownBy(() -> new FunctionalSwarmInvocation(
        "body", Map.of(FunctionalSwarmProtocol.REPLY_LIST_HEADER, "attacker-list")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("transport header");
  }
}
