package io.pockethive.examples.starter.generator;

import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Demonstrates a generator worker that emits templated messages.
 */
@Component("sampleGeneratorWorker")
@PocketHiveWorker(
    role = "generator",
    outQueue = "generator",
    config = SampleGeneratorConfig.class
)
class SampleGeneratorWorker implements PocketHiveWorkerFunction {

  private static final SampleGeneratorConfig FALLBACK_CONFIG =
      new SampleGeneratorConfig(1.0, "Hello from the generator");

  @Override
  @Override
  public WorkResult onMessage(WorkMessage seed, WorkerContext context) {
    SampleGeneratorConfig config = context.config(SampleGeneratorConfig.class)
        .orElse(FALLBACK_CONFIG);

    String outQueue = Optional.ofNullable(context.info().outQueue())
        .orElseThrow(() -> new IllegalStateException("Outbound queue not configured"));

    context.statusPublisher()
        .workOut(outQueue)
        .update(status -> status
            .data("enabled", context.enabled())
            .data("ratePerSecond", config.ratePerSecond())
            .data("message", config.message()));

    if (!context.enabled()) {
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
