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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("httpBuilderWorker")
@PocketHiveWorker(
    input = WorkerInputType.RABBITMQ,
    output = WorkerOutputType.RABBITMQ,
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

    if (templates == null) {
      reloadTemplates(config);
    }

    String serviceId = resolveServiceId(seed, config);
    String callId = resolveCallId(seed);
    if (callId == null || callId.isBlank()) {
      context.logger().warn("No callId present on work item; skipping HTTP building");
      return seed;
    }

    HttpTemplateDefinition definition =
        templates.get(HttpTemplateLoader.key(serviceId, callId));
    if (definition == null) {
      context.logger().warn("No HTTP template found for serviceId={} callId={}", serviceId, callId);
      return seed;
    }

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
    envelope.put("path", rendered.path());
    envelope.put("method", rendered.method() == null ? "GET" : rendered.method().toUpperCase(Locale.ROOT));
    envelope.set("headers", MAPPER.valueToTree(rendered.headers()));
    envelope.put("body", rendered.body());

    WorkItem httpItem = WorkItem.json(envelope)
        .header("content-type", "application/json")
        .header("x-ph-service", context.info().role())
        .build();

    return seed.addStep(httpItem.asString(), httpItem.headers());
  }

  private void reloadTemplates() {
    reloadTemplates(properties.defaultConfig());
  }

  private void reloadTemplates(HttpBuilderWorkerConfig config) {
    this.templates = templateLoader.load(config.templateRoot(), config.serviceId());
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
}

