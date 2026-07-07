package io.pockethive.sink.clickhouse.metrics;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.metrics.clickhouse")
public class ClickHouseMetricsSinkProperties {

  public static final String DEFAULT_TABLE = "ph_metrics_samples";
  private static final Pattern TABLE_NAME =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

  private String endpoint = "";
  private String table = DEFAULT_TABLE;
  private String username = "";
  private String password = "";
  private int connectTimeoutMs = 3000;
  private int readTimeoutMs = 5000;
  private int batchSize = 200;
  private int flushIntervalMs = 200;
  private int maxBufferedSamples = 50_000;
  private int maxLabelCount = 20;
  private int maxLabelKeyLength = 80;
  private int maxLabelValueLength = 256;

  public static ClickHouseMetricsSinkProperties disabled() {
    return new ClickHouseMetricsSinkProperties();
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = normalize(endpoint);
  }

  public String getTable() {
    return table;
  }

  public void setTable(String table) {
    String resolved = normalizeOrDefault(table, DEFAULT_TABLE);
    if (!TABLE_NAME.matcher(resolved).matches()) {
      throw new IllegalArgumentException("ClickHouse metrics table name is invalid: " + resolved);
    }
    this.table = resolved;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = normalize(username);
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = normalize(password);
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = requirePositive(connectTimeoutMs, "connectTimeoutMs");
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = requirePositive(readTimeoutMs, "readTimeoutMs");
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = requirePositive(batchSize, "batchSize");
  }

  public int getFlushIntervalMs() {
    return flushIntervalMs;
  }

  public void setFlushIntervalMs(int flushIntervalMs) {
    this.flushIntervalMs = requirePositive(flushIntervalMs, "flushIntervalMs");
  }

  public int getMaxBufferedSamples() {
    return maxBufferedSamples;
  }

  public void setMaxBufferedSamples(int maxBufferedSamples) {
    this.maxBufferedSamples = requirePositive(maxBufferedSamples, "maxBufferedSamples");
  }

  public int getMaxLabelCount() {
    return maxLabelCount;
  }

  public void setMaxLabelCount(int maxLabelCount) {
    this.maxLabelCount = requireNonNegative(maxLabelCount, "maxLabelCount");
  }

  public int getMaxLabelKeyLength() {
    return maxLabelKeyLength;
  }

  public void setMaxLabelKeyLength(int maxLabelKeyLength) {
    this.maxLabelKeyLength = requirePositive(maxLabelKeyLength, "maxLabelKeyLength");
  }

  public int getMaxLabelValueLength() {
    return maxLabelValueLength;
  }

  public void setMaxLabelValueLength(int maxLabelValueLength) {
    this.maxLabelValueLength = requirePositive(maxLabelValueLength, "maxLabelValueLength");
  }

  public boolean configured() {
    return endpoint != null && !endpoint.isBlank() && table != null && !table.isBlank();
  }

  public void requireConfigured() {
    if (!configured()) {
      throw new IllegalStateException("ClickHouse metrics settings require endpoint/table");
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalizeOrDefault(String value, String defaultValue) {
    String normalized = normalize(value);
    return normalized.isBlank() ? defaultValue : normalized;
  }

  private static int requirePositive(int value, String fieldName) {
    if (value <= 0) {
      throw new IllegalArgumentException("metrics clickhouse " + fieldName + " must be positive");
    }
    return value;
  }

  private static int requireNonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException("metrics clickhouse " + fieldName + " must not be negative");
    }
    return value;
  }
}
