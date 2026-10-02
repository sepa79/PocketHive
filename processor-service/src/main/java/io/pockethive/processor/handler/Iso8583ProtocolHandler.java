package io.pockethive.processor.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.processor.ProcessorPacer;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.metrics.CallMetrics;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.mip.MipReply;
import io.pockethive.processor.transport.TcpTransportRuntime;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.Iso8583RequestEnvelope;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: coordinate canonical ISO request preparation, pacing, explicit byte execution and result construction.
 * Must not: own schema parsing, session transitions, transport pools or pacing state.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute;
 * delegates transport lifetime, pacing and MIP sessions to their distinct responsibility owners.
 */
public class Iso8583ProtocolHandler implements ProtocolHandler {
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final ProcessorPacer pacer;
  private final Iso8583EnvelopeCodec envelopeCodec;
  private final Iso8583PayloadAuthentication authentication;
  private final Iso8583ResultBuilder resultBuilder;
  private final Iso8583ClientExchange clientExchange = new Iso8583ClientExchange(new TcpTransportRuntime());
  private final Iso8583ServerExchange serverExchange;

  public Iso8583ProtocolHandler(ObjectMapper mapper, Clock clock, CallMetricsRecorder metricsRecorder,
                                ProcessorPacer pacer, TemplateRenderer templateRenderer,
                                RedisSequenceProperties redisProperties) {
    this(mapper, clock, metricsRecorder, pacer, templateRenderer, redisProperties,
        Iso8583ServerExchange.clientOnly());
  }

  public Iso8583ProtocolHandler(ObjectMapper mapper, Clock clock, CallMetricsRecorder metricsRecorder,
                                ProcessorPacer pacer, TemplateRenderer templateRenderer,
                                RedisSequenceProperties redisProperties, Iso8583ServerExchange serverExchange) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    this.pacer = Objects.requireNonNull(pacer, "pacer");
    this.envelopeCodec = new Iso8583EnvelopeCodec(mapper);
    this.authentication = new Iso8583PayloadAuthentication(templateRenderer, redisProperties);
    this.resultBuilder = new Iso8583ResultBuilder(mapper);
    this.serverExchange = Objects.requireNonNull(serverExchange, "serverExchange");
  }

  @Override
  public WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig config, WorkerContext context)
      throws Exception {
    Iso8583RequestEnvelope request;
    Iso8583Endpoint endpoint;
    try {
      request = envelopeCodec.decodeEnvelope(envelope);
      endpoint = Iso8583Endpoint.parse(config.baseUrl());
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex,
          Map.of("transport", Iso8583ResultBuilder.TRANSPORT));
    }

    Iso8583WireProfile profile;
    Iso8583AuthenticatedPayload authenticated;
    try {
      profile = Iso8583WireProfile.fromId(request.request().wireProfileId());
      authenticated = authentication.prepare(envelopeCodec.decodePayload(request.request()),
          request.request(), endpoint, message, config, context);
      profile.validatePayload(authenticated.payload());
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex,
          resultBuilder.requestMetadata(endpoint, request));
    }

    long start = clock.millis();
    long pacingMillis = 0L;
    try {
      pacingMillis = pacer.await(config);
      byte[] payload = authenticated.payload();
      byte[] response;
      Map<String, String> responseHeaders;
      if (endpoint.mipServer()) {
        MipReply reply = serverExchange.exchange(payload, config);
        response = reply.payload();
        responseHeaders = Iso8583ResponseHeaders.project(reply.decoded());
      } else {
        response = clientExchange.exchange(payload, profile, endpoint, config,
            authenticated.transportOptions(), context);
        responseHeaders = Map.of();
      }
      long callDuration = Math.max(0L, clock.millis() - start - pacingMillis);
      CallMetrics metrics = CallMetrics.success(callDuration, Math.max(0L, pacingMillis),
          Iso8583ResultBuilder.RESPONSE_STATUS);
      WorkItem result = resultBuilder.build(message, request, endpoint, profile, payload.length,
          response, responseHeaders, metrics, context.info());
      metricsRecorder.record(metrics);
      return result;
    } catch (Exception ex) {
      long callDuration = Math.max(0L, clock.millis() - start - pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, Math.max(0L, pacingMillis), -1);
      metricsRecorder.record(metrics);
      throw new ProcessorCallException(metrics, ex, resultBuilder.requestMetadata(endpoint, request));
    }
  }

  @Override
  public void close() {
    clientExchange.close();
  }
}
