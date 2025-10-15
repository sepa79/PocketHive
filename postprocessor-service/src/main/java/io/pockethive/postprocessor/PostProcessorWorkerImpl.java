package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
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
import java.util.ArrayList;
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
  private final AtomicReference<PostProcessorMetrics> metricsRef = new AtomicReference<>();

  @Autowired
  PostProcessorWorkerImpl(PostProcessorDefaults defaults) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
  }

  /**
   * Observes the last leg of a message's journey. The method pulls
   * {@link PostProcessorWorkerConfig} via
   * {@code pockethive.control-plane.worker.postprocessor.enabled} (used mainly to toggle
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
    appendTerminalHop(context, observability);
    LatencyMeasurements measurements = measureLatency(observability);
    boolean error = isError(in.headers().get(ERROR_HEADER));

    PostProcessorMetrics metrics = metrics(context);
    metrics.record(measurements, error);

    context.statusPublisher()
        .workIn(Topology.FINAL_QUEUE)
        .update(status -> status
            .data("enabled", config.enabled())
            .data("errors", metrics.errorsCount())
            .data("hopLatencyMs", measurements.latestHopMs())
            .data("totalLatencyMs", measurements.totalMs())
            .data("hopCount", measurements.hopCount()));

    return WorkResult.none();
  }

  private void appendTerminalHop(WorkerContext context, ObservabilityContext observability) {
    List<Hop> hops = observability.getHops();
    if (hops == null) {
      hops = new ArrayList<>();
      observability.setHops(hops);
    } else if (!(hops instanceof ArrayList<?>)) {
      hops = new ArrayList<>(hops);
      observability.setHops(hops);
    }
    Instant terminalTimestamp = resolveTerminalTimestamp(hops);
    Hop last = hops.isEmpty() ? null : hops.get(hops.size() - 1);
    if (last != null && matchesWorker(last, context)) {
      if (last.getReceivedAt() == null) {
        last.setReceivedAt(terminalTimestamp);
      }
      if (last.getProcessedAt() == null) {
        last.setProcessedAt(terminalTimestamp);
      }
      return;
    }
    hops.add(
        new Hop(
            context.info().role(),
            context.info().instanceId(),
            terminalTimestamp,
            terminalTimestamp));
  }

  private Instant resolveTerminalTimestamp(List<Hop> hops) {
    if (!hops.isEmpty()) {
      Hop last = hops.get(hops.size() - 1);
      if (last.getProcessedAt() != null) {
        return last.getProcessedAt();
      }
      if (last.getReceivedAt() != null) {
        return last.getReceivedAt();
      }
    }
    return Instant.now();
  }

  private boolean matchesWorker(Hop hop, WorkerContext context) {
    return Objects.equals(hop.getService(), context.info().role())
        && Objects.equals(hop.getInstance(), context.info().instanceId());
  }

  private LatencyMeasurements measureLatency(ObservabilityContext context) {
    List<Hop> hops = context.getHops();
    if (hops == null || hops.isEmpty()) {
      return LatencyMeasurements.zero();
    }
    List<Long> hopDurations = new ArrayList<>(hops.size());
    for (Hop hop : hops) {
      hopDurations.add(durationMillis(hop));
    }
    Hop first = hops.get(0);
    Hop last = hops.get(hops.size() - 1);
    long totalMs = durationMillis(
        Objects.requireNonNull(first.getReceivedAt(), "first hop missing receivedAt"),
        Objects.requireNonNull(last.getProcessedAt(), "last hop missing processedAt"));
    return new LatencyMeasurements(List.copyOf(hopDurations), totalMs);
  }

  private long durationMillis(Hop hop) {
    return durationMillis(
        Objects.requireNonNull(hop.getReceivedAt(), () -> describeHop("receivedAt", hop)),
        Objects.requireNonNull(hop.getProcessedAt(), () -> describeHop("processedAt", hop)));
  }

  private long durationMillis(Instant start, Instant end) {
    return Duration.between(start, end).toMillis();
  }

  private String describeHop(String missingField, Hop hop) {
    return "hop "
        + hop.getService()
        + "(" + hop.getInstance() + ") missing "
        + missingField;
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

  private record LatencyMeasurements(List<Long> hopDurations, long totalMs) {
    static LatencyMeasurements zero() {
      return new LatencyMeasurements(List.of(), 0L);
    }

    int hopCount() {
      return hopDurations.size();
    }

    long latestHopMs() {
      if (hopDurations.isEmpty()) {
        return 0L;
      }
      return hopDurations.get(hopDurations.size() - 1);
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
      String role = context.info().role();
      String instance = context.info().instanceId();
      String swarm = context.info().swarmId();
      this.hopLatency = DistributionSummary.builder("ph_hop_latency_ms")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
      this.totalLatency = DistributionSummary.builder("ph_total_latency_ms")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
      this.hopCount = DistributionSummary.builder("ph_hops")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
      this.errorCounter = Counter.builder("ph_errors_total")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
    }

    void record(LatencyMeasurements measurements, boolean error) {
      for (Long hopMs : measurements.hopDurations()) {
        hopLatency.record(hopMs);
      }
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
