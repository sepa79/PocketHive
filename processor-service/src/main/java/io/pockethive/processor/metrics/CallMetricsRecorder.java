package io.pockethive.processor.metrics;

import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;

public class CallMetricsRecorder {
  private final LongAdder totalCalls = new LongAdder();
  private final LongAdder successfulCalls = new LongAdder();
  private final DoubleAccumulator totalLatencyMs = new DoubleAccumulator(Double::sum, 0.0);

  public void record(CallMetrics metrics) {
    totalCalls.increment();
    totalLatencyMs.accumulate(metrics.durationMs());
    if (metrics.success()) {
      successfulCalls.increment();
    }
  }

  public double averageLatencyMs() {
    long calls = totalCalls.sum();
    return calls == 0L ? 0.0 : totalLatencyMs.get() / calls;
  }

  public double successRatio() {
    long calls = totalCalls.sum();
    return calls == 0L ? 0.0 : (double) successfulCalls.sum() / calls;
  }

  public long totalCalls() {
    return totalCalls.sum();
  }
}
