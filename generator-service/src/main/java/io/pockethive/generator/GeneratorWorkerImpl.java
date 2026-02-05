package io.pockethive.generator;

import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkStep;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PocketHive's generator service is the "source" side of the default swarm pipeline. It runs
 * alongside the orchestrator in most deployments and continuously emits HTTP request work items
 * into the generator routing key configured via {@code pockethive.outputs.rabbit.routing-key}
 * (typically sourced from {@code POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY}).
 * Junior engineers can think of it as the friendly robot that drafts the first message so
 * downstream moderators, processors, and post-processors have
 * something to act on. The queue name and worker type are set by {@link PocketHiveWorker}
 * defaults, but you can override them in the annotation if you spin up a custom swarm.
 *
 * <p>When PocketHive boots the generator worker it resolves configuration in three stages:</p>
 * <ol>
 *   <li>Read {@code pockethive.worker.*} defaults from
 *       {@link GeneratorWorkerProperties} (for example a
 *       {@code pockethive.worker.config.rate-per-sec} property in
 *       {@code application.yml}).</li>
 *   <li>Merge in any runtime overrides supplied by the control plane. Those show up in
 *       {@link WorkerContext#config(Class)}.</li>
 *   <li>Publish a status heartbeat so operators see which path, method, and headers this worker is
 *       currently using.</li>
 * </ol>
 *
 * <p>Because the generator is the entry point, it does not emit metrics on its own; instead it
 * updates the worker status stream. Watch the <em>Generator status</em> card in Grafana to confirm
 * it is emitting work. You can also inspect the generated {@code WorkItem}—it includes headers
 * like {@code content-type}, {@code message-id}, and {@code x-ph-service} to help with
 * observability.</p>
 */
@Component("generatorWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = GeneratorWorkerConfig.class
)
class GeneratorWorkerImpl implements PocketHiveWorkerFunction {

  private final GeneratorWorkerProperties properties;
  private final TemplateRenderer templateRenderer;
  private final MessageTemplateRenderer messageTemplateRenderer;

  @Autowired
  GeneratorWorkerImpl(GeneratorWorkerProperties properties, TemplateRenderer templateRenderer) {
    this.properties = properties;
    this.templateRenderer = templateRenderer;
    this.messageTemplateRenderer = new MessageTemplateRenderer(templateRenderer);
  }

  /**
   * Builds a {@link WorkItem} ready for the moderator queue. The method pulls generator
   * settings from the {@link WorkerContext} (control-plane overrides) or falls back to
   * {@link GeneratorWorkerProperties}. Expect a nested {@code message} object with keys like
   * {@code path}, {@code method}, {@code body}, and {@code headers}—they mirror the fields in
   * {@link GeneratorWorkerConfig}. A sample override payload looks like:
   *
   * <pre>{@code
   * {
   *   "message": {
   *     "path": "/api/orders",
   *     "method": "POST",
   *     "body": "{\"event\":\"demo\"}",
   *     "headers": {"x-demo": "true"}
   *   }
   * }
   * </pre>
   *
   * <p>After resolving configuration the worker emits a status update showing which queue it is
   * targeting and the effective HTTP settings. That update feeds the <em>Worker Status</em>
   * dashboards and is the first place to check if you wonder “why is nothing being generated?”.</p>
   *
   * <p>The returned {@link WorkItem} includes default headers:</p>
   * <ul>
   *   <li>{@code content-type} → {@code application/json}</li>
   *   <li>{@code message-id} → a generated UUID (helpful for tracing)</li>
   *   <li>{@code x-ph-service} → the worker role so downstream services can attribute work</li>
   * </ul>
   *
   * <p>Downstream processors can extend this worker by adjusting the payload map in
   * {@link #buildMessage(GeneratorWorkerConfig, WorkerContext)}—for example add a
   * {@code scenarioId} field. Just remember to update any schema documentation when you do.</p>
   *
   * @param context the PocketHive runtime context, including configuration and status/meter
   *     publishers.
   * @return a {@link WorkItem} that should be placed on the configured generator queue.
   */
  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    GeneratorWorkerConfig config = context.configOrDefault(GeneratorWorkerConfig.class, properties::defaultConfig);
    context.statusPublisher()
        .update(status -> status
            .data("path", config.message().path())
            .data("method", config.message().method())
            .data("enabled", context.enabled()));
    WorkItem message = buildMessage(config, context, seed);
    return appendMessageStep(seed, message, context);
  }

  private WorkItem buildMessage(GeneratorWorkerConfig config, WorkerContext context, WorkItem seed) {
    String messageId = UUID.randomUUID().toString();
    WorkItem effectiveSeed = seed;
    if (effectiveSeed.headers().get("vars") == null && config.vars() != null && !config.vars().isEmpty()) {
      effectiveSeed = effectiveSeed.toBuilder().header("vars", config.vars()).build();
    }
    GeneratorWorkerConfig.Message message = config.message();
    MessageTemplate template = MessageTemplate.builder()
        .bodyType(message.bodyType())
        .pathTemplate(message.path())
        .methodTemplate(message.method())
        .bodyTemplate(message.body())
        .headerTemplates(message.headers())
        .build();
    MessageTemplateRenderer.RenderedMessage rendered = messageTemplateRenderer.render(template, effectiveSeed);

    Map<String, Object> baseHeaders = new HashMap<>(effectiveSeed.headers());

    if (rendered.bodyType() == MessageBodyType.SIMPLE) {
      Map<String, Object> headers = new LinkedHashMap<>(baseHeaders);
      headers.putAll(rendered.headers());
      return WorkItem.text(context.info(), rendered.body())
          .headers(headers)
          .messageId(messageId)
          .contentType(MessageProperties.CONTENT_TYPE_JSON)
          .build();
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", messageId);
    payload.put("path", rendered.path());
    payload.put("method", rendered.method() == null ? null : rendered.method().toUpperCase(Locale.ROOT));
    payload.put("headers", rendered.headers());
    payload.put("body", rendered.body());
    payload.put("createdAt", Instant.now().toString());

    return WorkItem.json(context.info(), payload)
        .headers(baseHeaders)
        .messageId(messageId)
        .contentType(MessageProperties.CONTENT_TYPE_JSON)
        .build();
  }

  private WorkItem appendMessageStep(WorkItem seed, WorkItem message, WorkerContext context) {
    WorkStep step = lastStep(message);
    return seed.toBuilder()
        .headers(message.headers())
        .messageId(message.messageId())
        .contentType(message.contentType())
        .step(context.info(), step.payload(), step.payloadEncoding(), step.headers())
        .build();
  }

  private WorkStep lastStep(WorkItem item) {
    WorkStep last = null;
    for (WorkStep step : item.steps()) {
      last = step;
    }
    if (last == null) {
      throw new IllegalStateException("Generator message did not include any steps");
    }
    return last;
  }
}
