package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.Iso8583Request;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.worker.sdk.auth.AuthApplyAs;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.worker.sdk.auth.AuthRuntime;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.util.HexFormat;
import java.util.Map;

/**
 * Responsibility: prepare ISO request credentials through the canonical auth runtime before framing.
 * Must not: frame bytes, open transports or silently ignore unsupported server credentials.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
final class Iso8583PayloadAuthentication {
  private final TemplateRenderer templateRenderer;
  private final RedisSequenceProperties redisProperties;

  Iso8583PayloadAuthentication(TemplateRenderer templateRenderer, RedisSequenceProperties redisProperties) {
    this.templateRenderer = templateRenderer;
    this.redisProperties = redisProperties;
  }

  Iso8583AuthenticatedPayload prepare(byte[] payload, Iso8583Request request, Iso8583Endpoint endpoint,
                                      WorkItem message, ProcessorWorkerConfig config, WorkerContext context) {
    if (endpoint.mipServer() && request.authApplications().stream()
        .anyMatch(ref -> ref.applyAs() == AuthApplyAs.MTLS_CLIENT_CERT)) {
      throw new IllegalArgumentException("MTLS_CLIENT_CERT is not supported by the plaintext MIP server");
    }
    if (request.authApplications().isEmpty()) {
      return new Iso8583AuthenticatedPayload(payload, Map.of());
    }
    Map<String, Object> transportOptions = Map.of();
    try (AuthRuntime runtime = AuthRuntime.forApplications(request.authApplications(), Map.of(),
        config.authProfileSutContext(), context, templateRenderer, redisProperties)) {
      String payloadHex = HexFormat.of().withUpperCase().formatHex(payload);
      for (AuthRef ref : request.authApplications()) {
        if (ref.applyAs() == AuthApplyAs.MTLS_CLIENT_CERT) {
          transportOptions = runtime.transportOptions(ref, context);
        } else {
          payloadHex = runtime.applyIsoPayloadHex(ref, payloadHex, message, context);
        }
      }
      return new Iso8583AuthenticatedPayload(HexFormat.of().parseHex(payloadHex), transportOptions);
    }
  }
}
