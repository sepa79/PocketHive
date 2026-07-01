package io.pockethive.requestbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import io.pockethive.worker.sdk.api.Iso8583RequestEnvelope;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.TcpRequestEnvelope;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkStep;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import io.pockethive.worker.sdk.auth.AuthFailureJournalDeduplicator;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.worker.sdk.auth.AuthRuntime;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.MessageTemplate;
import io.pockethive.worker.sdk.templating.MessageTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.requesttemplates.Iso8583TemplateDefinition;
import io.pockethive.requesttemplates.TcpTemplateDefinition;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.requesttemplates.TemplateLoader;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("requestBuilderWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = RequestBuilderWorkerConfig.class
)
class RequestBuilderWorkerImpl implements PocketHiveWorkerFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private final TemplateRenderer templateRenderer;
  private final MessageTemplateRenderer messageTemplateRenderer;
  private final TemplateLoader templateLoader;
  private final RedisSequenceProperties redisProperties;
  private final AuthFailureJournalDeduplicator authFailureJournal = new AuthFailureJournalDeduplicator();
  private final J8583FieldListXmlCodec fieldListXmlCodec =
      new J8583FieldListXmlCodec(new Iso8583SchemaPackRegistry());
  private volatile Map<String, TemplateDefinition> templates;
  private volatile String lastTemplateConfigKey;
  private final LongAdder errorCount = new LongAdder();
  private final Object statusLock = new Object();
  private volatile long lastStatusAtMillis = System.currentTimeMillis();
  private volatile long lastErrorCountSnapshot = 0L;

  @Autowired
  RequestBuilderWorkerImpl(RequestBuilderWorkerProperties properties, 
                          TemplateRenderer templateRenderer,
                          RedisSequenceProperties redisProperties) {
    this(properties, templateRenderer, new TemplateLoader(), redisProperties);
  }

  RequestBuilderWorkerImpl(RequestBuilderWorkerProperties properties,
                        TemplateRenderer templateRenderer,
                        TemplateLoader templateLoader,
                        RedisSequenceProperties redisProperties) {
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.templateLoader = Objects.requireNonNull(templateLoader, "templateLoader");
    this.redisProperties = redisProperties == null ? new RedisSequenceProperties() : redisProperties;
    this.messageTemplateRenderer = new MessageTemplateRenderer(templateRenderer);
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    RequestBuilderWorkerConfig config =
        context.requireConfig(RequestBuilderWorkerConfig.class);

    WorkItem effectiveSeed = seed;
    if (effectiveSeed.headers().get("vars") == null && config.vars() != null && !config.vars().isEmpty()) {
      effectiveSeed = effectiveSeed.addStepHeader("vars", config.vars());
    }

    String serviceId = resolveServiceId(seed, config);
    String callId = resolveCallId(effectiveSeed);
    if (callId == null || callId.isBlank()) {
      context.logger().warn("No callId present on work item; {}", missingBehavior(config));
      return handleMissing(config, seed, context);
    }

    try {
      reloadTemplatesIfNeeded(config);
      TemplateDefinition definition =
          templates.get(TemplateLoader.key(serviceId, callId));
      if (definition == null) {
        context.logger().warn("No request template found for serviceId={} callId={}; {}", serviceId, callId, missingBehavior(config));
        return handleMissing(config, seed, context);
      }

      AuthRuntime authRuntime = AuthRuntime.forTemplates(
          config.templateRoot(), authRefs(templates), config.vars(), config.authProfileSutContext(), context, templateRenderer, redisProperties);
      Object envelope;
      String protocol = definition.protocol();
      if ("TCP".equals(protocol) && definition instanceof TcpTemplateDefinition tcpDef) {
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.SIMPLE)
            .bodyTemplate(tcpDef.bodyTemplate())
            .headerTemplates(tcpDef.headersTemplate() == null ? Map.of() : tcpDef.headersTemplate())
            .build();

        MessageTemplateRenderer.RenderedMessage rendered =
            messageTemplateRenderer.render(template, effectiveSeed);

        Map<String, String> headers = new HashMap<>(rendered.headers());
        String body = rendered.body();
        List<AuthRef> authApplications = List.of();
        if (tcpDef.authRef() != null) {
          switch (tcpDef.authRef().applyAs()) {
            case TCP_PAYLOAD_PREFIX, HMAC_PAYLOAD_FIELD -> body = authRuntime.applyTcpBody(tcpDef.authRef(), body, effectiveSeed, context);
            case ISO8583_MAC_FIELD, MTLS_CLIENT_CERT -> authApplications = List.of(tcpDef.authRef());
            default -> throw new IllegalArgumentException("Unsupported TCP auth applyAs: " + tcpDef.authRef().applyAs());
          }
        }

        envelope = TcpRequestEnvelope.of(
            new TcpRequestEnvelope.TcpRequest(
                tcpDef.behavior(),
                body,
                headers,
                tcpDef.endTag(),
                tcpDef.maxBytes(),
                authApplications
            ),
            tcpDef.resultRules()
        );
      } else if ("HTTP".equals(protocol) && definition instanceof HttpTemplateDefinition httpDef) {
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.HTTP)
            .pathTemplate(httpDef.pathTemplate())
            .methodTemplate(httpDef.method())
            .bodyTemplate(httpDef.bodyTemplate())
            .headerTemplates(httpDef.headersTemplate() == null ? Map.of() : httpDef.headersTemplate())
            .build();

        MessageTemplateRenderer.RenderedMessage rendered =
            messageTemplateRenderer.render(template, effectiveSeed);

        Map<String, String> headers = new HashMap<>(rendered.headers());

        String method = requireNonBlank(rendered.method(), "method").toUpperCase(Locale.ROOT);
        AuthRuntime.MutableHttpRequest authRequest = new AuthRuntime.MutableHttpRequest(
            method, rendered.path(), headers, rendered.body());
        if (httpDef.authRef() != null) {
          authRuntime.applyHttp(httpDef.authRef(), authRequest, effectiveSeed, context);
          headers = authRequest.headers();
        }
        String contentType = headers.getOrDefault("Content-Type", "").toLowerCase();
        boolean isJson = contentType.contains("application/json") ||
                        (contentType.isEmpty() && looksLikeJson(rendered.body()));
        envelope = HttpRequestEnvelope.of(
            new HttpRequestEnvelope.HttpRequest(
                method,
                authRequest.path(),
                headers,
                resolveBodyValue(rendered.body(), isJson)
            ),
            httpDef.resultRules()
        );
      } else if ("ISO8583".equals(protocol) && definition instanceof Iso8583TemplateDefinition isoDef) {
        envelope = buildIso8583Envelope(isoDef, effectiveSeed, context, serviceId, callId, authRuntime);
      } else {
        throw new IllegalStateException("Unsupported template protocol: " + protocol);
      }

      WorkItem httpItem = WorkItem.json(context.info(), envelope)
          .contentType("application/json")
          .build();

      context.logger().debug("Request Builder envelope: {}", httpItem.asString());
      WorkStep step = lastStep(httpItem);
      WorkItem result = seed.toBuilder()
          .contentType(httpItem.contentType())
          .step(context.info(), step.payload(), step.payloadEncoding(), step.headers())
          .build();
      publishStatus(context, config);
      return result;
    } catch (Exception ex) {
      var authFailure = AuthFailureException.find(ex);
      if (authFailure.isPresent()) {
        return handleAuthFailure(authFailure.get(), config, context, serviceId, callId);
      }
      context.logger().error("Request Builder failed to render template for serviceId={} callId={}",
          serviceId, callId, ex);
      recordError();
      publishStatus(context, config);
      throw new IllegalStateException(
          "Request Builder runtime failure for serviceId=%s callId=%s".formatted(serviceId, callId),
          ex);
    }
  }

  private WorkItem handleAuthFailure(
      AuthFailureException failure,
      RequestBuilderWorkerConfig config,
      WorkerContext context,
      String serviceId,
      String callId
  ) {
    recordError();
    publishStatus(context, config);
    AuthFailureJournalDeduplicator.Decision decision = authFailureJournal.record(
        context.info().swarmId() + ":" + context.info().instanceId() + ":request-builder",
        failure);
    if (decision.firstOccurrence()) {
      context.logger().error("Request Builder auth failure for serviceId={} callId={}: {}",
          serviceId, callId, failure.getMessage());
      throw failure;
    }
    context.logger().debug("Suppressed repeated Request Builder auth journal alert for serviceId={} callId={} occurrence={}",
        serviceId, callId, decision.occurrences());
    return null;
  }

  private void reloadTemplates(RequestBuilderWorkerConfig config) {
    Map<String, TemplateDefinition> loaded =
        templateLoader.load(config.templateRoot(), config.serviceId());
    this.templates = loaded;
    this.lastTemplateConfigKey = config.templateRoot() + "::" + config.serviceId();
  }

  private void reloadTemplatesIfNeeded(RequestBuilderWorkerConfig config) {
    String key = config.templateRoot() + "::" + config.serviceId();
    Map<String, TemplateDefinition> current = this.templates;
    if (current == null || !key.equals(lastTemplateConfigKey)) {
      reloadTemplates(config);
    }
  }

  private WorkItem handleMissing(RequestBuilderWorkerConfig config, WorkItem seed, WorkerContext context) {
    recordError();
    publishStatus(context, config);
    return Boolean.TRUE.equals(config.passThroughOnMissingTemplate()) ? seed : null;
  }

  private static String missingBehavior(RequestBuilderWorkerConfig config) {
    return Boolean.TRUE.equals(config.passThroughOnMissingTemplate())
        ? "passing work item through unchanged"
        : "dropping work item (no output)";
  }

  private WorkStep lastStep(WorkItem item) {
    WorkStep last = null;
    for (WorkStep step : item.steps()) {
      last = step;
    }
    if (last == null) {
      throw new IllegalStateException("Request Builder produced a WorkItem without steps");
    }
    return last;
  }

  private static String resolveServiceId(WorkItem item, RequestBuilderWorkerConfig config) {
    Object header = item.headers().get("x-ph-service-id");
    if (header instanceof String s && !s.isBlank()) {
      return s.trim();
    }
    return config.serviceId();
  }

  private static String resolveCallId(WorkItem item) {
    Object header = item.headers().get("x-ph-call-id");
    if (header instanceof String s && !s.isBlank()) {
      return s.trim();
    }
    return null;
  }

  private Object resolveBodyValue(String body, boolean isJson) {
    if (body == null || body.isBlank()) {
      return "";
    }
    if (isJson) {
      try {
        return MAPPER.readValue(body, Object.class);
      } catch (Exception ignored) {
      }
    }
    return body;
  }

  private static boolean looksLikeJson(String body) {
    if (body == null || body.isBlank()) return false;
    char first = body.trim().charAt(0);
    return first == '{' || first == '[';
  }

  private Iso8583RequestEnvelope buildIso8583Envelope(Iso8583TemplateDefinition isoDef,
                                                      WorkItem effectiveSeed,
                                                      WorkerContext context,
                                                      String serviceId,
                                                      String callId,
                                                      AuthRuntime authRuntime) {
    MessageTemplate template = MessageTemplate.builder()
        .bodyType(MessageBodyType.SIMPLE)
        .bodyTemplate(isoDef.bodyTemplate())
        .headerTemplates(isoDef.headersTemplate() == null ? Map.of() : isoDef.headersTemplate())
        .build();
    MessageTemplateRenderer.RenderedMessage rendered = messageTemplateRenderer.render(template, effectiveSeed);

    Map<String, String> headers = new HashMap<>(rendered.headers());
    List<AuthRef> authApplications = isoDef.authRef() == null
        ? List.of()
        : List.of(isoDef.authRef());

    String payloadAdapter = requireNonBlank(isoDef.payloadAdapter(), "payloadAdapter").toUpperCase(Locale.ROOT);
    String wireProfileId = requireNonBlank(isoDef.wireProfileId(), "wireProfileId");

    if ("RAW_HEX".equals(payloadAdapter)) {
      return Iso8583RequestEnvelope.of(new Iso8583RequestEnvelope.Iso8583Request(
          wireProfileId,
          "RAW_HEX",
          requireNonBlank(rendered.body(), "payload"),
          headers,
          null,
          authApplications
      ), isoDef.resultRules());
    }

    if ("FIELD_LIST_XML".equals(payloadAdapter)) {
      Iso8583TemplateDefinition.IsoSchemaRef templateSchema = isoDef.schemaRef();
      if (templateSchema == null) {
        throw new IllegalArgumentException("schemaRef must not be null for FIELD_LIST_XML");
      }
      Iso8583RequestEnvelope.IsoSchemaRef schemaRef = new Iso8583RequestEnvelope.IsoSchemaRef(
          templateSchema.schemaRegistryRoot(),
          templateSchema.schemaId(),
          templateSchema.schemaVersion(),
          templateSchema.schemaAdapter(),
          templateSchema.schemaFile()
      );
      byte[] encoded = fieldListXmlCodec.encodePayload(rendered.body(), schemaRef);
      String hexPayload = HexFormat.of().withUpperCase().formatHex(encoded);
      return Iso8583RequestEnvelope.of(new Iso8583RequestEnvelope.Iso8583Request(
          wireProfileId,
          "RAW_HEX",
          hexPayload,
          headers,
          null,
          authApplications
      ), isoDef.resultRules());
    }

    throw new IllegalArgumentException("Unsupported ISO8583 payloadAdapter in template: " + payloadAdapter);
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private static List<AuthRef> authRefs(Map<String, TemplateDefinition> templates) {
    if (templates == null || templates.isEmpty()) {
      return List.of();
    }
    return templates.values().stream()
        .map(definition -> {
          if (definition instanceof HttpTemplateDefinition http) {
            return http.authRef();
          }
          if (definition instanceof TcpTemplateDefinition tcp) {
            return tcp.authRef();
          }
          if (definition instanceof Iso8583TemplateDefinition iso) {
            return iso.authRef();
          }
          return null;
        })
        .filter(Objects::nonNull)
        .toList();
  }

  private void recordError() {
    errorCount.increment();
  }

  private void publishStatus(WorkerContext context, RequestBuilderWorkerConfig config) {
    long now = System.currentTimeMillis();
    synchronized (statusLock) {
      long totalErrors = errorCount.sum();
      long deltaErrors = totalErrors - lastErrorCountSnapshot;
      long deltaMillis = now - lastStatusAtMillis;
      double errorTps = 0.0;
      if (deltaMillis > 0L && deltaErrors > 0L) {
        errorTps = (deltaErrors * 1000.0) / deltaMillis;
      }
      lastStatusAtMillis = now;
      lastErrorCountSnapshot = totalErrors;
      final double finalErrorTps = errorTps;
      final long finalTotalErrors = totalErrors;
      context.statusPublisher()
          .update(status -> status
              .data("templateRoot", config.templateRoot())
              .data("serviceId", config.serviceId())
              .data("passThroughOnMissingTemplate", config.passThroughOnMissingTemplate())
              .data("errorCount", finalTotalErrors)
              .data("errorTps", finalErrorTps));
    }
  }
}
