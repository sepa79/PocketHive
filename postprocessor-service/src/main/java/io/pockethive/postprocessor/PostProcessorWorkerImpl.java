package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The post-processor is the last hop in the PocketHive happy path. It listens to the queue
 * configured via {@code pockethive.inputs.rabbit.queue} (the {@code final-out} suffix in the default swarm)
 * and focuses on observability:
 * measuring end-to-end latency,
 * counting hops, and flagging any {@code x-ph-error} markers set by upstream workers. When teams
 * deploy PocketHive to staging or production they often scale the post-processor alongside their
 * observability stack because its metrics feed Grafana dashboards such as "Pipeline Latency".
 *
 * <p>Each instance caches a {@link PostProcessorMetrics} bundle tied to the
 * {@link io.pockethive.worker.sdk.api.WorkerInfo} (service, instance, swarm). Those Micrometer
 * instruments produce metrics:</p>
 * <ul>
 *   <li>{@code ph_hop_latency_ms}</li>
 *   <li>{@code ph_total_latency_ms}</li>
 *   <li>{@code ph_hops}</li>
 *   <li>{@code ph_errors_total}</li>
 * </ul>
 *
 * <p>If you extend the worker—for example to emit custom histograms—do so within
 * {@link PostProcessorMetrics#record(LatencyMeasurements, boolean, ProcessorCallStats)} so instrumentation stays
 * consistent. The worker keeps publishing status updates so junior developers can see hop counts
 * and running error totals without leaving the PocketHive UI.</p>
 */
@Component("postProcessorWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = PostProcessorWorkerConfig.class
)
class PostProcessorWorkerImpl implements PocketHiveWorkerFunction {

  private static final String ERROR_HEADER = "x-ph-error";
  private static final String PROCESSOR_DURATION_HEADER = "x-ph-processor-duration-ms";
  private static final String PROCESSOR_SUCCESS_HEADER = "x-ph-processor-success";
  private static final String PROCESSOR_STATUS_HEADER = "x-ph-processor-status";

  private final PostProcessorWorkerProperties properties;
  private final PostProcessorTxOutcomeWriter txOutcomeWriter;
  private final Clock clock;
  private final AtomicReference<PostProcessorMetrics> metricsRef = new AtomicReference<>();
  private final LongAdder txOutcomeInserted = new LongAdder();
  private final LongAdder txOutcomeDropped = new LongAdder();
  private final LongAdder txOutcomeFailed = new LongAdder();
  private final LongAdder txOutcomeBufferFull = new LongAdder();
  private final AtomicReference<String> txOutcomeLastError = new AtomicReference<>("");

  @Autowired
  PostProcessorWorkerImpl(
      PostProcessorWorkerProperties properties,
      PostProcessorTxOutcomeWriter txOutcomeWriter
  ) {
    this(properties, txOutcomeWriter, Clock.systemUTC());
  }

  PostProcessorWorkerImpl(
      PostProcessorWorkerProperties properties,
      PostProcessorTxOutcomeWriter txOutcomeWriter,
      Clock clock
  ) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.txOutcomeWriter = Objects.requireNonNull(txOutcomeWriter, "txOutcomeWriter");
    this.clock = Objects.requireNonNull(clock, "clock");
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
 * {@code "true"}—the post-processor increments {@code ph_errors_total}. This is the
   * metric Grafana panels use to paint red alerts.</p>
   *
   * <p>After updating metrics the worker publishes a status heartbeat containing the effective
   * enabled flag, hop counts, and latency snapshots. Junior engineers debugging the pipeline should
   * first look here; the values reflect what the Micrometer counters recorded during the same
   * invocation.</p>
   *
   * <p>The worker finishes by returning {@code null} because the pipeline ends here.
   * Teams who need to forward the results to another queue can return a new
   * {@link WorkItem} and reuse message-building helpers from the generator
   * or processor services.</p>
   *
   * @param in the item consumed from the configured final queue.
   * @param context provides configuration, Micrometer {@link MeterRegistry}, and the current
   *     {@link ObservabilityContext}.
   * @return {@code null} in terminal mode, or the original item when {@code forwardToOutput=true}.
   */
  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    PostProcessorWorkerConfig config = context.configOrDefault(PostProcessorWorkerConfig.class, properties::defaultConfig);

    ObservabilityContext observability =
        Objects.requireNonNull(context.observabilityContext(), "observabilityContext");
    appendTerminalHop(context, observability);
    LatencyMeasurements measurements = measureLatency(observability);
    ProcessorCallStats processorStats = extractProcessorStats(in.headers());
    boolean error = isError(in.headers().get(ERROR_HEADER));

    PostProcessorMetrics metrics = metrics(context);
    metrics.record(measurements, error, processorStats);
    // When publishAllMetrics is enabled we still record aggregated metrics above, but
    // skip per-item Micrometer publishing to avoid flooding Prometheus. Use the ClickHouse
    // tx-outcome sink for per-transaction breakdowns.
    RuntimeException txOutcomeFailure = null;
    String txOutcomeLastCallId = "";
    if (config.writeTxOutcomeToClickHouse()) {
      TxOutcomeEvent event = TxOutcomeProjector
          .project(in, context, clock.instant(), config.dropTxOutcomeWithoutCallId())
          .orElse(null);
      if (event == null) {
        txOutcomeDropped.increment();
      } else {
        txOutcomeLastCallId = event.callId();
        try {
          txOutcomeWriter.write(event);
          txOutcomeInserted.increment();
          txOutcomeLastError.set("");
        } catch (Exception ex) {
          txOutcomeFailed.increment();
          if (ex instanceof ClickHouseTxOutcomeBufferFullException) {
            txOutcomeBufferFull.increment();
          }
          String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
          txOutcomeLastError.set(message);
          txOutcomeFailure =
              new IllegalStateException("Postprocessor ClickHouse sink failed for callId=" + event.callId(), ex);
        }
      }
    }

    String lastCallIdForStatus = txOutcomeLastCallId;
    StatusPublisher publisher = context.statusPublisher();
    publisher.update(status -> {
      status
          .data("enabled", context.enabled())
          .data("publishAllMetrics", config.publishAllMetrics())
          .data("errors", metrics.errorsCount())
          .data("hopLatencyMs", measurements.latestHopMs())
          .data("totalLatencyMs", measurements.totalMs())
          .data("hopCount", measurements.hopCount())
          .data("processorTransactions", metrics.processorTransactions())
          .data("processorSuccessRatio", metrics.processorSuccessRatio())
          .data("processorAvgLatencyMs", metrics.processorAverageLatencyMs())
          .data("txOutcomeSinkEnabled", config.writeTxOutcomeToClickHouse())
          .data("txOutcomeInserted", txOutcomeInserted.sum())
          .data("txOutcomeDropped", txOutcomeDropped.sum())
          .data("txOutcomeFailed", txOutcomeFailed.sum())
          .data("txOutcomeBufferFull", txOutcomeBufferFull.sum())
          .data("txOutcomeLastCallId", lastCallIdForStatus)
          .data("txOutcomeLastError", txOutcomeLastError.get());
    });

    if (txOutcomeFailure != null) {
      throw txOutcomeFailure;
    }
    if (config.forwardToOutput()) {
      return in;
    }
    return null;
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

  private List<Map<String, Object>> describeHops(List<Hop> hops) {
    if (hops == null || hops.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> description = new ArrayList<>(hops.size());
    for (Hop hop : hops) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("service", hop.getService());
      values.put("instance", hop.getInstance());
      values.put("receivedAt", hop.getReceivedAt());
      values.put("processedAt", hop.getProcessedAt());
      description.add(Map.copyOf(values));
    }
    return List.copyOf(description);
  }

  private Map<String, Object> describeProcessorCall(ProcessorCallStats stats) {
    if (stats == null || !stats.hasValues()) {
      return Map.of();
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if (stats.durationMs() != null) {
      values.put("durationMs", stats.durationMs());
    }
    if (stats.success() != null) {
      values.put("success", stats.success());
    }
    if (stats.statusCode() != null) {
      values.put("statusCode", stats.statusCode());
    }
    return Map.copyOf(values);
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

  private ProcessorCallStats extractProcessorStats(Map<String, Object> headers) {
    if (headers == null || headers.isEmpty()) {
      return ProcessorCallStats.empty();
    }
    Long duration = parseLong(headers.get(PROCESSOR_DURATION_HEADER));
    Boolean success = parseBoolean(headers.get(PROCESSOR_SUCCESS_HEADER));
    Integer status = parseInteger(headers.get(PROCESSOR_STATUS_HEADER));
    if (duration == null && success == null && status == null) {
      return ProcessorCallStats.empty();
    }
    return new ProcessorCallStats(duration, success, status);
  }

  private Long parseLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private Boolean parseBoolean(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String text) {
      return Boolean.parseBoolean(text);
    }
    return null;
  }

  private Integer parseInteger(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
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
    private final DistributionSummary processorLatency;
    private final Counter processorCalls;
    private final Counter processorSuccessCalls;
    private final LongAdder processorTransactionCount = new LongAdder();
    private final LongAdder processorSuccesses = new LongAdder();
    private final LongAdder processorLatencySamples = new LongAdder();
    private final DoubleAccumulator processorLatencyTotal = new DoubleAccumulator(Double::sum, 0.0);

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
      this.processorLatency = DistributionSummary.builder("ph_processor_latency_ms")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .publishPercentileHistogram(true)
          .serviceLevelObjectives(
              Duration.ofMillis(1).toMillis(),
              Duration.ofMillis(5).toMillis(),
              Duration.ofMillis(10).toMillis(),
              Duration.ofMillis(50).toMillis(),
              Duration.ofMillis(100).toMillis(),
              Duration.ofMillis(250).toMillis(),
              Duration.ofMillis(500).toMillis(),
              Duration.ofSeconds(1).toMillis(),
              Duration.ofSeconds(5).toMillis(),
              Duration.ofSeconds(10).toMillis())
          .minimumExpectedValue((double) Duration.ofMillis(1).toMillis())
          .maximumExpectedValue((double) Duration.ofSeconds(60).toMillis())
          .register(registry);
      this.processorCalls = Counter.builder("ph_processor_calls_total")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
      this.processorSuccessCalls = Counter.builder("ph_processor_calls_success_total")
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
      Gauge.builder("ph_processor_success_ratio", this, PostProcessorMetrics::processorSuccessRatio)
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
      Gauge.builder("ph_processor_latency_avg_ms", this, PostProcessorMetrics::processorAverageLatencyMs)
          .tag("ph_role", role)
          .tag("ph_instance", instance)
          .tag("ph_swarm", swarm)
          .register(registry);
    }

    void record(LatencyMeasurements measurements, boolean error, ProcessorCallStats processorStats) {
      for (Long hopMs : measurements.hopDurations()) {
        hopLatency.record(hopMs);
      }
      totalLatency.record(measurements.totalMs());
      hopCount.record(measurements.hopCount());
      if (error) {
        errorCounter.increment();
      }
      recordProcessorStats(processorStats);
    }

    double errorsCount() {
      return errorCounter.count();
    }

    long processorTransactions() {
      return processorTransactionCount.sum();
    }

    double processorSuccessRatio() {
      long total = processorTransactionCount.sum();
      if (total == 0L) {
        return 0.0;
      }
      return (double) processorSuccesses.sum() / total;
    }

    double processorAverageLatencyMs() {
      long samples = processorLatencySamples.sum();
      if (samples == 0L) {
        return 0.0;
      }
      return processorLatencyTotal.get() / samples;
    }

    private void recordProcessorStats(ProcessorCallStats processorStats) {
      if (processorStats == null || !processorStats.hasValues()) {
        return;
      }
      boolean counted = false;
      Boolean success = processorStats.success();
      if (success != null) {
        processorTransactionCount.increment();
        processorCalls.increment();
        counted = true;
        if (success) {
          processorSuccessCalls.increment();
          processorSuccesses.increment();
        }
      }
      Long duration = processorStats.durationMs();
      if (duration != null) {
        processorLatency.record(duration);
        processorLatencyTotal.accumulate(duration);
        processorLatencySamples.increment();
        if (!counted) {
          processorTransactionCount.increment();
          processorCalls.increment();
          counted = true;
        }
      }
      if (!counted && processorStats.statusCode() != null) {
        processorTransactionCount.increment();
        processorCalls.increment();
      }
    }
  }

  record ProcessorCallStats(Long durationMs, Boolean success, Integer statusCode) {
    static ProcessorCallStats empty() {
      return new ProcessorCallStats(null, null, null);
    }

    boolean hasValues() {
      return durationMs != null || success != null || statusCode != null;
    }
  }
}
