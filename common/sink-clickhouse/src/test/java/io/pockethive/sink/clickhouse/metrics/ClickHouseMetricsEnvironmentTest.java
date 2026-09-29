package io.pockethive.sink.clickhouse.metrics;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseMetricsEnvironmentTest {
  @Test
  void bothProjectionsBindBackToEveryAcceptedMetricSetting() {
    var properties = new ClickHouseMetricsSinkProperties();
    properties.setEndpoint(" http://metrics:8123 ");
    properties.setTable(" custom.metrics ");
    properties.setUsername(" writer ");
    properties.setPassword(" password ");
    properties.setConnectTimeoutMs(123);
    properties.setReadTimeoutMs(456);
    properties.setBatchSize(7);
    properties.setFlushIntervalMs(89);
    properties.setMaxBufferedSamples(999);
    properties.setMaxLabelCount(2);
    properties.setMaxLabelKeyLength(12);
    properties.setMaxLabelValueLength(34);
    Map<String, String> env = new LinkedHashMap<>();
    env.put("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", "old");
    env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_BATCH_SIZE", "100");
    ClickHouseMetricsEnvironment.applyRuntime(env, properties);
    ClickHouseMetricsEnvironment.applyController(env, properties);
    assertThat(env).hasSize(24);
    var source = new SystemEnvironmentPropertySource("systemEnvironment", new HashMap<String, Object>(env));
    var binder = new Binder(ConfigurationPropertySources.from(source));
    for (String prefix : new String[]{"pockethive.metrics.clickhouse",
        "pockethive.control-plane.swarm-controller.metrics.clickhouse"}) {
      var bound = binder.bind(prefix, Bindable.of(ClickHouseMetricsSinkProperties.class)).orElseThrow(() -> new AssertionError("No bound settings for " + prefix));
      assertThat(bound).usingRecursiveComparison().isEqualTo(properties);
    }
  }

  @Test
  void disabledConfigurationAndBlankCredentialsLeaveExistingEntriesUntouched() {
    var properties = ClickHouseMetricsSinkProperties.disabled();
    Map<String, String> env = new HashMap<>();
    ClickHouseMetricsEnvironment.applyRuntime(env, properties);
    ClickHouseMetricsEnvironment.applyController(env, properties);
    assertThat(env).isEmpty();
    properties.setEndpoint("http://metrics:8123");
    env.put("POCKETHIVE_METRICS_CLICKHOUSE_USERNAME", "explicit");
    ClickHouseMetricsEnvironment.applyRuntime(env, properties);
    assertThat(env).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_USERNAME", "explicit")
        .doesNotContainKey("POCKETHIVE_METRICS_CLICKHOUSE_PASSWORD");
  }
}
