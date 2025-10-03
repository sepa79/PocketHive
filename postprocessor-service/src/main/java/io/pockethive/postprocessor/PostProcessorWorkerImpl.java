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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

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

  PostProcessorWorkerImpl(PostProcessorDefaults defaults) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
  }

  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    PostProcessorWorkerConfig config = context.config(PostProcessorWorkerConfig.class)
        .orElseGet(defaults::asConfig);

    ObservabilityContext observability = resolveObservabilityContext(in, context);
    LatencyMeasurements measurements = measureLatency(observability);
    boolean error = isError(in.headers().get(ERROR_HEADER));

    PostProcessorMetrics metrics = metrics(context);
    metrics.record(measurements, error);

    context.statusPublisher()
        .workIn(Topology.FINAL_QUEUE)
        .update(status -> status
            .data("enabled", config.enabled())
            .data("errors", metrics.errorsCount())
            .data("hopLatencyMs", measurements.hopMs())
            .data("totalLatencyMs", measurements.totalMs())
            .data("hopCount", measurements.hopCount()));

    return WorkResult.none();
  }

  private ObservabilityContext resolveObservabilityContext(WorkMessage message, WorkerContext context) {
    ObservabilityContext fromContext = context.observabilityContext();
    if (fromContext != null) {
      return fromContext;
    }
    Optional<ObservabilityContext> fromMessage = message.observabilityContext();
    return fromMessage.orElse(null);
  }

  private LatencyMeasurements measureLatency(ObservabilityContext context) {
    if (context == null) {
      return LatencyMeasurements.zero();
    }
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
