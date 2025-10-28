package io.pockethive.moderator;

import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The moderator service is the steady gatekeeper that vets messages coming out of the generator
 * queue before they enter the main processing lane. In the default PocketHive swarm it sits
 * between the generator and moderator queues configured under
 * {@code pockethive.control-plane.queues}, enriching messages with the
 * {@code x-ph-service} header so downstream processors can tell who handed them the payload.
 * Deploy it near the generator for low latency; smaller teams often co-locate the two services in
 * the same pod.
 *
 * <p>Moderation is intentionally lightweight today—just pass-through with metadata—but junior
 * engineers can extend it with validation or routing logic. Flip the
 * {@code pockethive.control-plane.worker.moderator.enabled}
 * flag in {@code application.yml} (or push a runtime override) to pause moderation during load
 * testing. The rich shaper configuration (time warp, repeat pattern, steps, mutators and seeds)
 * is exposed under the same prefix and validated by {@link ModeratorWorkerConfig}. The worker keeps
 * publishing status updates so you can confirm its enabled/disabled state from Grafana.</p>
 */
@Component("moderatorWorker")
@PocketHiveWorker(
    role = "moderator",
    type = WorkerType.MESSAGE,
    inQueue = "generator",
    outQueue = "moderator",
    config = ModeratorWorkerConfig.class
)
class ModeratorWorkerImpl implements MessageWorker {

  private final ModeratorDefaults defaults;

  @Autowired
  ModeratorWorkerImpl(ModeratorDefaults defaults) {
    this.defaults = defaults;
  }

  /**
   * Accepts a message from the generator queue, records the moderator's enabled flag in the worker
   * status stream, and forwards the payload to the moderator queue. Configuration arrives via
   * {@code pockethive.control-plane.worker.moderator.enabled} (boolean) and can be overridden live
   * through the control plane. A
   * simple JSON override looks like {@code {"enabled": true}}.
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
        .orElseGet(defaults::asConfig);
    String inboundQueue = context.info().inQueue();
    String outboundQueue = context.info().outQueue();
    context.statusPublisher()
        .workIn(inboundQueue)
        .workOut(outboundQueue)
        .update(status -> status
            .data("enabled", config.enabled()));
    WorkMessage out = in.toBuilder()
        .header("x-ph-service", context.info().role())
        .build();
    return WorkResult.message(out);
  }
}
