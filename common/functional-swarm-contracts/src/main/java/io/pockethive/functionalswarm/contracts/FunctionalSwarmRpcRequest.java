package io.pockethive.functionalswarm.contracts;

import java.util.Objects;
import java.util.UUID;

/** Versioned request written to the configured Functional Swarm ingress list. */
public record FunctionalSwarmRpcRequest(
    String protocolVersion,
    String requestId,
    String replyList,
    FunctionalSwarmInvocation invocation
) {
  public FunctionalSwarmRpcRequest {
    if (!FunctionalSwarmProtocol.VERSION.equals(protocolVersion)) {
      throw new IllegalArgumentException("Unsupported Functional Swarm protocol version: " + protocolVersion);
    }
    requestId = requireUuid(requestId);
    replyList = requireText(replyList, "replyList");
    invocation = Objects.requireNonNull(invocation, "invocation");
  }

  private static String requireUuid(String value) {
    String candidate = requireText(value, "requestId");
    try {
      UUID.fromString(candidate);
      return candidate;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("requestId must be a UUID", ex);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
