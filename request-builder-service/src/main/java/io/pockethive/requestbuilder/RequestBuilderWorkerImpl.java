package io.pockethive.requestbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthHeaderGenerator;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.MessageTemplate;
import io.pockethive.worker.sdk.templating.MessageTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.HashMap;
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
      context.logger().warn("No HTTP template found for serviceId={} callId={}; {}", serviceId, callId, missingBehavior(config));
      return handleMissing(config, seed, context);
    }

    try {
      ObjectNode envelope = MAPPER.createObjectNode();
      String protocol = definition.protocol() == null ? "HTTP" : definition.protocol().toUpperCase(Locale.ROOT);

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

        envelope.put("protocol", "TCP");
        envelope.put("method", "");
        envelope.put("behavior", tcpDef.behavior());
        if (tcpDef.endTag() != null) {
          envelope.put("endTag", tcpDef.endTag());
        }
        if (tcpDef.maxBytes() != null) {
          envelope.put("maxBytes", tcpDef.maxBytes());
        }
        envelope.put("body", rendered.body());
        envelope.set("headers", MAPPER.valueToTree(headers));
        if (hasResultRules(tcpDef.resultRules())) {
          envelope.set("resultRules", MAPPER.valueToTree(tcpDef.resultRules()));
        }
      } else if (definition instanceof HttpTemplateDefinition httpDef) {
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
        envelope.put("protocol", "HTTP");
        envelope.put("path", rendered.path());
        envelope.put("method", method);
        envelope.set("headers", MAPPER.valueToTree(headers));
        String contentType = headers.getOrDefault("Content-Type", "").toLowerCase();
        // Detect JSON to embed as compact node instead of escaped string
        boolean isJson = contentType.contains("application/json") ||
                        (contentType.isEmpty() && looksLikeJson(rendered.body()));
        setBodyNode(envelope, rendered.body(), isJson);
        if (hasResultRules(httpDef.resultRules())) {
          envelope.set("resultRules", MAPPER.valueToTree(httpDef.resultRules()));
        }
      } else {
        throw new IllegalStateException("Unknown template type: " + definition.getClass());
      }

      WorkItem httpItem = WorkItem.json(envelope)
          .header("content-type", "application/json")
          .header("x-ph-service", context.info().role())
          .build();

      context.logger().debug("Request Builder envelope: {}", httpItem.asString());
      WorkItem result = seed.addStep(httpItem.asString(), httpItem.headers());
      publishStatus(context, config);
      return result;
    } catch (Exception ex) {
      context.logger().error("Request Builder failed to render template for serviceId={} callId={}",
          serviceId, callId, ex);
      recordError();
      publishStatus(context, config);
      return config.passThroughOnMissingTemplate() ? seed : null;
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

  private void setBodyNode(ObjectNode envelope, String body, boolean isJson) {
    if (body == null || body.isBlank()) {
      envelope.put("body", "");
      return;
    }
    if (isJson) {
      try {
        envelope.set("body", MAPPER.readTree(body));
        return;
      } catch (Exception ignored) {
      }
    }
    envelope.put("body", body);
  }

  private static boolean looksLikeJson(String body) {
    if (body == null || body.isBlank()) return false;
    char first = body.trim().charAt(0);
    return first == '{' || first == '[';
  }

  private static boolean hasResultRules(io.pockethive.httpbuilder.HttpTemplateDefinition.ResultRules rules) {
    if (rules == null) {
      return false;
    }
    if (rules.businessCode() != null) {
      return true;
    }
    if (rules.successRegex() != null && !rules.successRegex().isBlank()) {
      return true;
    }
    return rules.dimensions() != null && !rules.dimensions().isEmpty();
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
