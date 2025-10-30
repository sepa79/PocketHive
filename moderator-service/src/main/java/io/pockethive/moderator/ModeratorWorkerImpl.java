package io.pockethive.moderator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.runtime.PatternAckPacer;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(ModeratorWorkerImpl.class);
  private final ModeratorDefaults defaults;
  private final Clock clock;
  private final PatternAckPacer.Sleeper sleeper;
  private final AtomicReference<PacerState> pacerRef = new AtomicReference<>();
  private final AtomicReference<MeterBundle> meterRef = new AtomicReference<>();
  private volatile Instant lastReadyInstant;

  @Autowired
  ModeratorWorkerImpl(ModeratorDefaults defaults) {
    this(defaults, Clock.systemUTC(), PatternAckPacer.defaultSleeper());
  }

  ModeratorWorkerImpl(ModeratorDefaults defaults, Clock clock, PatternAckPacer.Sleeper sleeper) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
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
    MeterBundle metrics = metrics(context);
    String inboundQueue = context.info().inQueue();
    String outboundQueue = context.info().outQueue();
    if (!config.enabled()) {
      metrics.resetDisabled(readQueueDepth(in));
      context.statusPublisher()
          .workIn(inboundQueue)
          .workOut(outboundQueue)
          .update(status -> status
              .data("enabled", false)
              .data("multiplier_now", 0d)
              .data("norm_k", 1d)
              .data("pattern_pos", 0d)
              .data("step_id", "disabled")
              .data("in_queue", inboundQueue)
              .data("out_queue", outboundQueue));
      return WorkResult.none();
    }

    PatternAckPacer pacer = pacer(config);
    PatternAckPacer.AwaitResult pacing;
    try {
      pacing = pacer.awaitReady();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return WorkResult.none();
    }

    updateMetrics(metrics, pacing, in);
    updateStatus(context, config, inboundQueue, outboundQueue, pacing, pacer.patternDurationMillis());

    WorkMessage out = in.toBuilder()
        .header("x-ph-service", context.info().role())
        .build();
    return WorkResult.message(out);
  }

  private PatternAckPacer pacer(ModeratorWorkerConfig config) {
    PacerState current = pacerRef.get();
    if (current != null && current.config().equals(config)) {
      return current.pacer();
    }
    PatternAckPacer pacer = new PatternAckPacer(config, clock, sleeper);
    pacerRef.set(new PacerState(config, pacer));
    logPacerInstallation(config, pacer);
    return pacer;
  }

  private void logPacerInstallation(ModeratorWorkerConfig config, PatternAckPacer pacer) {
    List<StepConfig> steps = config.pattern().steps();
    int stepCount = steps.size();
    String stepIds = steps.isEmpty()
        ? ""
        : steps.stream().map(StepConfig::id).collect(Collectors.joining(","));
    log.info(
        "Moderator pacer refreshed: enabled={}, baseRateRps={}, stepCount={}, stepIds={}, patternDurationMillis={}",
        config.enabled(),
        config.pattern().baseRateRps(),
        stepCount,
        stepIds,
        pacer.patternDurationMillis());
  }

  private MeterBundle metrics(WorkerContext context) {
    MeterBundle bundle = meterRef.get();
    if (bundle != null && bundle.registry == context.meterRegistry()) {
      return bundle;
    }
    MeterBundle created = MeterBundle.create(context.meterRegistry(), context.info());
    meterRef.set(created);
    return created;
  }

  private void updateMetrics(MeterBundle metrics,
                             PatternAckPacer.AwaitResult pacing,
                             WorkMessage in) {
    metrics.targetRps.set(pacing.targetRps());
    metrics.bucketLevel.set(pacing.bucketLevel());
    double delayMs = pacing.totalDelay().toNanos() / 1_000_000d;
    metrics.delayMs.set(delayMs);
    metrics.waitTimer.record(pacing.totalDelay());
    metrics.actualCounter.increment();
    double queueDepth = readQueueDepth(in);
    metrics.queueDepth.set(queueDepth);

    Instant readyAt = pacing.readyAt();
    Instant previous = this.lastReadyInstant;
    if (previous != null) {
      Duration delta = Duration.between(previous, readyAt);
      double rate = delta.isZero() ? pacing.targetRps() : (1_000_000_000d / Math.max(delta.toNanos(), 1L));
      metrics.actualRps.set(rate);
    } else {
      metrics.actualRps.set(pacing.targetRps());
    }
    this.lastReadyInstant = readyAt;
  }

  private void updateStatus(WorkerContext context,
                            ModeratorWorkerConfig config,
                            String inboundQueue,
                            String outboundQueue,
                            PatternAckPacer.AwaitResult pacing,
                            double patternDurationMillis) {
    PatternAckPacer.AwaitResult result = pacing;
    double fraction = result.sample().patternFraction(patternDurationMillis);
    context.statusPublisher()
        .workIn(inboundQueue)
        .workOut(outboundQueue)
        .update(status -> status
            .data("enabled", config.enabled())
            .data("multiplier_now", result.sample().multiplier())
            .data("norm_k", result.sample().normalizationConstant())
            .data("pattern_pos", fraction)
            .data("step_id", Optional.ofNullable(result.sample().stepId()).orElse(""))
            .data("in_queue", inboundQueue)
            .data("out_queue", outboundQueue));
  }

  private double readQueueDepth(WorkMessage in) {
    return headerAsDouble(in.headers(), "x-ph-in-queue-depth")
        .or(() -> headerAsDouble(in.headers(), "x-ph-queue-depth"))
        .orElse(-1d);
  }

  private Optional<Double> headerAsDouble(Map<String, Object> headers, String key) {
    if (!headers.containsKey(key)) {
      return Optional.empty();
    }
    Object value = headers.get(key);
    if (value instanceof Number number) {
      return Optional.of(number.doubleValue());
    }
    if (value instanceof String text) {
      try {
        return Optional.of(Double.parseDouble(text));
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private record PacerState(ModeratorWorkerConfig config, PatternAckPacer pacer) {}

  private static final class MeterBundle {
    private final MeterRegistry registry;
    private final AtomicReference<Double> targetRps;
    private final AtomicReference<Double> actualRps;
    private final AtomicReference<Double> bucketLevel;
    private final AtomicReference<Double> delayMs;
    private final AtomicReference<Double> queueDepth;
    private final Counter actualCounter;
    private final Timer waitTimer;

    private MeterBundle(MeterRegistry registry,
                        AtomicReference<Double> targetRps,
                        AtomicReference<Double> actualRps,
                        AtomicReference<Double> bucketLevel,
                        AtomicReference<Double> delayMs,
                        AtomicReference<Double> queueDepth,
                        Counter actualCounter,
                        Timer waitTimer) {
      this.registry = registry;
      this.targetRps = targetRps;
      this.actualRps = actualRps;
      this.bucketLevel = bucketLevel;
      this.delayMs = delayMs;
      this.queueDepth = queueDepth;
      this.actualCounter = actualCounter;
      this.waitTimer = waitTimer;
    }

    private static MeterBundle create(MeterRegistry registry, WorkerInfo info) {
      AtomicReference<Double> target = new AtomicReference<>(0d);
      AtomicReference<Double> actual = new AtomicReference<>(0d);
      AtomicReference<Double> bucket = new AtomicReference<>(0d);
      AtomicReference<Double> delay = new AtomicReference<>(0d);
      AtomicReference<Double> depth = new AtomicReference<>(-1d);
      Gauge.builder("moderator.target_rps", target, AtomicReference::get)
          .tag("worker", info.role())
          .tag("queue", info.outQueue())
          .register(registry);
      Gauge.builder("moderator.actual_rps_out", actual, AtomicReference::get)
          .tag("worker", info.role())
          .tag("queue", info.outQueue())
          .register(registry);
      Gauge.builder("moderator.bucket_level", bucket, AtomicReference::get)
          .tag("worker", info.role())
          .register(registry);
      Gauge.builder("moderator.delay_ms_last", delay, AtomicReference::get)
          .tag("worker", info.role())
          .register(registry);
      Gauge.builder("moderator.in_queue_depth", depth, AtomicReference::get)
          .tag("worker", info.role())
          .tag("queue", info.inQueue())
          .register(registry);
      Counter counter = Counter.builder("moderator.actual_rps_out.count")
          .tag("worker", info.role())
          .register(registry);
      Timer timer = Timer.builder("moderator.pacing.delay")
          .tag("worker", info.role())
          .register(registry);
      return new MeterBundle(registry, target, actual, bucket, delay, depth, counter, timer);
    }

    private void resetDisabled(double queueDepthValue) {
      targetRps.set(0d);
      actualRps.set(0d);
      bucketLevel.set(0d);
      delayMs.set(0d);
      queueDepth.set(queueDepthValue);
    }
  }
}
