package io.pockethive.logaggregator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the required control-plane identifiers so they are validated once during startup and
 * available for any component that needs the swarm or manager instance identifiers.
 */
@Validated
@ConfigurationProperties("pockethive.control-plane")
public record LogAggregatorControlPlaneProperties(
    @NotBlank String swarmId, @NotNull @Valid Manager manager) {

  @Validated
  public record Manager(@NotBlank String instanceId, String role) {}
}
