package io.pockethive.functionalswarm.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.HttpResultEnvelope;
import java.util.Objects;

/** Canonical JSON codec for Functional Swarm RPC and Processor HTTP results. */
public final class FunctionalSwarmJsonCodec {
  private final ObjectMapper mapper;

  public FunctionalSwarmJsonCodec() {
    this(new ObjectMapper().findAndRegisterModules());
  }

  FunctionalSwarmJsonCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public String writeRequest(FunctionalSwarmRpcRequest request) {
    try {
      return mapper.writeValueAsString(Objects.requireNonNull(request, "request"));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not serialize Functional Swarm RPC request", ex);
    }
  }

  public FunctionalSwarmRpcRequest readRequest(String raw) {
    try {
      return mapper.readerFor(FunctionalSwarmRpcRequest.class)
          .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .readValue(requireText(raw));
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid Functional Swarm RPC request", ex);
    }
  }

  public HttpResultEnvelope readHttpResult(String raw) {
    try {
      return mapper.readerFor(HttpResultEnvelope.class)
          .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .readValue(requireText(raw));
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid Functional Swarm HTTP result", ex);
    }
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("JSON payload must not be blank");
    }
    return value;
  }
}
