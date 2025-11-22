package io.pockethive.swarmcontroller.guard;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Encapsulates the buffer guard state machine so it can be reused independently of the
 * SwarmLifecycle implementation.
 */
public final class BufferGuardController implements SwarmGuard {

  @FunctionalInterface
  public interface RateUpdatePublisher {
    void publish(double ratePerSec);
  }

  private static final Logger log = LoggerFactory.getLogger(BufferGuardController.class);

  private final BufferGuardSettings settings;
  private final AmqpAdmin amqp;
  private final MeterRegistry meterRegistry;
  private final Tags metricTags;
  private final RateUpdatePublisher ratePublisher;
  private final ScheduledExecutorService executor;

  private ScheduledFuture<?> future;
  private final AtomicBoolean paused = new AtomicBoolean(false);
  private BufferGuardState guardState;
  private final AtomicLong depthValue = new AtomicLong(0);
  private final AtomicLong targetValue = new AtomicLong(0);
  private final AtomicReference<Double> rateValue = new AtomicReference<>(0.0);
  private final AtomicInteger stateValue = new AtomicInteger(GuardMode.DISABLED.code);
  private Gauge depthGauge;
  private Gauge targetGauge;
  private Gauge rateGauge;
  private Gauge stateGauge;

  public BufferGuardController(BufferGuardSettings settings,
                               AmqpAdmin amqp,
                               MeterRegistry meterRegistry,
                               Tags metricTags,
                               RateUpdatePublisher ratePublisher) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.metricTags = Objects.requireNonNull(metricTags, "metricTags");
    this.ratePublisher = Objects.requireNonNull(ratePublisher, "ratePublisher");
    this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, "buffer-guard-" + settings.queueAlias());
        thread.setDaemon(true);
        return thread;
      }
    });
  }

  @Override
  public synchronized void start() {
    if (future != null) {
      return;
    }
    registerGauges();
    guardState = new BufferGuardState(settings);
    long periodMs = Math.max(settings.samplePeriod().toMillis(), 50L);
    runGuardTick();
    future = executor.scheduleAtFixedRate(this::runGuardTick, periodMs, periodMs, TimeUnit.MILLISECONDS);
    log.info(
      "Buffer guard [{}] started (queue={} targetRole={})",
      settings.queueAlias(),
      settings.queueName(),
      settings.targetRole());
    }

  @Override
  public synchronized void stop() {
    if (future != null) {
      future.cancel(true);
      future = null;
    }
    guardState = null;
    removeGauges();
    stateValue.set(GuardMode.DISABLED.code);
    executor.shutdownNow();
    log.info("Buffer guard [{}] stopped", settings.queueAlias());
  }

  @Override
  public void pause() {
    if (paused.compareAndSet(false, true)){
      log.info("Buffer guard [{}] paused", settings.queueAlias());
    }
  }

  @Override
  public void resume() {
    if (paused.compareAndSet(true, false)){
      log.info("Buffer guard [{}] resumed", settings.queueAlias());
    }
  }

  private void runGuardTick() {
    BufferGuardState state = guardState;
    if (state == null) {
      return;
    }
    try {
      if (paused.get()) {
        updateMetrics(0, state.currentRatePerSec, GuardMode.DISABLED);
        return;
      }
      GuardMode mode = GuardMode.STEADY;
      boolean prefillActive = state.isPrefillActive();
      double prefillFactor = 1.0;
      if (prefillActive && settings.prefill().liftPct() > 0) {
        prefillFactor = 1.0 + (settings.prefill().liftPct() / 100.0);
        mode = GuardMode.PREFILL;
      }

      int minDepth = (int) Math.round(settings.minDepth() * prefillFactor);
      int maxDepth = (int) Math.round(settings.maxDepth() * prefillFactor);
      double targetDepth = settings.targetDepth() * prefillFactor;
      PropertiesWrapper stats = fetchQueueStats(settings.queueName());
      if (!stats.present()) {
        log.debug("Buffer guard queue {} not found; skipping tick", settings.queueName());
        updateMetrics(0, state.currentRatePerSec, GuardMode.DISABLED);
        return;
      }
      long depth = stats.depth();
      state.recordSample(depth, settings.movingAverageWindow());
      double average = state.averageDepth();
      double currentRate = state.currentRatePerSec;
      double drainPerSec = state.updateDrain(depth, settings.samplePeriod());
      double nextRate = currentRate;

      if (average < minDepth) {
        mode = GuardMode.FILLING;
        double step = Math.max(1.0, currentRate * settings.adjust().maxIncreasePct() / 100.0);
        nextRate = clampRate(currentRate + step);
      } else if (average > maxDepth) {
        mode = GuardMode.DRAINING;
        double step = Math.max(1.0, currentRate * settings.adjust().maxDecreasePct() / 100.0);
        nextRate = clampRate(currentRate - step);
      } else {
        double desired = feedForwardRate(drainPerSec, average, targetDepth);
        double limited = boundedStep(currentRate, desired);
        nextRate = clampRate(limited);
      }

      BufferGuardSettings.Backpressure backpressure = settings.backpressure();
      if (backpressure.queueName() != null) {
        PropertiesWrapper downstream = fetchQueueStats(backpressure.queueName());
        long downstreamDepth = downstream.present() ? downstream.depth() : 0L;
        if (state.updateBackpressure(downstreamDepth, backpressure)) {
          mode = GuardMode.BACKPRESSURE;
          nextRate = clampRate(settings.adjust().minRatePerSec());
        }
      }

      boolean modeChanged = state.updateMode(mode);
      if (modeChanged) {
        log.info(
            "Buffer guard [{}] state -> {} (target={}, bracket={}..{}, avgDepth={})",
            settings.queueAlias(),
            mode,
            settings.targetDepth(),
            minDepth,
            maxDepth,
            Math.round(average));
      }

      if (shouldSendRateChange(currentRate, nextRate)) {
        log.info(
            "Buffer guard [{}] adjusting {} rate {} -> {} (state={}, avgDepth={}, depth={}, queue={})",
            settings.queueAlias(),
            settings.targetRole(),
            currentRate,
            nextRate,
            mode,
            Math.round(average),
            depth,
            settings.queueName());
        ratePublisher.publish(nextRate);
        state.currentRatePerSec = nextRate;
      }
      updateMetrics(average, state.currentRatePerSec, mode);
    } catch (Exception ex) {
      log.warn("Buffer guard tick failed", ex);
    }
  }

  private PropertiesWrapper fetchQueueStats(String queueName) {
    Object result = amqp.getQueueProperties(queueName);
    if (!(result instanceof java.util.Properties props)) {
      return PropertiesWrapper.missing();
    }
    long depth = coerceLong(props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
    return PropertiesWrapper.present(depth);
  }

  private boolean shouldSendRateChange(double current, double candidate) {
    if (!Double.isFinite(candidate)) {
      return false;
    }
    double baseline = Math.max(1.0, current);
    double diff = Math.abs(candidate - current);
    return diff / baseline >= 0.01;
  }

  private double clampRate(double candidate) {
    double min = Math.max(0d, settings.adjust().minRatePerSec());
    double max = Math.max(min, settings.adjust().maxRatePerSec());
    if (!Double.isFinite(candidate)) {
      return min;
    }
    return Math.max(min, Math.min(max, candidate));
  }

  private double feedForwardRate(double drainPerSec, double depth, double targetDepth) {
    double delta = targetDepth - depth;
    double proportion = (settings.adjust().maxIncreasePct() + settings.adjust().maxDecreasePct()) / 200.0;
    double windowSeconds = Math.max(1.0, settings.samplePeriod().toMillis() / 1000.0);
    double correction = (delta / windowSeconds) * proportion;
    return drainPerSec + correction;
  }

  private double boundedStep(double currentRate, double desiredRate) {
    double maxIncrease = currentRate * settings.adjust().maxIncreasePct() / 100.0;
    double maxDecrease = currentRate * settings.adjust().maxDecreasePct() / 100.0;
    double delta = desiredRate - currentRate;
    if (delta > 0) {
      delta = Math.min(Math.max(1.0, maxIncrease), delta);
    } else if (delta < 0) {
      delta = Math.max(-Math.max(1.0, Math.abs(maxDecrease)), delta);
    }
    return currentRate + delta;
  }

  private void registerGauges() {
    depthGauge = Gauge.builder("ph_swarm_buffer_guard_depth", depthValue, AtomicLong::doubleValue)
        .description("Moving average depth observed by buffer guard")
        .tags(metricTags)
        .register(meterRegistry);
    targetGauge = Gauge.builder("ph_swarm_buffer_guard_target", targetValue, AtomicLong::doubleValue)
        .description("Target depth configured for buffer guard")
        .tags(metricTags)
        .register(meterRegistry);
    rateGauge = Gauge.builder("ph_swarm_buffer_guard_rate_per_sec", rateValue, AtomicReference::get)
        .description("Latest rate override issued by buffer guard")
        .tags(metricTags)
        .register(meterRegistry);
    stateGauge = Gauge.builder("ph_swarm_buffer_guard_state", stateValue, AtomicInteger::doubleValue)
        .description("Buffer guard state code (0=disabled,1=steady,2=prefill,3=filling,4=draining,5=backpressure)")
        .tags(metricTags)
        .register(meterRegistry);
  }

  private void removeGauges() {
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

  private void updateMetrics(double averageDepth, double ratePerSec, GuardMode mode) {
    depthValue.set(Math.round(averageDepth));
    targetValue.set(settings.targetDepth());
    rateValue.set(ratePerSec);
    stateValue.set(mode.code);
  }

  private static long coerceLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string) {
      try {
        return Long.parseLong(string);
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }

  private enum GuardMode {
    DISABLED(0),
    STEADY(1),
    PREFILL(2),
    FILLING(3),
    DRAINING(4),
    BACKPRESSURE(5);

    final int code;

    GuardMode(int code) {
      this.code = code;
    }
  }

  private static final class BufferGuardState {
    private final BufferGuardSettings settings;
    private final ArrayDeque<Long> samples = new ArrayDeque<>();
    private double currentRatePerSec;
    private long prefillEnd;
    private boolean backpressureActive;
    private GuardMode mode;
    private long lastDepth = -1;
    private long lastSampleNanos = System.nanoTime();

    BufferGuardState(BufferGuardSettings settings) {
      this.settings = settings;
      this.currentRatePerSec = settings.initialRatePerSec();
      this.prefillEnd = System.currentTimeMillis() + settings.prefill().lookahead().toMillis();
      this.mode = GuardMode.DISABLED;
    }

    void recordSample(long depth, int window) {
      samples.addLast(depth);
      int targetWindow = Math.max(1, window);
      while (samples.size() > targetWindow) {
        samples.removeFirst();
      }
    }

    double averageDepth() {
      if (samples.isEmpty()) {
        return 0.0;
      }
      long total = 0;
      for (Long sample : samples) {
        total += sample;
      }
      return (double) total / samples.size();
    }

    boolean isPrefillActive() {
      if (!settings.prefill().enabled()) {
        return false;
      }
      return System.currentTimeMillis() < prefillEnd;
    }

    boolean updateBackpressure(long downstreamDepth, BufferGuardSettings.Backpressure backpressure) {
      if (backpressure.queueName() == null) {
        return false;
      }
      if (downstreamDepth >= backpressure.highDepth()) {
        backpressureActive = true;
      } else if (backpressureActive && downstreamDepth <= backpressure.recoveryDepth()) {
        backpressureActive = false;
      }
      return backpressureActive;
    }

    boolean updateMode(GuardMode candidate) {
      if (candidate == null) {
        return false;
      }
      if (mode != candidate) {
        mode = candidate;
        return true;
      }
      return false;
    }

    double updateDrain(long depth, Duration samplePeriod) {
      long now = System.nanoTime();
      double elapsedSeconds = (now - lastSampleNanos) / 1_000_000_000.0;
      if (elapsedSeconds < 0.001) {
        elapsedSeconds = Math.max(0.001, samplePeriod.toMillis() / 1000.0);
      }
      double drain = lastDepth < 0 ? currentRatePerSec : (lastDepth - depth) / elapsedSeconds;
      lastDepth = depth;
      lastSampleNanos = now;
      return drain;
    }
  }

  private record PropertiesWrapper(boolean present, long depth) {
    static PropertiesWrapper missing() {
      return new PropertiesWrapper(false, 0);
    }

    static PropertiesWrapper present(long depth) {
      return new PropertiesWrapper(true, depth);
    }
  }
}
