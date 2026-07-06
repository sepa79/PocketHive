package io.pockethive.observability.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.metrics")
public class PocketHiveMetricsProperties {

  private PocketHiveMetricsAdapter adapter;
  private Duration publishInterval;
  private String swarmId = "";
  private String runId = "";
  private String role = "";
  private String instance = "";

  public PocketHiveMetricsAdapter getAdapter() {
    return adapter;
  }

  public void setAdapter(PocketHiveMetricsAdapter adapter) {
    this.adapter = adapter;
  }

  public Duration getPublishInterval() {
    return publishInterval;
  }

  public void setPublishInterval(Duration publishInterval) {
    this.publishInterval = publishInterval;
  }

  public String getSwarmId() {
    return swarmId;
  }

  public void setSwarmId(String swarmId) {
    this.swarmId = swarmId;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getInstance() {
    return instance;
  }

  public void setInstance(String instance) {
    this.instance = instance;
  }

  void requireClickHouseAdapter() {
    if (adapter != PocketHiveMetricsAdapter.CLICKHOUSE) {
      throw new IllegalStateException("pockethive.metrics.adapter must be CLICKHOUSE");
    }
    if (publishInterval == null || publishInterval.isZero() || publishInterval.isNegative()) {
      throw new IllegalStateException("pockethive.metrics.publish-interval must be positive");
    }
    requireNonBlank(swarmId, "pockethive.metrics.swarm-id");
    requireNonBlank(runId, "pockethive.metrics.run-id");
    requireNonBlank(role, "pockethive.metrics.role");
    requireNonBlank(instance, "pockethive.metrics.instance");
  }

  String requiredSwarmId() {
    return requireNonBlank(swarmId, "pockethive.metrics.swarm-id");
  }

  String requiredRunId() {
    return requireNonBlank(runId, "pockethive.metrics.run-id");
  }

  String requiredRole() {
    return requireNonBlank(role, "pockethive.metrics.role");
  }

  String requiredInstance() {
    return requireNonBlank(instance, "pockethive.metrics.instance");
  }

  private static String requireNonBlank(String value, String propertyName) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(propertyName + " must not be null or blank");
    }
    return value.trim();
  }
}
