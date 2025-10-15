package io.pockethive.observability.metrics;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.PushGateway;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoggingPushGateway extends PushGateway {

  private static final Logger log = LoggerFactory.getLogger(LoggingPushGateway.class);

  private final String gatewayBaseUrl;

  LoggingPushGateway(URL baseUrl) {
    super(baseUrl);
    this.gatewayBaseUrl = baseUrl.toString();
  }

  LoggingPushGateway(String address) {
    super(address);
    this.gatewayBaseUrl = address;
  }

  String getGatewayBaseUrl() {
    return gatewayBaseUrl;
  }

  @Override
  public void pushAdd(CollectorRegistry registry, String job, Map<String, String> groupingKey)
      throws IOException {
    MetricsSnapshot snapshot = snapshot(registry);
    log.info(
        "Pushgateway POST add {} job={} groupingKey={} families={} samples={} metricNames={}",
        getGatewayBaseUrl(),
        job,
        describeGroupingKey(groupingKey),
        snapshot.familyCount(),
        snapshot.sampleCount(),
        snapshot.metricNames());
    try {
      super.pushAdd(registry, job, groupingKey);
      log.info(
          "Pushgateway POST add succeeded job={} groupingKey={} families={} samples={}",
          job,
          describeGroupingKey(groupingKey),
          snapshot.familyCount(),
          snapshot.sampleCount());
    } catch (IOException ex) {
      log.warn(
          "Pushgateway POST add failed job={} groupingKey={} families={} samples={}",
          job,
          describeGroupingKey(groupingKey),
          snapshot.familyCount(),
          snapshot.sampleCount(),
          ex);
      throw ex;
    }
  }

  @Override
  public void push(CollectorRegistry registry, String job, Map<String, String> groupingKey)
      throws IOException {
    MetricsSnapshot snapshot = snapshot(registry);
    log.info(
        "Pushgateway PUT {} job={} groupingKey={} families={} samples={} metricNames={}",
        getGatewayBaseUrl(),
        job,
        describeGroupingKey(groupingKey),
        snapshot.familyCount(),
        snapshot.sampleCount(),
        snapshot.metricNames());
    try {
      super.push(registry, job, groupingKey);
      log.info(
          "Pushgateway PUT succeeded job={} groupingKey={} families={} samples={}",
          job,
          describeGroupingKey(groupingKey),
          snapshot.familyCount(),
          snapshot.sampleCount());
    } catch (IOException ex) {
      log.warn(
          "Pushgateway PUT failed job={} groupingKey={} families={} samples={}",
          job,
          describeGroupingKey(groupingKey),
          snapshot.familyCount(),
          snapshot.sampleCount(),
          ex);
      throw ex;
    }
  }

  @Override
  public void delete(String job, Map<String, String> groupingKey) throws IOException {
    log.info(
        "Pushgateway DELETE {} job={} groupingKey={}",
        getGatewayBaseUrl(),
        job,
        describeGroupingKey(groupingKey));
    try {
      super.delete(job, groupingKey);
      log.info("Pushgateway DELETE succeeded job={} groupingKey={}", job, describeGroupingKey(groupingKey));
    } catch (IOException ex) {
      log.warn("Pushgateway DELETE failed job={} groupingKey={}", job, describeGroupingKey(groupingKey), ex);
      throw ex;
    }
  }

  private MetricsSnapshot snapshot(CollectorRegistry registry) {
    if (registry == null) {
      return MetricsSnapshot.empty();
    }
    List<Collector.MetricFamilySamples> families = Collections.list(registry.metricFamilySamples());
    int sampleCount = 0;
    List<String> names = new ArrayList<>(families.size());
    for (Collector.MetricFamilySamples family : families) {
      if (family == null) {
        continue;
      }
      names.add(family.name);
      if (family.samples != null) {
        sampleCount += family.samples.size();
      }
    }
    return new MetricsSnapshot(families.size(), sampleCount, summarizeNames(names));
  }

  private String summarizeNames(List<String> names) {
    if (names.isEmpty()) {
      return "none";
    }
    if (names.size() <= 5) {
      return String.join(", ", names);
    }
    return String.join(", ", names.subList(0, 5)) + ", … (" + names.size() + " total)";
  }

  private String describeGroupingKey(Map<String, String> groupingKey) {
    if (groupingKey == null || groupingKey.isEmpty()) {
      return "{}";
    }
    return groupingKey.toString();
  }

  private record MetricsSnapshot(int familyCount, int sampleCount, String metricNames) {

    static MetricsSnapshot empty() {
      return new MetricsSnapshot(0, 0, "none");
    }
  }
}
