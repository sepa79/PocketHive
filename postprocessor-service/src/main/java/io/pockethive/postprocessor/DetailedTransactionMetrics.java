package io.pockethive.postprocessor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.pockethive.observability.Hop;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

final class DetailedTransactionMetrics {

  private static final int DEFAULT_HISTORY_SIZE = 50;

  private final MultiGauge hopDurationGauge;
  private final MultiGauge totalLatencyGauge;
  private final MultiGauge processorDurationGauge;
  private final MultiGauge processorSuccessGauge;
  private final MultiGauge processorStatusGauge;
  private final String role;
  private final String instance;
  private final String swarm;
  private final Deque<Transaction> history = new ArrayDeque<>();
  private final AtomicLong sequence = new AtomicLong();
  private final int historySize;

  DetailedTransactionMetrics(MeterRegistry registry, WorkerContext context) {
    this(registry, context, DEFAULT_HISTORY_SIZE);
  }

  DetailedTransactionMetrics(MeterRegistry registry, WorkerContext context, int historySize) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(context.info(), "context.info");
    this.role = context.info().role();
    this.instance = context.info().instanceId();
    this.swarm = context.info().swarmId();
    this.historySize = Math.max(1, historySize);
    this.hopDurationGauge = MultiGauge.builder("ph_transaction_hop_duration_ms").register(registry);
    this.totalLatencyGauge = MultiGauge.builder("ph_transaction_total_latency_ms").register(registry);
    this.processorDurationGauge =
        MultiGauge.builder("ph_transaction_processor_duration_ms").register(registry);
    this.processorSuccessGauge = MultiGauge.builder("ph_transaction_processor_success")
        .register(registry);
    this.processorStatusGauge = MultiGauge.builder("ph_transaction_processor_status")
        .register(registry);
  }

  void record(
      List<Long> hopDurations,
      long totalLatencyMs,
      List<Hop> hops,
      PostProcessorWorkerImpl.ProcessorCallStats processorStats) {
    List<HopSample> hopSamples = snapshotHops(hops, hopDurations);
    ProcessorSnapshot processorSnapshot = snapshotProcessor(processorStats);
    Transaction transaction =
        new Transaction(sequence.incrementAndGet(), totalLatencyMs, hopSamples, processorSnapshot);
    List<Transaction> snapshot;
    synchronized (history) {
      history.addLast(transaction);
      while (history.size() > historySize) {
        history.removeFirst();
      }
      snapshot = List.copyOf(history);
    }
    flush(snapshot);
  }

  private void flush(List<Transaction> transactions) {
    hopDurationGauge.register(hopDurationRows(transactions), true);
    totalLatencyGauge.register(totalLatencyRows(transactions), true);
    processorDurationGauge.register(processorDurationRows(transactions), true);
    processorSuccessGauge.register(processorSuccessRows(transactions), true);
    processorStatusGauge.register(processorStatusRows(transactions), true);
  }

  private List<MultiGauge.Row<?>> hopDurationRows(List<Transaction> transactions) {
    if (transactions.isEmpty()) {
      return List.of();
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>();
    for (Transaction transaction : transactions) {
      for (HopSample hop : transaction.hops()) {
        Tags tags =
            baseTags(transaction.sequence())
                .and("hop_index", Integer.toString(hop.index()))
                .and("hop_service", hop.service())
                .and("hop_instance", hop.instance());
        rows.add(MultiGauge.Row.of(tags, hop.durationMs()));
      }
    }
    return rows;
  }

  private List<MultiGauge.Row<?>> totalLatencyRows(List<Transaction> transactions) {
    if (transactions.isEmpty()) {
      return List.of();
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>(transactions.size());
    for (Transaction transaction : transactions) {
      rows.add(MultiGauge.Row.of(baseTags(transaction.sequence()), transaction.totalLatencyMs()));
    }
    return rows;
  }

  private List<MultiGauge.Row<?>> processorDurationRows(List<Transaction> transactions) {
    if (transactions.isEmpty()) {
      return List.of();
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>(transactions.size());
    for (Transaction transaction : transactions) {
      ProcessorSnapshot snapshot = transaction.processor();
      if (snapshot.durationMs() != null) {
        rows.add(MultiGauge.Row.of(baseTags(transaction.sequence()), snapshot.durationMs()));
      }
    }
    return rows;
  }

  private List<MultiGauge.Row<?>> processorSuccessRows(List<Transaction> transactions) {
    if (transactions.isEmpty()) {
      return List.of();
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>(transactions.size());
    for (Transaction transaction : transactions) {
      ProcessorSnapshot snapshot = transaction.processor();
      if (snapshot.success() != null) {
        double value = snapshot.success() ? 1.0d : 0.0d;
        rows.add(MultiGauge.Row.of(baseTags(transaction.sequence()), value));
      }
    }
    return rows;
  }

  private List<MultiGauge.Row<?>> processorStatusRows(List<Transaction> transactions) {
    if (transactions.isEmpty()) {
      return List.of();
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>(transactions.size());
    for (Transaction transaction : transactions) {
      ProcessorSnapshot snapshot = transaction.processor();
      if (snapshot.statusCode() != null) {
        rows.add(MultiGauge.Row.of(baseTags(transaction.sequence()), snapshot.statusCode()));
      }
    }
    return rows;
  }

  private Tags baseTags(long sequence) {
    return Tags.of(
        "ph_role",
        role,
        "ph_instance",
        instance,
        "ph_swarm",
        swarm,
        "transaction_seq",
        Long.toString(sequence));
  }

  private List<HopSample> snapshotHops(List<Hop> hops, List<Long> hopDurations) {
    if ((hops == null || hops.isEmpty()) && (hopDurations == null || hopDurations.isEmpty())) {
      return List.of();
    }
    List<HopSample> samples = new ArrayList<>();
    int durationCount = hopDurations == null ? 0 : hopDurations.size();
    for (int index = 0; index < durationCount; index++) {
      Hop hop = hops != null && index < hops.size() ? hops.get(index) : null;
      samples.add(new HopSample(index, describeService(hop), describeInstance(hop), hopDurations.get(index)));
    }
    return List.copyOf(samples);
  }

  private ProcessorSnapshot snapshotProcessor(PostProcessorWorkerImpl.ProcessorCallStats stats) {
    if (stats == null || !stats.hasValues()) {
      return ProcessorSnapshot.EMPTY;
    }
    return new ProcessorSnapshot(stats.durationMs(), stats.success(), stats.statusCode());
  }

  private String describeService(Hop hop) {
    if (hop == null || hop.getService() == null) {
      return "unknown";
    }
    return hop.getService();
  }

  private String describeInstance(Hop hop) {
    if (hop == null || hop.getInstance() == null) {
      return "unknown";
    }
    return hop.getInstance();
  }

  private record Transaction(long sequence, long totalLatencyMs, List<HopSample> hops, ProcessorSnapshot processor) {}

  private record HopSample(int index, String service, String instance, long durationMs) {}

  private record ProcessorSnapshot(Long durationMs, Boolean success, Integer statusCode) {
    private static final ProcessorSnapshot EMPTY = new ProcessorSnapshot(null, null, null);
  }
}
