package io.pockethive.swarmcontroller.infra.amqp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.pockethive.swarmcontroller.QueueStats;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks per-queue metrics (depth, consumers, oldest age) for a swarm.
 * <p>
 * This helper owns Micrometer gauge registration and provides a small API for
 * updating and unregistering metrics so that {@code SwarmLifecycleManager}
 * does not need to manage gauge maps directly.
 */
public final class SwarmQueueMetrics {

  private final String swarmId;
  private final MeterRegistry meterRegistry;

  private final ConcurrentMap<String, AtomicLong> queueDepthValues = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicInteger> queueConsumerValues = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicLong> queueOldestValues = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Gauge> queueDepthGauges = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Gauge> queueConsumerGauges = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Gauge> queueOldestGauges = new ConcurrentHashMap<>();

  public SwarmQueueMetrics(String swarmId, MeterRegistry meterRegistry) {
    this.swarmId = Objects.requireNonNull(swarmId, "swarmId");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
  }

  public void update(String queueName, QueueStats stats) {
    Objects.requireNonNull(queueName, "queueName");
    Objects.requireNonNull(stats, "stats");

    AtomicLong depthValue = queueDepthValues.computeIfAbsent(queueName, this::registerDepthGauge);
    depthValue.set(stats.depth());

    AtomicInteger consumerValue =
        queueConsumerValues.computeIfAbsent(queueName, this::registerConsumerGauge);
    consumerValue.set(stats.consumers());

    long oldestValue = stats.oldestAgeSec().orElse(-1L);
    AtomicLong oldestGaugeValue =
        queueOldestValues.computeIfAbsent(queueName, this::registerOldestGauge);
    oldestGaugeValue.set(oldestValue);
  }

  public void unregister(String queueName) {
    if (queueName == null || queueName.isBlank()) {
      return;
    }
    AtomicLong depthValue = queueDepthValues.remove(queueName);
    if (depthValue != null) {
      Gauge gauge = queueDepthGauges.remove(queueName);
      if (gauge != null) {
        meterRegistry.remove(gauge);
      }
    }
    AtomicInteger consumerValue = queueConsumerValues.remove(queueName);
    if (consumerValue != null) {
      Gauge gauge = queueConsumerGauges.remove(queueName);
      if (gauge != null) {
        meterRegistry.remove(gauge);
      }
    }
    AtomicLong oldestValue = queueOldestValues.remove(queueName);
    if (oldestValue != null) {
      Gauge gauge = queueOldestGauges.remove(queueName);
      if (gauge != null) {
        meterRegistry.remove(gauge);
      }
    }
  }

  private AtomicLong registerDepthGauge(String queueName) {
    AtomicLong holder = new AtomicLong();
    Gauge gauge = Gauge.builder("ph_swarm_queue_depth", holder, AtomicLong::doubleValue)
        .description("Depth of a PocketHive swarm queue")
        .tags(queueTags(queueName))
        .register(meterRegistry);
    queueDepthGauges.put(queueName, gauge);
    return holder;
  }

  private AtomicInteger registerConsumerGauge(String queueName) {
    AtomicInteger holder = new AtomicInteger();
    Gauge gauge = Gauge.builder("ph_swarm_queue_consumers", holder, AtomicInteger::doubleValue)
        .description("Active consumer count for a PocketHive swarm queue")
        .tags(queueTags(queueName))
        .register(meterRegistry);
    queueConsumerGauges.put(queueName, gauge);
    return holder;
  }

  private AtomicLong registerOldestGauge(String queueName) {
    AtomicLong holder = new AtomicLong(-1L);
    Gauge gauge = Gauge.builder("ph_swarm_queue_oldest_age_seconds", holder, AtomicLong::doubleValue)
        .description("Age in seconds of the oldest message visible on a PocketHive swarm queue")
        .tags(queueTags(queueName))
        .register(meterRegistry);
    queueOldestGauges.put(queueName, gauge);
    return holder;
  }

  private Tags queueTags(String queueName) {
    return Tags.of("swarm", swarmId, "queue", queueName);
  }
}

