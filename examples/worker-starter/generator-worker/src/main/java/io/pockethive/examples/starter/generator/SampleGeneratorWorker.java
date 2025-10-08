package io.pockethive.examples.starter.generator;

import io.pockethive.worker.sdk.api.GeneratorWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Demonstrates a generator worker that emits templated messages.
 */
@Component("sampleGeneratorWorker")
@PocketHiveWorker(
    role = "generator",
    type = WorkerType.GENERATOR,
    outQueue = "ph.generator.out",
    config = SampleGeneratorConfig.class
)
class SampleGeneratorWorker implements GeneratorWorker {

  private static final SampleGeneratorConfig FALLBACK_CONFIG =
      new SampleGeneratorConfig(true, 1.0, "Hello from the generator");

  @Override
  public WorkResult generate(WorkerContext context) {
    SampleGeneratorConfig config = context.config(SampleGeneratorConfig.class)
        .orElse(FALLBACK_CONFIG);

    context.statusPublisher()
        .workOut("ph.generator.out")
        .update(status -> status
            .data("enabled", config.enabled())
            .data("ratePerSecond", config.ratePerSecond())
            .data("message", config.message()));

    if (!config.enabled()) {
      return WorkResult.none();
    }

    WorkMessage message = WorkMessage.json(
            Map.of(
                "id", UUID.randomUUID().toString(),
                "message", config.message(),
                "createdAt", Instant.now().toString()))
        .build();

    return WorkResult.message(message);
  }
}
