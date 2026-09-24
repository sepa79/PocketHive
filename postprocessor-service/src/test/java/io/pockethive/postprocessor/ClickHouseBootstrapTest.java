package io.pockethive.postprocessor;

import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseBootstrapTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class)
      .withInitializer(context -> {
        try {
          new YamlPropertySourceLoader().load("serviceSettings", new ClassPathResource("application.yml"))
              .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
        } catch (IOException ex) {
          throw new UncheckedIOException(ex);
        }
      });

  @Test
  void absentSettingsRetainCanonicalDisabledDefaults() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      var settings = context.getBean(ClickHouseSinkProperties.class);
      assertThat(settings.configured()).isFalse();
      assertThat(settings).usingRecursiveComparison().isEqualTo(new ClickHouseSinkProperties());
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void bindsExistingEnvironmentNamesAndHonorsSourcePrecedence(boolean environmentFirst) {
    Map<String, Object> env = Map.of(
        "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", "http://env:8123",
        "POCKETHIVE_SINK_CLICKHOUSE_TABLE", "tx_outcomes",
        "POCKETHIVE_SINK_CLICKHOUSE_USERNAME", "writer",
        "POCKETHIVE_SINK_CLICKHOUSE_PASSWORD", "test-password",
        "POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS", "123",
        "POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS", "456",
        "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE", "7",
        "POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS", "89",
        "POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS", "999");
    runner.withPropertyValues("pockethive.sink.clickhouse.endpoint=http://property:8123")
        .withInitializer(context -> {
          var source = new SystemEnvironmentPropertySource("systemEnvironment", env);
          if (environmentFirst) {
            context.getEnvironment().getPropertySources().addFirst(source);
          } else {
            context.getEnvironment().getPropertySources().replace("systemEnvironment", source);
          }
        }).run(context -> {
          assertThat(context).hasNotFailed();
          var settings = context.getBean(ClickHouseSinkProperties.class);
          assertThat(settings.configured()).isTrue();
          assertThat(settings.getEndpoint()).isEqualTo(environmentFirst ? "http://env:8123" : "http://property:8123");
          assertThat(settings.getTable()).isEqualTo("tx_outcomes");
          assertThat(settings.getUsername()).isEqualTo("writer");
          assertThat(settings.getPassword()).isEqualTo("test-password");
          assertThat(settings.getConnectTimeoutMs()).isEqualTo(123);
          assertThat(settings.getReadTimeoutMs()).isEqualTo(456);
          assertThat(settings.getBatchSize()).isEqualTo(7);
          assertThat(settings.getFlushIntervalMs()).isEqualTo(89);
          assertThat(settings.getMaxBufferedEvents()).isEqualTo(999);
        });
  }

  @EnableConfigurationProperties(ClickHouseSinkProperties.class)
  static class Config {}
}
