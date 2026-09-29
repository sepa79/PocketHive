package io.pockethive.sink.clickhouse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseSinkEnvironmentTest {
  @Test
  void exportsConfiguredValuesWithoutReplacingExplicitOverrides() {
    var properties = configured();
    properties.setConnectTimeoutMs(123);
    properties.setReadTimeoutMs(456);
    properties.setBatchSize(7);
    properties.setFlushIntervalMs(890);
    properties.setMaxBufferedEvents(12);
    Map<String, String> env = new HashMap<>();
    env.put("UNRELATED", "preserved");
    ClickHouseSinkEnvironment.applyMissing(env, properties);
    assertThat(env).containsExactlyInAnyOrderEntriesOf(Map.of(
        "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", "http://clickhouse:8123",
        "POCKETHIVE_SINK_CLICKHOUSE_TABLE", "db.events",
        "POCKETHIVE_SINK_CLICKHOUSE_USERNAME", "user",
        "POCKETHIVE_SINK_CLICKHOUSE_PASSWORD", "pass",
        "POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS", "123",
        "POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS", "456",
        "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE", "7",
        "POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS", "890",
        "POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS", "12",
        "UNRELATED", "preserved"));
    var binder = new org.springframework.boot.context.properties.bind.Binder(
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(
            new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", new HashMap<String, Object>(env))));
    var bound = binder.bind("pockethive.sink.clickhouse",
        org.springframework.boot.context.properties.bind.Bindable.of(ClickHouseSinkProperties.class))
        .orElseThrow(() -> new AssertionError("Missing sink settings"));
    assertThat(bound.getEndpoint()).isEqualTo("http://clickhouse:8123");
    assertThat(bound.getTable()).isEqualTo("db.events");
    assertThat(bound.getUsername()).isEqualTo("user");
    assertThat(bound.getPassword()).isEqualTo("pass");
    assertThat(bound.getConnectTimeoutMs()).isEqualTo(123);
    assertThat(bound.getReadTimeoutMs()).isEqualTo(456);
    assertThat(bound.getBatchSize()).isEqualTo(7);
    assertThat(bound.getFlushIntervalMs()).isEqualTo(890);
    assertThat(bound.getMaxBufferedEvents()).isEqualTo(12);
    env.put(ClickHouseSinkEnvironment.ENDPOINT, "http://override:8123");
    env.put(ClickHouseSinkEnvironment.USERNAME, "");
    env.put(ClickHouseSinkEnvironment.PASSWORD, null);
    var before = new HashMap<>(env);
    ClickHouseSinkEnvironment.applyMissing(env, properties);
    assertThat(env).isEqualTo(before);
  }

  @Test
  void doesNotExportPartialConfigurationOrBlankCredentials() {
    var properties = configured();
    properties.setTable(" ");
    Map<String, String> env = new HashMap<>();
    ClickHouseSinkEnvironment.applyMissing(env, properties);
    assertThat(env).isEmpty();
    properties.setTable("events");
    properties.setUsername(" ");
    properties.setPassword(null);
    properties.setBatchSize(0);
    ClickHouseSinkEnvironment.applyMissing(env, properties);
    assertThat(env).doesNotContainKeys(ClickHouseSinkEnvironment.USERNAME, ClickHouseSinkEnvironment.PASSWORD)
        .containsEntry(ClickHouseSinkEnvironment.BATCH_SIZE, "0");
  }

  private static ClickHouseSinkProperties configured() {
    var properties = new ClickHouseSinkProperties();
    properties.setEndpoint(" http://clickhouse:8123 ");
    properties.setTable(" db.events ");
    properties.setUsername(" user ");
    properties.setPassword(" pass ");
    return properties;
  }
}
