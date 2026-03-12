package io.pockethive.requestbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import io.pockethive.worker.sdk.api.Iso8583RequestEnvelope;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.TcpRequestEnvelope;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkStep;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthHeaderGenerator;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component("requestBuilderWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = RequestBuilderWorkerConfig.class
)
class RequestBuilderWorkerImpl implements PocketHiveWorkerFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private final RequestBuilderWorkerProperties properties;
  private final TemplateRenderer templateRenderer;
  private final MessageTemplateRenderer messageTemplateRenderer;
  private final TemplateLoader templateLoader;
  private final AuthHeaderGenerator authHeaderGenerator;
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
                          @Nullable AuthHeaderGenerator authHeaderGenerator) {
    this(properties, templateRenderer, new TemplateLoader(), authHeaderGenerator);
  }

  RequestBuilderWorkerImpl(RequestBuilderWorkerProperties properties,
                        TemplateRenderer templateRenderer,
                        TemplateLoader templateLoader,
                        @Nullable AuthHeaderGenerator authHeaderGenerator) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.templateLoader = Objects.requireNonNull(templateLoader, "templateLoader");
    this.authHeaderGenerator = authHeaderGenerator;
    this.messageTemplateRenderer = new MessageTemplateRenderer(templateRenderer);
    reloadTemplates();
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    RequestBuilderWorkerConfig config =
        context.configOrDefault(RequestBuilderWorkerConfig.class, properties::defaultConfig);

    reloadTemplatesIfNeeded(config);

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

    TemplateDefinition definition =
        templates.get(TemplateLoader.key(serviceId, callId));
    if (definition == null) {
      context.logger().warn("No request template found for serviceId={} callId={}; {}", serviceId, callId, missingBehavior(config));
      return handleMissing(config, seed, context);
    }

    try {
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
        
        // Add auth headers if configured in template
        if (tcpDef.auth() != null && authHeaderGenerator != null) {
          try {
            AuthConfig authConfig = AuthConfig.fromTemplate(tcpDef.auth(), serviceId, callId);
            Map<String, String> authHeaders = authHeaderGenerator.generate(context, authConfig, seed);
            headers.putAll(authHeaders);
          } catch (Exception ex) {
            context.logger().warn("Failed to generate auth headers for serviceId={} callId={}", serviceId, callId, ex);
          }
        }

        envelope = TcpRequestEnvelope.of(
            new TcpRequestEnvelope.TcpRequest(
                tcpDef.behavior(),
                rendered.body(),
                headers,
                tcpDef.endTag(),
                tcpDef.maxBytes()
            )
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
        
        // Add auth headers if configured in template
        if (httpDef.auth() != null && authHeaderGenerator != null) {
          try {
            AuthConfig authConfig = AuthConfig.fromTemplate(httpDef.auth(), serviceId, callId);
            Map<String, String> authHeaders = authHeaderGenerator.generate(context, authConfig, seed);
            headers.putAll(authHeaders);
          } catch (Exception ex) {
            context.logger().warn("Failed to generate auth headers for serviceId={} callId={}", serviceId, callId, ex);
          }
        }

        String method = rendered.method() == null ? "GET" : rendered.method().toUpperCase(Locale.ROOT);
        String contentType = headers.getOrDefault("Content-Type", "").toLowerCase();
        boolean isJson = contentType.contains("application/json") ||
                        (contentType.isEmpty() && looksLikeJson(rendered.body()));
        envelope = HttpRequestEnvelope.of(
            new HttpRequestEnvelope.HttpRequest(
                method,
                rendered.path(),
                headers,
                resolveBodyValue(rendered.body(), isJson)
            )
        );
      } else if ("ISO8583".equals(protocol) && definition instanceof Iso8583TemplateDefinition isoDef) {
        envelope = buildIso8583Envelope(isoDef, effectiveSeed, context, serviceId, callId);
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
      context.logger().error("Request Builder failed to render template for serviceId={} callId={}",
          serviceId, callId, ex);
      recordError();
      publishStatus(context, config);
      throw new IllegalStateException(
          "Request Builder runtime failure for serviceId=%s callId=%s".formatted(serviceId, callId),
          ex);
    }
  }

  private void reloadTemplates() {
    reloadTemplates(properties.defaultConfig());
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
    return config.passThroughOnMissingTemplate() ? seed : null;
  }

  private static String missingBehavior(RequestBuilderWorkerConfig config) {
    return config.passThroughOnMissingTemplate()
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
                                                      String callId) {
    MessageTemplate template = MessageTemplate.builder()
        .bodyType(MessageBodyType.SIMPLE)
        .bodyTemplate(isoDef.bodyTemplate())
        .headerTemplates(isoDef.headersTemplate() == null ? Map.of() : isoDef.headersTemplate())
        .build();
    MessageTemplateRenderer.RenderedMessage rendered = messageTemplateRenderer.render(template, effectiveSeed);

    Map<String, String> headers = new HashMap<>(rendered.headers());
    if (isoDef.auth() != null && authHeaderGenerator != null) {
      try {
        AuthConfig authConfig = AuthConfig.fromTemplate(isoDef.auth(), serviceId, callId);
        Map<String, String> authHeaders = authHeaderGenerator.generate(context, authConfig, effectiveSeed);
        headers.putAll(authHeaders);
      } catch (Exception ex) {
        context.logger().warn("Failed to generate auth headers for serviceId={} callId={}", serviceId, callId, ex);
      }
    }

    String payloadAdapter = requireNonBlank(isoDef.payloadAdapter(), "payloadAdapter").toUpperCase(Locale.ROOT);
    String wireProfileId = requireNonBlank(isoDef.wireProfileId(), "wireProfileId");

    if ("RAW_HEX".equals(payloadAdapter)) {
      return Iso8583RequestEnvelope.of(new Iso8583RequestEnvelope.Iso8583Request(
          wireProfileId,
          "RAW_HEX",
          requireNonBlank(rendered.body(), "payload"),
          headers,
          null
      ));
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
          null
      ));
    }

    throw new IllegalArgumentException("Unsupported ISO8583 payloadAdapter in template: " + payloadAdapter);
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
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
