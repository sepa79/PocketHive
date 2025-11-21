package io.pockethive.dataprovider;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("dataProviderWorker")
@PocketHiveWorker(
    input = WorkerInputType.REDIS_DATASET,
    output = WorkerOutputType.RABBITMQ,
    capabilities = {WorkerCapability.SCHEDULER},
    config = DataProviderWorkerConfig.class
)
class DataProviderWorkerImpl implements PocketHiveWorkerFunction {

  private final DataProviderWorkerProperties properties;
  private final MessageTemplateRenderer messageTemplateRenderer;
  private final TemplateRenderer templateRenderer;

  @Autowired
  DataProviderWorkerImpl(DataProviderWorkerProperties properties, TemplateRenderer templateRenderer) {
    this.properties = properties;
    this.templateRenderer = templateRenderer;
    this.messageTemplateRenderer = new MessageTemplateRenderer(templateRenderer);
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    DataProviderWorkerConfig config = context.configOrDefault(DataProviderWorkerConfig.class, properties::defaultConfig);

    Map<String, Object> headers = new LinkedHashMap<>(seed.headers());
    DataProviderWorkerConfig.Template templateConfig = config.template();
    Map<String, String> headerTemplates = new LinkedHashMap<>(properties.defaultConfig().template().headers());
    headerTemplates.putAll(templateConfig.headers());
    MessageTemplate template = MessageTemplate.builder()
        .bodyType(templateConfig.bodyType())
        .pathTemplate(templateConfig.path())
        .methodTemplate(templateConfig.method())
        .bodyTemplate(templateConfig.body())
        .headerTemplates(headerTemplates)
        .build();
    MessageTemplateRenderer.RenderedMessage rendered = messageTemplateRenderer.render(template, seed);

    headers.put("message-id", UUID.randomUUID().toString());
    headers.put("x-ph-service", context.info().role());
    if (rendered.headers() != null) {
      headers.putAll(rendered.headers());
    }

    context.statusPublisher().update(status -> status
        .data("enabled", context.enabled())
        .data("headers", headers.size())
        .data("bodyType", templateConfig.bodyType()));

    if (rendered.bodyType() == MessageBodyType.SIMPLE) {
      return seed.addStep(rendered.body(), headers);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", headers.get("message-id"));
    payload.put("path", rendered.path());
    payload.put("method", rendered.method() == null ? null : rendered.method().toUpperCase(Locale.ROOT));
    payload.put("headers", rendered.headers());
    payload.put("body", rendered.body());
    payload.put("createdAt", Instant.now().toString());

    WorkItem httpItem = WorkItem.json(payload)
        .header("content-type", "application/json")
        .build();
    return seed.addStep(httpItem.asString(), httpItem.headers());
  }
}
