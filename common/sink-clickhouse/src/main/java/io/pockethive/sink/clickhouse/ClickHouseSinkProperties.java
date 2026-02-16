package io.pockethive.sink.clickhouse;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.sink.clickhouse")
public class ClickHouseSinkProperties {

  private String endpoint = "";
  private String table = "";
  private String username = "";
  private String password = "";
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

  public String getTable() {
    return table;
  }

  public void setTable(String table) {
    this.table = table;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
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
    return endpoint != null && !endpoint.isBlank() && table != null && !table.isBlank();
  }
}
