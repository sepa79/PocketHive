package io.pockethive.observability.metrics;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class ClickHouseMetricsLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ClickHouseMetricsLifecycle.class);

  private final PocketHiveMetricsProperties properties;
  private final MicrometerClickHouseMetricsPublisher publisher;
  private ScheduledExecutorService executor;
  private ScheduledFuture<?> task;
  private volatile boolean running;

  public ClickHouseMetricsLifecycle(
      PocketHiveMetricsProperties properties,
      MicrometerClickHouseMetricsPublisher publisher) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    properties.requireClickHouseAdapter();
    long intervalMs = properties.getPublishInterval().toMillis();
    executor = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "pockethive-clickhouse-metrics-publisher");
      thread.setDaemon(true);
      return thread;
    });
    task = executor.scheduleWithFixedDelay(this::publishSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    running = true;
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    if (task != null) {
      task.cancel(false);
      task = null;
    }
    if (executor != null) {
      executor.shutdown();
      executor = null;
    }
    publishSafely();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  private void publishSafely() {
    try {
      MicrometerClickHouseMetricsPublisher.PublishResult result = publisher.publishOnce();
      log.debug(
          "Published ClickHouse metrics meters={} samples={} skipped={} rejected={}",
          result.meters(),
          result.samples(),
          result.skippedMeasurements(),
          result.rejectedSamples());
    } catch (Exception ex) {
      log.warn("Failed to publish ClickHouse metrics", ex);
    }
  }
}
