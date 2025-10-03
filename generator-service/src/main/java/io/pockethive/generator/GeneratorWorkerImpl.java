package io.pockethive.generator;

import io.pockethive.Topology;
import io.pockethive.TopologyDefaults;
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
import org.springframework.stereotype.Component;

@Component("generatorWorker")
@PocketHiveWorker(
    role = "generator",
    type = WorkerType.GENERATOR,
  outQueue = TopologyDefaults.GEN_QUEUE,
    config = GeneratorWorkerConfig.class
)
class GeneratorWorkerImpl implements GeneratorWorker {

  private final GeneratorDefaults defaults;

  GeneratorWorkerImpl(GeneratorDefaults defaults) {
    this.defaults = defaults;
  }

  @Override
  public WorkResult generate(WorkerContext context) {
    GeneratorWorkerConfig config = context.config(GeneratorWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    context.statusPublisher()
        .workOut(Topology.GEN_QUEUE)
        .update(status -> status
            .data("path", config.path())
            .data("method", config.method())
            .data("ratePerSec", config.ratePerSec())
            .data("enabled", config.enabled())
            .data("singleRequest", config.singleRequest()));
    return WorkResult.message(buildMessage(config, context));
  }

  private WorkMessage buildMessage(GeneratorWorkerConfig config, WorkerContext context) {
    String messageId = UUID.randomUUID().toString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", messageId);
    payload.put("path", config.path());
    payload.put("method", config.method());
    payload.put("headers", config.headers());
    payload.put("body", config.body());
    payload.put("createdAt", Instant.now().toString());

  return WorkMessage.json(payload)
    .header("content-type", MessageProperties.CONTENT_TYPE_JSON)
    .header("message-id", messageId)
    .header("x-ph-service", context.info().role())
    .build();
  }
}
