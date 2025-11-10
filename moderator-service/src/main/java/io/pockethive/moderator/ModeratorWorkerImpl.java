package io.pockethive.moderator;

import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The moderator service is the steady gatekeeper that vets messages coming out of the generator
 * queue before they enter the main processing lane. In the default PocketHive swarm it sits
 * between the generator queue configured via {@code pockethive.inputs.rabbit.queue} and the
 * moderator routing key defined under {@code pockethive.outputs.rabbit.routing-key},
 * enriching messages with the {@code x-ph-service} header so downstream processors can tell who
 * handed them the payload.
 * Deploy it near the generator for low latency; smaller teams often co-locate the two services in
 * the same pod.
 *
 * <p>Moderation is intentionally lightweight today—just pass-through with metadata—but
 * engineers can extend it with validation or routing logic. Flip the
 * {@code pockethive.workers.moderator.enabled}
 * flag in {@code application.yml} (or push a runtime override) to pause moderation during load
 * testing. The worker keeps publishing status updates so you can confirm its enabled/disabled state
 * from Grafana.</p>
 */
@Component("moderatorWorker")
@PocketHiveWorker(
    role = "moderator",
    input = WorkerInputType.RABBIT,
    output = WorkerOutputType.RABBITMQ,
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = ModeratorWorkerConfig.class
)
class ModeratorWorkerImpl implements PocketHiveWorkerFunction {

  private final ModeratorWorkerProperties properties;
  private final OperationModeLimiter modeLimiter = new OperationModeLimiter();

  @Autowired
  ModeratorWorkerImpl(ModeratorWorkerProperties properties) {
    this.properties = properties;
  }

  /**
   * Accepts a message from the generator queue, records the moderator's enabled flag in the worker
   * status stream, and forwards the payload to the moderator queue. Configuration arrives via
   * {@code pockethive.workers.moderator.enabled} (boolean) and can be overridden live
   * through the control plane. Operation modes are selected with
   * {@code pockethive.workers.moderator.config.mode.type} and support
   * {@code pass-through}, {@code rate-per-sec}, and {@code sine}. A
   * simple JSON override looks like {@code {"enabled": true}} or
   * {@code {"mode": {"type": "rate-per-sec", "ratePerSec": 5}}}.
   *
   * <p>The moderator does not alter the payload body; it only ensures the outbound message carries
   * a {@code x-ph-service} header whose value is the worker role (for example {@code "moderator"}).
   * That header is a reliable breadcrumb when you track messages in logs or trace viewers.</p>
   *
   * <p>New behaviors should be added by branching the {@code in.toBuilder()} call—e.g. set a
   * {@code moderation-status} header after evaluating your own rules, or drop the message entirely
   * by returning {@link WorkResult#none()}.</p>
   *
   * @param in the message pulled from the configured generator queue.
   * @param context PocketHive runtime utilities (status publisher, worker info, configuration).
   * @return a {@link WorkResult} instructing the runtime to publish the updated message to
   *     the configured moderator queue.
   */
  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    ModeratorWorkerConfig config = context.config(ModeratorWorkerConfig.class)
        .orElseGet(properties::defaultConfig);
    ModeratorOperationMode mode = config.operationMode();
    context.statusPublisher()
        .update(status -> {
          status.data("enabled", context.enabled());
          status.data("mode", formatMode(mode.type()));
          if (mode instanceof ModeratorOperationMode.RatePerSec ratePerSec) {
            status.data("ratePerSec", ratePerSec.ratePerSec());
          } else if (mode instanceof ModeratorOperationMode.Sine sine) {
            status.data("minRatePerSec", sine.minRatePerSec());
            status.data("maxRatePerSec", sine.maxRatePerSec());
            status.data("periodSeconds", sine.periodSeconds());
            status.data("phaseOffsetSeconds", sine.phaseOffsetSeconds());
          }
        });
    modeLimiter.await(mode);
    WorkMessage out = in.toBuilder()
        .header("x-ph-service", context.info().role())
        .build();
    return WorkResult.message(out);
  }

  private static String formatMode(ModeratorOperationMode.Type type) {
    return switch (type) {
      case PASS_THROUGH -> "pass-through";
      case RATE_PER_SEC -> "rate-per-sec";
      case SINE -> "sine";
    };
  }
}
