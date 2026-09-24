package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.TcpRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project UI request logs with their existing matched predicate.
 * Must not: mutate requests or decide unmatched journal membership.
 * Contract: RESP-TCP-MOCK-WEB-TOOLS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-web-tools.
 */
@Service
public class RequestLogProjection {

    public RequestLogProjection() {
    }

  public Map<String, Object> toMap(TcpRequest request) {
    boolean matched = request.getResponse() != null &&
                     !request.getResponse().isEmpty() &&
                     !request.getResponse().startsWith("ERROR") &&
                     !request.getResponse().equals("INVALID_MESSAGE") &&
                     !request.getResponse().equals("OK") &&
                     !request.getResponse().equals("UNKNOWN_MESSAGE_TYPE");

    return Map.of(
        "id", request.getId(),
        "message", request.getMessage() != null ? request.getMessage() : "",
        "response", request.getResponse() != null ? request.getResponse() : "",
        "timestamp", request.getTimestamp().toString(),
        "matched", matched,
        "clientAddress", request.getClientAddress() != null ? request.getClientAddress() : "",
        "behavior", request.getBehavior() != null ? request.getBehavior() : ""
    );
  }

}
