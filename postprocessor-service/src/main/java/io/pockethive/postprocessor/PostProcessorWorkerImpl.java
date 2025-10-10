package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.TopologyDefaults;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The post-processor is the last hop in the PocketHive happy path. It listens to
 * {@link Topology#FINAL_QUEUE} and focuses on observability: measuring end-to-end latency,
 * counting hops, and flagging any {@code x-ph-error} markers set by upstream workers. When teams
 * deploy PocketHive to staging or production they often scale the post-processor alongside their
 * observability stack because its metrics feed Grafana dashboards such as "Pipeline Latency".
 *
 * <p>Each instance caches a {@link PostProcessorMetrics} bundle tied to the
 * {@link io.pockethive.worker.sdk.api.WorkerInfo} (service, instance, swarm). Those Micrometer
 * instruments produce metrics:</p>
 * <ul>
 *   <li>{@code postprocessor_hop_latency_ms}</li>
 *   <li>{@code postprocessor_total_latency_ms}</li>
 *   <li>{@code postprocessor_hops}</li>
 *   <li>{@code postprocessor_errors_total}</li>
 * </ul>
 *
 * <p>If you extend the worker—for example to emit custom histograms—do so within
 * {@link PostProcessorMetrics#record(LatencyMeasurements, boolean)} so instrumentation stays
 * consistent. The worker keeps publishing status updates so junior developers can see hop counts
 * and running error totals without leaving the PocketHive UI.</p>
 */
@Component("postProcessorWorker")
@PocketHiveWorker(
    role = "postprocessor",
    type = WorkerType.MESSAGE,
    inQueue = TopologyDefaults.FINAL_QUEUE,
    config = PostProcessorWorkerConfig.class
)
class PostProcessorWorkerImpl implements MessageWorker {

  private static final String ERROR_HEADER = "x-ph-error";

  private final PostProcessorDefaults defaults;
  private final PostProcessorQueuesProperties queues;
  private final AtomicReference<PostProcessorMetrics> metricsRef = new AtomicReference<>();

  @Autowired
  PostProcessorWorkerImpl(PostProcessorDefaults defaults, PostProcessorQueuesProperties queues) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.queues = Objects.requireNonNull(queues, "queues");
  }

  /**
   * Observes the last leg of a message's journey. The method pulls
   * {@link PostProcessorWorkerConfig} via {@code ph.postprocessor.enabled} (used mainly to toggle
   * status displays) and inspects {@link ObservabilityContext#getHops()} to calculate per-hop and
   * total latency. Expect each {@link Hop} to include {@code receivedAt} and {@code processedAt}
   * timestamps—missing timestamps fall back to zero latency so the dashboards remain stable.
   *
   * <p>If upstream workers set an {@code x-ph-error} header—commonly a boolean string like
   * {@code "true"}—the post-processor increments {@code postprocessor_errors_total}. This is the
   * metric Grafana panels use to paint red alerts.</p>
   *
   * <p>After updating metrics the worker publishes a status heartbeat containing the effective
   * enabled flag, hop counts, and latency snapshots. Junior engineers debugging the pipeline should
   * first look here; the values reflect what the Micrometer counters recorded during the same
   * invocation.</p>
   *
   * <p>The worker finishes by returning {@link WorkResult#none()} because the pipeline ends here.
   * Teams who need to forward the results to another queue can swap in
   * {@link WorkResult#message(WorkMessage)} and reuse message-building helpers from the generator
   * or processor services.</p>
   *
   * @param in the message consumed from {@link Topology#FINAL_QUEUE}.
   * @param context provides configuration, Micrometer {@link MeterRegistry}, and the current
   *     {@link ObservabilityContext}.
   * @return {@link WorkResult#none()} because the post-processor does not produce a follow-up
   *     message.
   */
  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    PostProcessorWorkerConfig config = context.config(PostProcessorWorkerConfig.class)
        .orElseGet(defaults::asConfig);

    ObservabilityContext observability =
        Objects.requireNonNull(context.observabilityContext(), "observabilityContext");
    LatencyMeasurements measurements = measureLatency(observability);
    boolean error = isError(in.headers().get(ERROR_HEADER));

    PostProcessorMetrics metrics = metrics(context);
    metrics.record(measurements, error);

    context.statusPublisher()
        .workIn(queues.getFinalQueue())
        .update(status -> status
            .data("enabled", config.enabled())
            .data("errors", metrics.errorsCount())
            .data("hopLatencyMs", measurements.hopMs())
            .data("totalLatencyMs", measurements.totalMs())
            .data("hopCount", measurements.hopCount()));

    return WorkResult.none();
  }

  private LatencyMeasurements measureLatency(ObservabilityContext context) {
    List<Hop> hops = context.getHops();
    if (hops == null || hops.isEmpty()) {
      return LatencyMeasurements.zero();
    }
    int hopCount = hops.size();
    Hop last = hops.get(hopCount - 1);
    Hop first = hops.get(0);
    long hopMs = durationMillis(last.getReceivedAt(), last.getProcessedAt());
    long totalMs = durationMillis(first.getReceivedAt(), last.getProcessedAt());
    return new LatencyMeasurements(hopMs, totalMs, hopCount);
  }

  private long durationMillis(Instant start, Instant end) {
    if (start == null || end == null) {
      return 0L;
    }
    return Duration.between(start, end).toMillis();
  }

  private boolean isError(Object headerValue) {
    if (headerValue instanceof Boolean bool) {
      return Boolean.TRUE.equals(bool);
    }
    if (headerValue instanceof String text) {
      return Boolean.parseBoolean(text);
    }
    return false;
  }

  private PostProcessorMetrics metrics(WorkerContext context) {
    PostProcessorMetrics current = metricsRef.get();
    if (current != null) {
      return current;
    }
    synchronized (metricsRef) {
      current = metricsRef.get();
      if (current == null) {
        current = new PostProcessorMetrics(context.meterRegistry(), context);
        metricsRef.set(current);
      }
      return current;
    }
  }

  private record LatencyMeasurements(long hopMs, long totalMs, int hopCount) {
    static LatencyMeasurements zero() {
      return new LatencyMeasurements(0L, 0L, 0);
    }
  }

  private static final class PostProcessorMetrics {
    private final DistributionSummary hopLatency;
    private final DistributionSummary totalLatency;
    private final DistributionSummary hopCount;
    private final Counter errorCounter;

    PostProcessorMetrics(MeterRegistry registry, WorkerContext context) {
      Objects.requireNonNull(registry, "registry");
      Objects.requireNonNull(context, "context");
      String service = context.info().role();
      String instance = context.info().instanceId();
      String swarm = context.info().swarmId();
      this.hopLatency = DistributionSummary.builder("postprocessor_hop_latency_ms")
          .tag("service", service)
          .tag("instance", instance)
          .tag("swarm", swarm)
          .register(registry);
      this.totalLatency = DistributionSummary.builder("postprocessor_total_latency_ms")
          .tag("service", service)
          .tag("instance", instance)
          .tag("swarm", swarm)
          .register(registry);
      this.hopCount = DistributionSummary.builder("postprocessor_hops")
          .tag("service", service)
          .tag("instance", instance)
          .tag("swarm", swarm)
          .register(registry);
      this.errorCounter = Counter.builder("postprocessor_errors_total")
          .tag("service", service)
          .tag("instance", instance)
          .tag("swarm", swarm)
          .register(registry);
    }

    void record(LatencyMeasurements measurements, boolean error) {
      hopLatency.record(measurements.hopMs());
      totalLatency.record(measurements.totalMs());
      hopCount.record(measurements.hopCount());
      if (error) {
        errorCounter.increment();
      }
    }

    double errorsCount() {
      return errorCounter.count();
    }
  }
}
