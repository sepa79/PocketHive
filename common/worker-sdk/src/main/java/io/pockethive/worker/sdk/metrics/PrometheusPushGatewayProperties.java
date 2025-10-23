package io.pockethive.worker.sdk.metrics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Prometheus Pushgateway configuration required by control-plane workers.
 */
@Validated
@ConfigurationProperties("management.prometheus.metrics.export.pushgateway")
public record PrometheusPushGatewayProperties(
    @NotNull Boolean enabled,
    @NotNull Duration pushRate,
    @NotBlank String baseUrl,
    @NotBlank String job,
    @NotBlank String shutdownOperation,
    @Valid @NotNull GroupingKey groupingKey) {

  public static final String ENABLED_PROPERTY =
      "management.prometheus.metrics.export.pushgateway.enabled";
  public static final String BASE_URL_PROPERTY =
      "management.prometheus.metrics.export.pushgateway.base-url";
  public static final String PUSH_RATE_PROPERTY =
      "management.prometheus.metrics.export.pushgateway.push-rate";
  public static final String JOB_PROPERTY =
      "management.prometheus.metrics.export.pushgateway.job";
  public static final String SHUTDOWN_OPERATION_PROPERTY =
      "management.prometheus.metrics.export.pushgateway.shutdown-operation";
  public static final String GROUPING_KEY_INSTANCE_PROPERTY =
      "management.prometheus.metrics.export.pushgateway.grouping-key.instance";

  public static record GroupingKey(@NotBlank String instance) {}
}
