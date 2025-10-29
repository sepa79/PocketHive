package io.pockethive.generator;

import io.pockethive.worker.sdk.api.GeneratorWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PocketHive's generator service is the "source" side of the default swarm pipeline. It runs
 * alongside the orchestrator in most deployments and continuously emits HTTP request work items
 * into the generator queue configured via {@code pockethive.control-plane.queues.generator}.
 * Junior engineers can think of it as the friendly robot that drafts the first message so
 * downstream moderators, processors, and post-processors have
 * something to act on. The queue name and worker type are set by {@link PocketHiveWorker}
 * defaults, but you can override them in the annotation if you spin up a custom swarm.
 *
 * <p>When PocketHive boots the generator worker it resolves configuration in three stages:</p>
 * <ol>
 *   <li>Read {@code pockethive.control-plane.worker.generator.*} defaults from
 *       {@link GeneratorDefaults} (for example a
 *       {@code pockethive.control-plane.worker.generator.rate-per-sec} property in
 *       {@code application.yml}).</li>
 *   <li>Merge in any runtime overrides supplied by the control plane. Those show up in
 *       {@link WorkerContext#config(Class)}.</li>
 *   <li>Publish a status heartbeat so operators see which path, method, and headers this worker is
 *       currently using.</li>
 * </ol>
 *
 * <p>Because the generator is the entry point, it does not emit metrics on its own; instead it
 * updates the worker status stream. Watch the <em>Generator status</em> card in Grafana to confirm
 * it is emitting work. You can also inspect the generated {@code WorkMessage}—it includes headers
 * like {@code content-type}, {@code message-id}, and {@code x-ph-service} to help with
 * observability.</p>
 */
@Component("generatorWorker")
@PocketHiveWorker(
    role = "generator",
    type = WorkerType.GENERATOR,
    outQueue = "generator",
    config = GeneratorWorkerConfig.class
)
class GeneratorWorkerImpl implements GeneratorWorker {

  private final GeneratorDefaults defaults;

  @Autowired
  GeneratorWorkerImpl(GeneratorDefaults defaults) {
    this.defaults = defaults;
  }

  /**
   * Builds a {@link WorkMessage} ready for the moderator queue. The method pulls generator
   * settings from the {@link WorkerContext} (control-plane overrides) or falls back to
   * {@link GeneratorDefaults}. Expect a nested {@code message} object with keys like
   * {@code path}, {@code method}, {@code body}, and {@code headers}—they mirror the fields in
   * {@link GeneratorWorkerConfig}. A sample override payload looks like:
   *
   * <pre>{@code
   * {
   *   "enabled": true,
   *   "ratePerSec": 1.5,
   *   "singleRequest": false,
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
   * <p>The returned {@link WorkResult} wraps a message with default headers:</p>
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
   * @return a {@link WorkResult} wrapping the JSON message that should be placed on
   *     the configured generator queue.
   */
  @Override
  public WorkResult generate(WorkerContext context) {
    GeneratorWorkerConfig config = context.config(GeneratorWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    String outboundQueue = context.info().outQueue();
    context.statusPublisher()
        .workOut(outboundQueue)
        .update(status -> status
            .data("path", config.message().path())
            .data("method", config.message().method())
            .data("ratePerSec", config.ratePerSec())
            .data("enabled", config.enabled())
            .data("singleRequest", config.singleRequest()));
    return WorkResult.message(buildMessage(config, context));
  }

  private WorkMessage buildMessage(GeneratorWorkerConfig config, WorkerContext context) {
    String messageId = UUID.randomUUID().toString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", messageId);
    GeneratorWorkerConfig.Message message = config.message();
    payload.put("path", message.path());
    payload.put("method", message.method());
    payload.put("headers", message.headers());
    payload.put("body", message.body());
    payload.put("createdAt", Instant.now().toString());

    return WorkMessage.json(payload)
        .header("content-type", MessageProperties.CONTENT_TYPE_JSON)
        .header("message-id", messageId)
        .header("x-ph-service", context.info().role())
        .build();
  }
}
