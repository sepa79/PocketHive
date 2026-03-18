package io.pockethive.postprocessor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.sink.influxdb3")
public class InfluxDb3SinkProperties {

  private String endpoint = "";
  private String database = "";
  private String token = "";
  private String measurement = "";
  private int connectTimeoutMs = 3000;
  private int readTimeoutMs = 5000;
  private int batchSize = 200;
  private int flushIntervalMs = 200;
  private int maxBufferedEvents = 50_000;

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getDatabase() {
    return database;
  }

  public void setDatabase(String database) {
    this.database = database;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getMeasurement() {
    return measurement;
  }

  public void setMeasurement(String measurement) {
    this.measurement = measurement;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getFlushIntervalMs() {
    return flushIntervalMs;
  }

  public void setFlushIntervalMs(int flushIntervalMs) {
    this.flushIntervalMs = flushIntervalMs;
  }

  public int getMaxBufferedEvents() {
    return maxBufferedEvents;
  }

  public void setMaxBufferedEvents(int maxBufferedEvents) {
    this.maxBufferedEvents = maxBufferedEvents;
  }

  public boolean configured() {
    return endpoint != null
        && !endpoint.isBlank()
        && database != null
        && !database.isBlank()
        && measurement != null
        && !measurement.isBlank();
  }
}
