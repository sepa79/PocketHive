package io.pockethive.swarmcontroller.guard;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.pockethive.manager.guard.BufferGuardMetrics;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class MicrometerBufferGuardMetrics implements BufferGuardMetrics {

  private final MeterRegistry meterRegistry;
  private final Tags tags;

  private final AtomicLong depthValue = new AtomicLong(0);
  private final AtomicLong targetValue = new AtomicLong(0);
  private final AtomicReference<Double> rateValue = new AtomicReference<>(0.0);
  private final AtomicInteger stateValue = new AtomicInteger(0);

  private Gauge depthGauge;
  private Gauge targetGauge;
  private Gauge rateGauge;
  private Gauge stateGauge;

  MicrometerBufferGuardMetrics(MeterRegistry meterRegistry, Tags tags) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.tags = Objects.requireNonNull(tags, "tags");
    registerGauges();
  }

  private void registerGauges() {
    depthGauge = Gauge.builder("ph_swarm_buffer_guard_depth", depthValue, AtomicLong::doubleValue)
        .description("Moving average depth observed by buffer guard")
        .tags(tags)
        .register(meterRegistry);
    targetGauge = Gauge.builder("ph_swarm_buffer_guard_target", targetValue, AtomicLong::doubleValue)
        .description("Target depth configured for buffer guard")
        .tags(tags)
        .register(meterRegistry);
    rateGauge = Gauge.builder("ph_swarm_buffer_guard_rate_per_sec", rateValue, AtomicReference::get)
        .description("Latest rate override issued by buffer guard")
        .tags(tags)
        .register(meterRegistry);
    stateGauge = Gauge.builder("ph_swarm_buffer_guard_state", stateValue, AtomicInteger::doubleValue)
        .description("Buffer guard state code (0=disabled,1=steady,2=prefill,3=filling,4=draining,5=backpressure)")
        .tags(tags)
        .register(meterRegistry);
  }

  @Override
  public void update(double averageDepth, double targetDepth, double ratePerSec, int modeCode) {
    depthValue.set(Math.round(averageDepth));
    targetValue.set(Math.round(targetDepth));
    rateValue.set(ratePerSec);
    stateValue.set(modeCode);
  }

  @Override
  public void close() {
    if (depthGauge != null) {
      meterRegistry.remove(depthGauge);
      depthGauge = null;
    }
    if (targetGauge != null) {
      meterRegistry.remove(targetGauge);
      targetGauge = null;
    }
    if (rateGauge != null) {
      meterRegistry.remove(rateGauge);
      rateGauge = null;
    }
    if (stateGauge != null) {
      meterRegistry.remove(stateGauge);
      stateGauge = null;
    }
  }
}

