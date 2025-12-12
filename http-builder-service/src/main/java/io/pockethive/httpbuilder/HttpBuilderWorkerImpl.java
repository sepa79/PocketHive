package io.pockethive.httpbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.MessageTemplate;
import io.pockethive.worker.sdk.templating.MessageTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("httpBuilderWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = HttpBuilderWorkerConfig.class
)
class HttpBuilderWorkerImpl implements PocketHiveWorkerFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private final HttpBuilderWorkerProperties properties;
  private final TemplateRenderer templateRenderer;
  private final MessageTemplateRenderer messageTemplateRenderer;
  private final HttpTemplateLoader templateLoader;
  private volatile Map<String, HttpTemplateDefinition> templates;
  private volatile String lastTemplateConfigKey;
  private final LongAdder errorCount = new LongAdder();
  private final Object statusLock = new Object();
  private volatile long lastStatusAtMillis = System.currentTimeMillis();
  private volatile long lastErrorCountSnapshot = 0L;

  @Autowired
  HttpBuilderWorkerImpl(HttpBuilderWorkerProperties properties, TemplateRenderer templateRenderer) {
    this(properties, templateRenderer, new HttpTemplateLoader());
  }

  HttpBuilderWorkerImpl(HttpBuilderWorkerProperties properties,
                        TemplateRenderer templateRenderer,
                        HttpTemplateLoader templateLoader) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.templateLoader = Objects.requireNonNull(templateLoader, "templateLoader");
    this.messageTemplateRenderer = new MessageTemplateRenderer(templateRenderer);
    reloadTemplates();
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    HttpBuilderWorkerConfig config =
        context.configOrDefault(HttpBuilderWorkerConfig.class, properties::defaultConfig);

    reloadTemplatesIfNeeded(config);

    String serviceId = resolveServiceId(seed, config);
    String callId = resolveCallId(seed);
    if (callId == null || callId.isBlank()) {
      context.logger().warn("No callId present on work item; {}", missingBehavior(config));
      return handleMissing(config, seed, context);
    }

    HttpTemplateDefinition definition =
        templates.get(HttpTemplateLoader.key(serviceId, callId));
    if (definition == null) {
      context.logger().warn("No HTTP template found for serviceId={} callId={}; {}", serviceId, callId, missingBehavior(config));
      return handleMissing(config, seed, context);
    }

    try {
      MessageTemplate template = MessageTemplate.builder()
          .bodyType(MessageBodyType.HTTP)
          .pathTemplate(definition.pathTemplate())
          .methodTemplate(definition.method())
          .bodyTemplate(definition.bodyTemplate())
          .headerTemplates(definition.headersTemplate() == null ? Map.of() : definition.headersTemplate())
          .build();

      MessageTemplateRenderer.RenderedMessage rendered =
          messageTemplateRenderer.render(template, seed);

      ObjectNode envelope = MAPPER.createObjectNode();
      envelope.put("protocol", "HTTP");
      envelope.put("path", rendered.path());
      envelope.put("method", rendered.method() == null ? "GET" : rendered.method().toUpperCase(Locale.ROOT));
      envelope.set("headers", MAPPER.valueToTree(rendered.headers()));
      envelope.put("body", rendered.body());

      WorkItem httpItem = WorkItem.json(envelope)
          .header("content-type", "application/json")
          .header("x-ph-service", context.info().role())
          .build();

      WorkItem result = seed.addStep(httpItem.asString(), httpItem.headers());
      publishStatus(context, config);
      return result;
    } catch (Exception ex) {
      context.logger().warn("HTTP Builder failed to render template for serviceId={} callId={}: {}",
          serviceId, callId, ex.toString());
      recordError();
      publishStatus(context, config);
      return config.passThroughOnMissingTemplate() ? seed : null;
    }
  }

  private void reloadTemplates() {
    reloadTemplates(properties.defaultConfig());
  }

  private void reloadTemplates(HttpBuilderWorkerConfig config) {
    Map<String, HttpTemplateDefinition> loaded =
        templateLoader.load(config.templateRoot(), config.serviceId());
    this.templates = loaded;
    this.lastTemplateConfigKey = config.templateRoot() + "::" + config.serviceId();
  }

  private void reloadTemplatesIfNeeded(HttpBuilderWorkerConfig config) {
    String key = config.templateRoot() + "::" + config.serviceId();
    Map<String, HttpTemplateDefinition> current = this.templates;
    if (current == null || !key.equals(lastTemplateConfigKey)) {
      reloadTemplates(config);
    }
  }

  private WorkItem handleMissing(HttpBuilderWorkerConfig config, WorkItem seed, WorkerContext context) {
    recordError();
    publishStatus(context, config);
    return config.passThroughOnMissingTemplate() ? seed : null;
  }

  private static String missingBehavior(HttpBuilderWorkerConfig config) {
    return config.passThroughOnMissingTemplate()
        ? "passing work item through unchanged"
        : "dropping work item (no output)";
  }

  private static String resolveServiceId(WorkItem item, HttpBuilderWorkerConfig config) {
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

  private void recordError() {
    errorCount.increment();
  }

  private void publishStatus(WorkerContext context, HttpBuilderWorkerConfig config) {
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
